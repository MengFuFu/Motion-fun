package com.motionguardian.kid.ui.settings

import androidx.fragment.app.Fragment
import com.motionguardian.kid.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * 设置页（占位）。
 *
 * T13 会加：灵敏度（低/中/高）、提醒开关、震动开关、前后摄切换。
 * 设置项一律走 DataStore，不用 SharedPreferences。
 */
@AndroidEntryPoint
class SettingsFragment : Fragment(R.layout.fragment_settings)