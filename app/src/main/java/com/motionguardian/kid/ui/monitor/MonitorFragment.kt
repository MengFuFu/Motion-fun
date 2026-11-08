package com.motionguardian.kid.ui.monitor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorRes
import androidx.camera.core.CameraSelector
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
import kotlinx.coroutines.launch

/**
 * 监测页。
 *
 * 本任务点做到「权限 → 相机预览」，还不做姿态识别（T05）。
 *
 * 页面结构是三层：预览在最底、状态面板盖在上面、就绪胶囊浮在右上角。
 * 状态机仍然是「每次渲染都重新算真实状态」，不缓存 boolean——
 * 权限和相机都是外部可变状态，缓存只会制造不一致。
 *
 * 生命周期要点（这步最容易错的就这三条）：
 *  1. 相机绑 viewLifecycleOwner，不绑 Fragment 自己、更不绑 Activity；
 *  2. 启动放在 onViewCreated/onResume 之后的协程里，不要在 onCreateView 里同步做；
 *  3. onDestroyView 必须 stop()，否则 Surface 泄漏、再进来黑屏。
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

    /** 启动预览的协程，用于避免重复启动。 */
    private var startJob: Job? = null

    /** 预览是否已经成功绑上（绑定成功一次就不再重复绑）。 */
    private var previewBound = false

    // 只在「本次进入页面」自动弹一次，避免用户拒绝后又立刻被弹第二次。
    private var autoRequestDone = false

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

        // 关键：注册必须发生在 STARTED 之前，onCreate 是最晚的安全点。
        permissionLauncher = CameraPermissionLauncher(this) {
            granted ->
            autoRequestDone = true
            // 不管授权与否都重画：拒绝后文案要从「首次」换成「再授权一次」，
            // 永久拒绝后按钮要从「授予权限」换成「去系统设置」。
            render()
            if(granted) {
                // T04 会在这里启动相机预览
            }
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

        // 顺序很重要：先取消协程，再解绑相机，最后丢引用。
        // 反过来的话，协程可能在解绑之后又把相机绑上，最后一帧泄漏。
        startJob?.cancel()
        startJob = null
        previewController?.stop()
        previewController = null
        previewBound = false
        // 必须置 null：Fragment 进返回栈时 View 已销毁，留着引用就是内存泄漏
        _binding = null
    }

    // ==================== 状态计算 ====================

    /**
     * 每次调用都向系统重新问一遍，不缓存。
     * 权限是外部可变状态（用户随时能在设置里改），缓存只会带来「显示与实际不符」。
     */
    private fun resolveState(): UiState {
        if(!cameraChecker.hasCameraHardware()) return UiState.NO_CAMERA
        if(cameraChecker.isPermissionGranted()) return UiState.READY

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
                startPreview()
            }

            UiState.NO_CAMERA -> {
                showPanel(
                    titleRes = R.string.camera_no_hardware_title,
                    descRes = R.string.camera_no_hardware_desc,
                    actionTextRes = null,
                    iconColorRes = R.color.text_secondary,
                )
            }

            UiState.REQUESTABLE -> {
                showPanel(
                    titleRes = R.string.camera_need_title,
                    descRes = if (cameraChecker.wasAskedBefore()) {
                        R.string.camera_need_desc_again
                    } else {
                        R.string.camera_need_desc_first
                    },
                    actionTextRes = R.string.camera_grant,
                    iconColorRes = R.color.brand_primary,
                )
            }

            UiState.PERMANENTLY_DENIED -> {
                showPanel(
                    titleRes = R.string.camera_denied_title,
                    descRes = R.string.camera_denied_desc,
                    actionTextRes = R.string.camera_open_settings,
                    iconColorRes = R.color.semantic_warn,
                )
            }
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
        val owner = viewLifecycleOwner

        startJob = viewLifecycleOwner.lifecycleScope.launch {
            when(val result = controller.start(binding.previewView, owner)) {
                is CameraStartResult.Success -> {
                    previewBound = true
                    showBadge(result.lensFacing)
                }

                CameraStartResult.NoCamera -> {
                    // 理论上 hasCameraHardware 已经挡过一次，这里是双保险
                    showPanel(
                        titleRes = R.string.camera_no_hardware_title,
                        descRes = R.string.camera_no_hardware_desc,
                        actionTextRes = null,
                        iconColorRes = R.color.text_secondary,
                    )
                }

                is CameraStartResult.Failed -> {
                    showPanel(
                        titleRes = R.string.camera_error_title,
                        descRes = R.string.camera_error_desc,
                        actionTextRes = null,
                        iconColorRes = R.color.semantic_alert,
                    )
                }
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
}

