package com.motionguardian.kid.ui.stats

import androidx.fragment.app.Fragment
import com.motionguardian.kid.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * 统计页（占位）。
 *
 * 这里用的是 Fragment(layoutId) 简写构造器：不需要操作任何控件，
 * 一行就够。T12 要往布局里塞 RecyclerView / 自定义图表时，
 * 再改成 MonitorFragment 那种 _binding 模式。
 */
@AndroidEntryPoint
class StatsFragment : Fragment(R.layout.fragment_stats)