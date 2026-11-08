package com.motionguardian.kid.ui.monitor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.motionguardian.kid.databinding.FragmentMonitorBinding
import dagger.hilt.android.AndroidEntryPoint

/**
 * 监测页。
 *
 * T02：只是一个占位页面，证明导航能跑到这里。
 * T03：加相机权限的三条分支（未申请 / 已授予 / 永久拒绝）。
 * T04：加 CameraX 预览。
 * T11：改沉浸式（隐藏底部导航、去掉 insets padding）。
 *
 * @AndroidEntryPoint 让 Hilt 重写本 Fragment 的 getDefaultViewModelProviderFactory()，
 * 之后 `by viewModels<MonitorViewModel>()` 才能拿到 @HiltViewModel 标注的实例。
 * 注意：Fragment 里用 by viewModels() 不需要额外的 hilt-navigation-fragment 依赖，
 * 那个依赖只有 by navGraphViewModels() 才需要。
 */
@AndroidEntryPoint
class MonitorFragment : Fragment() {

    // 双变量模式：_binding 可空，binding 非空快捷方式（只在 View 存活期间有效）
    private var _binding: FragmentMonitorBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        // 第三个参数 false 很重要：让 inflate 不要自动 attach 到 container，
        // 由 FragmentManager 负责 attach，否则会抛 IllegalStateException
        _binding = FragmentMonitorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // T03 会在这里加权限检查
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 必须置 null：Fragment 进返回栈时 View 已销毁，留着引用就是内存泄漏
        _binding = null
    }
}