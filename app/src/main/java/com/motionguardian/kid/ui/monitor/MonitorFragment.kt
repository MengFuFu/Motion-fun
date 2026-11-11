package com.motionguardian.kid.ui.monitor

import android.Manifest
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorRes
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.motionguardian.kid.R
import com.motionguardian.kid.core.camera.CameraChecker
import com.motionguardian.kid.core.camera.CameraPermissionLauncher
import com.motionguardian.kid.databinding.FragmentMonitorBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import javax.inject.Inject
import com.motionguardian.kid.core.camera.CameraPreviewController
import com.motionguardian.kid.core.camera.CameraStartResult
import com.motionguardian.kid.core.pose.PoseFrame
import com.motionguardian.kid.core.pose.PoseFrameAnalyzer
import com.motionguardian.kid.core.pose.PoseLandmarkerHelper
import com.motionguardian.kid.core.pose.PoseStats
import com.motionguardian.kid.domain.filter.GateCounter
import com.motionguardian.kid.domain.filter.LandmarkGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import com.motionguardian.kid.domain.geometry.PostureMetricsCalculator
import com.motionguardian.kid.domain.model.PostureMetrics

/**
 * 监测页。
 *
 * T05 起这个页面有三条并发的"流"：
 *   1. 相机采集（CameraX 内部线程）
 *   2. 模型推理（CameraX 分析线程）
 *   3. UI 刷新（主线程）
 * 三者的交汇点只有一处：分析器回调 → 切主线程 → 更新 HUD。
 * 任何一个回调里直接碰 View 都会崩（CalledFromWrongThreadException）。
 */
@AndroidEntryPoint
class MonitorFragment : Fragment() {

    // 双变量模式：_binding 可空，binding 非空快捷方式（只在 View 存活期间有效）
    private var _binding: FragmentMonitorBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var cameraChecker: CameraChecker

    // 权限申请器必须在 onCreate 里构造（即 STARTED 之前完成注册）。
    private lateinit var permissionLauncher: CameraPermissionLauncher

    /** 相机控制器不注入（它只需要 Context，没必要进 DI 图），View 重建时一起重建。 */
    private var previewController: CameraPreviewController? = null

    /** 帧分析器。View 重建时新建一个，绑定到当前视图。 */
    private var analyzer: PoseFrameAnalyzer? = null

    /** 模型实例。创建很贵，整个 View 生命周期内只建一次。 */
    private var poseEngine: PoseLandmarkerHelper? = null

    private var engineJob: Job? = null
    /** 启动预览的协程，用于避免重复启动。 */
    private var startJob: Job? = null

    private var engineReady = false
    /** 预览是否已经成功绑上（绑定成功一次就不再重复绑）。 */
    private var previewBound = false

    // 只在「本次进入页面」自动弹一次，避免用户拒绝后又立刻被弹第二次。
    private var autoRequestDone = false

    /** 连续无效帧计数：用于区分"偶尔丢一帧"和"真的看不到人"。 */
    private val gateCounter = GateCounter(warnThreshold = 3)

    /**
     * 最近一次成功算出的指标。
     * 由分析线程写、主线程读，所以必须 @Volatile——
     * 不加的话主线程可能一直读到 null（JMM 不保证可见性）。
     */
    @Volatile
    private var lastMetrics: PostureMetrics? = null

    /**
     * 页面状态。用 enum 而不是 sealed class：这里没有携带数据的需要，
     * enum 更轻、when 分支还能被编译器检查是否写全。
     */
    private enum class UiState {
        /** 设备没有摄像头。 */
        NO_CAMERA,
        /** 还没授权，但还能弹系统框（首次，或被拒后仍允许再问）。 */
        REQUESTABLE,
        /** 被永久拒绝，只能去系统设置。 */
        PERMANENTLY_DENIED,
        /** 权限已拿到，可以进入监测（T04 之前先显示占位）。 */
        READY
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher = CameraPermissionLauncher(this) { granted ->
            autoRequestDone = true
            if (granted) {
                // 重新走一遍完整流程：加载模型 → 起相机
                initPoseEngine()
            }
            render()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentMonitorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        previewController = CameraPreviewController(requireContext())
        analyzer = PoseFrameAnalyzer(
            onFrame = ::onPoseFrame,
            onStats = ::onPoseStats,
            onError = ::onPoseError,
        )
        binding.btnStateAction.setOnClickListener { onActionClick() }

        render()
        maybeAutoRequest()
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚从系统设置页手动开完权限回来。onResume 是唯一能可靠捕捉
        if(_binding != null) {
            render()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // 顺序：先停相机（停止投递新帧）→ 再关模型（此时没有推理在跑）→ 最后丢引用
        startJob?.cancel()
        startJob = null
        previewController?.stop()
        previewController = null

        // 解绑分析器，避免 CameraX 持有它导致 Fragment 泄漏
        analyzer = null

        engineJob?.cancel()
        engineJob = null
        poseEngine?.close()
        poseEngine = null
        engineReady = false
        previewBound = false

        _binding = null
    }

    // ==================== 模型加载 ====================
    /**
     * 加载模型。幂等：已在加载或已就绪时直接返回。
     */
    private fun initPoseEngine() {
        if (engineReady) {
            startPreview()
            return
        }
        if (engineJob?.isActive == true) return

        engineJob = viewLifecycleOwner.lifecycleScope.launch {
            binding.tvPreviewBadge.text = getString(R.string.pose_loading)
            binding.tvPreviewBadge.visibility = View.VISIBLE

            try {
                // Default 而不是 IO：这里是 CPU 密集（TFLite 初始化），
                // IO 调度器没有并发上限，放进去会和其他 IO 任务抢线程
                val engine = withContext(Dispatchers.Default) {
                    PoseLandmarkerHelper(requireContext())
                }
                poseEngine = engine
                analyzer?.engine = engine
                engineReady = true
                render()
            } catch (t: Throwable) {
                Log.e(TAG, "模型加载失败", t)
                showPanel(
                    titleRes = R.string.pose_model_missing_title,
                    descRes = R.string.pose_model_missing_desc,
                    actionTextRes = null,
                    iconColorRes = R.color.semantic_alert,
                )
            }
        }
    }

    // ==================== 状态与渲染 ====================

    /**
     * 每次调用都向系统重新问一遍，不缓存。
     * 权限是外部可变状态（用户随时能在设置里改），缓存只会带来「显示与实际不符」。
     */
    private fun resolveState(): UiState {
        if(!cameraChecker.hasCameraHardware()) return UiState.NO_CAMERA
        if(cameraChecker.isPermissionGranted()) return UiState.READY

        val canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(
            requireActivity(),
            Manifest.permission.CAMERA,
        )
        if (canAskAgain) return UiState.REQUESTABLE

        // 还能弹框 → 说明是首次，或者拒绝过但没勾「不再询问」
        // 靠我们自己记的标记区分——这就是 CameraChecker.wasAskedBefore() 的唯一用途。
        return if(cameraChecker.wasAskedBefore()) {
            UiState.PERMANENTLY_DENIED
        } else {
            UiState.REQUESTABLE
        }
    }

    // ==================== 渲染 ====================
    private fun render() {
        when (resolveState()) {
            UiState.READY -> {
                // 就绪：藏面板、亮出预览
                binding.panelState.visibility = View.GONE
                // 权限有了不代表能开工：模型还没加载完就先等
                if (!engineReady) {
                    initPoseEngine()
                } else {
                    startPreview()
                }
            }

            UiState.NO_CAMERA -> showPanel(
                R.string.camera_no_hardware_title,
                R.string.camera_no_hardware_desc,
                null,
                R.color.text_secondary,
            )

            UiState.REQUESTABLE -> showPanel(
                R.string.camera_need_title,
                if (cameraChecker.wasAskedBefore()) {
                    R.string.camera_need_desc_again
                } else {
                    R.string.camera_need_desc_first
                },
                R.string.camera_grant,
                R.color.brand_primary,
            )

            UiState.PERMANENTLY_DENIED -> showPanel(
                R.string.camera_denied_title,
                R.string.camera_denied_desc,
                R.string.camera_open_settings,
                R.color.semantic_warn,
            )
        }
    }

    /** 显示状态面板（并保证预览胶囊不挡在上面）。 */
    private fun showPanel(
        titleRes: Int,
        descRes: Int,
        actionTextRes: Int?,
        @ColorRes iconColorRes: Int,
    ) {
        binding.panelState.visibility = View.VISIBLE
        binding.tvPreviewBadge.visibility = View.GONE
        binding.tvHud.visibility = View.GONE

        binding.tvStateTitle.setText(titleRes)
        binding.tvStateDesc.setText(descRes)
        binding.ivStateIcon.imageTintList =
            ContextCompat.getColorStateList(requireContext(), iconColorRes)

        if (actionTextRes == null) {
            binding.btnStateAction.visibility = View.GONE
        } else {
            binding.btnStateAction.visibility = View.VISIBLE
            binding.btnStateAction.setText(actionTextRes)
        }
    }

    // ==================== 相机 ====================

    /**
     * 启动预览。三重防抖：
     *  - 已经绑成功了就不再绑（previewBound）
     *  - 正在启动中就不再启动（startJob.isActive）
     *  - 视图已销毁就不启动（_binding == null）
     * 没有这三层的话，onResume → render 会被反复触发，导致相机反复 bind/unbind。
     */
    private fun startPreview() {
        if(previewBound) return
        if(startJob?.isActive == true) return
        val controller = previewController ?: return
        val frameAnalyzer = analyzer ?: return
        val owner = viewLifecycleOwner

        startJob = owner.lifecycleScope.launch {
            // 1. 建分析用例
            val analysis = ImageAnalysis.Builder()
                // KEEP_ONLY_LATEST：处理不过来就丢旧帧，永远只处理最新的一张。
                // 用 QUEUE 会积压，画面越跑越延迟（延迟 1 秒以上）
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // 输出 RGBA 而不是默认 YUV：我们不需要 YUV，直接拷进 Bitmap 最快。
                // 代价是内存带宽略高，但省掉了 YUV→RGB 的手写转换
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(CameraExecutor, frameAnalyzer)

            // 2. 和 Preview 一起绑定（必须同一次 bind，否则会互相 unbind）
            when(val result = controller.start(binding.previewView, owner, listOf(analysis))) {
                is CameraStartResult.Success -> {
                    previewBound = true
                    showBadge(result.lensFacing)
                }

                CameraStartResult.NoCamera -> showPanel(
                    R.string.camera_no_hardware_title,
                    R.string.camera_no_hardware_desc,
                    null,
                    R.color.text_secondary,
                )

                is CameraStartResult.Failed -> showPanel(
                    R.string.camera_error_title,
                    R.string.camera_error_desc,
                    null,
                    R.color.semantic_alert,
                )
            }
        }
    }

    private fun showBadge(lensFacing: Int?) {
        val lens = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            R.string.camera_badge_front
        } else {
            R.string.camera_badge_back
        }
        binding.tvPreviewBadge.text =
            getString(lens) + " " + getString(R.string.camera_badge_ready)
        binding.tvPreviewBadge.visibility = View.VISIBLE
    }

    // ==================== 识别回调（都在分析线程，必须切主线程） ====================
    /**
     * 收到一帧关键点。**运行在分析线程**，绝对不能碰 UI。
     *
     * 这里只做两件轻量的事：算指标、更新计数器。
     * 真正的界面刷新交给 onPoseStats（它会被切到主线程）。
     */
    private fun onPoseFrame(frame: PoseFrame) {
        val metrics = PostureMetricsCalculator.calculate(frame)

        if (metrics == null) {
            // 关键点不可信或人太远：累加无效帧计数，但不重置 lastMetrics。
            // 为什么不重置：短暂丢一帧时保留上一帧数值，界面不会闪烁。
            gateCounter.miss()
        } else {
            gateCounter.reset()
            lastMetrics = metrics
        }
    }

    /**
     * 每帧都会回调，用来刷新 HUD。**运行在分析线程**，必须切主线程。
     */
    private fun onPoseStats(stats: PoseStats) {
        binding?.root?.post {
            val b = _binding ?: return@post
            b.tvHud.visibility = View.VISIBLE

            // 第一行：管道健康度。帧号不涨 = 管道堵了；耗时暴涨 = 设备吃力
            val head = String.format(
                Locale.US,
                "%.1ffps %dms #%d",
                stats.fps,
                stats.lastInferenceMs,
                stats.totalFrames,
            )

            // 第二、三行：指标数值。没有数值时分清"没人"和"看不清"
            val m = lastMetrics
            val body = if (m == null) {
                if (gateCounter.shouldWarn) {
                    "看不到你 · 请坐正对镜头"
                } else {
                    "识别中…"
                }
            } else {
                String.format(
                    Locale.US,
                    "头垂 %.2f  肩斜 %.1f°  头斜 %.1f°\n躯干 %.1f°  颈前 %.2f  肩宽 %.2f",
                    m.headDropRatio,
                    m.shoulderTiltDeg,
                    m.headTiltDeg,
                    m.trunkTiltDeg,
                    m.neckForwardRatio,
                    m.shoulderWidth,
                )
            }

            b.tvHud.text = "$head\n$body"
        }
    }

    private fun onPoseError(t: Throwable) {
        Log.e(TAG, "识别出错", t)
    }

    // ==================== 交互 ====================

    private fun onActionClick() {
        when(resolveState()) {
            // 永久拒绝：唯一出路是系统设置页
            UiState.PERMANENTLY_DENIED -> cameraChecker.openAppSettings()

            // 还能问：走系统弹框流程
            UiState.REQUESTABLE -> requestPermission()

            // 其余状态按钮本就不可见，防御性忽略
            UiState.READY, UiState.NO_CAMERA -> Unit
        }
    }

    private fun requestPermission() {
        // 必须先标记再 launch：如果用户这次选了「拒绝」，回调时
        // wasAskedBefore() 必须已经是 true，否则会被误判成「首次」，
        // 页面会一直停在「首次」话术上，永远进不了「去系统设置」分支。
        cameraChecker.markAsked()
        permissionLauncher.launch()
    }

    /**
     * 首次进入页面时自动弹一次授权框。
     * 只在「从没问过」时自动弹——已经问过还自动弹就是骚扰。
     */
    private fun maybeAutoRequest() {
        if (autoRequestDone) return
        if (cameraChecker.wasAskedBefore()) return
        if (resolveState() != UiState.REQUESTABLE) return

        autoRequestDone = true
        requestPermission()
    }

    private companion object {
        const val TAG = "MonitorFragment"

        /**
         * 相机分析线程。单线程 + 高优先级：
         * MediaPipe 推理是串行的，多线程没有收益，反而增加锁竞争。
         */
        val CameraExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "pose-analysis").apply { priority = Thread.NORM_PRIORITY + 1 }
        }
    }
}

