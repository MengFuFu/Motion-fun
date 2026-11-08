package com.motionguardian.kid.ui.monitor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.motionguardian.kid.R
import com.motionguardian.kid.core.camera.CameraChecker
import com.motionguardian.kid.core.camera.CameraPermissionLauncher
import com.motionguardian.kid.databinding.FragmentMonitorBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 监测页。
 *
 * 本任务点只做「权限 + 状态展示」，不接相机预览——那是 T04。
 * 页面行为是一个显式状态机：每次渲染都重新算一遍真实状态再画，
 * 而不是靠一堆 boolean 记「我现在是不是在授权中」。
 * 状态少的时候，「每次都重新算」永远比「增量更新」更不容易出 bug。
 *
 * @AndroidEntryPoint 让 Hilt 注入生效：它会把 Fragment 的父类从 Fragment
 * 换成 Hilt_MonitorFragment，后者在 onCreate 里完成字段注入。
 * 所以 @Inject 字段在 onCreate 之后才可用，不能用它的值去初始化同名字段的默认值。
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
        when(val state = resolveState()) {
            UiState.NO_CAMERA -> bind(
                titleRes = R.string.camera_no_hardware_title,
                descRes = R.string.camera_no_hardware_desc,
                actionTextRes = null,
                iconColorRes = R.color.text_secondary,
            )

            UiState.READY -> bind(
                titleRes = R.string.camera_ready_title,
                descRes = R.string.camera_ready_desc,
                actionTextRes = null,
                iconColorRes = R.color.semantic_good,
            )

            UiState.REQUESTABLE -> bind(
                titleRes = R.string.camera_need_title,
                // 首次和被拒后的话术不同：首次要讲清「数据不出本机」，
                // 被拒后不重复大道理、只说怎么补救。
                descRes = if (cameraChecker.wasAskedBefore()) {
                    R.string.camera_need_desc_again
                } else {
                    R.string.camera_need_desc_first
                },
                actionTextRes = R.string.camera_grant,
                iconColorRes = R.color.brand_primary,
            )

            UiState.PERMANENTLY_DENIED -> bind(
                titleRes = R.string.camera_denied_title,
                descRes = R.string.camera_denied_desc,
                actionTextRes = R.string.camera_open_settings,
                iconColorRes = R.color.semantic_warn,
            )
        }
    }

    /**
     * 把四个控件一次性改到位。
     * 集中成一个函数，是为了避免「某个分支忘了隐藏按钮」这类漏改。
     */
    private fun bind(
        titleRes: Int,
        descRes: Int,
        actionTextRes: Int?,
        @ColorRes iconColorRes: Int,
    ) {
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