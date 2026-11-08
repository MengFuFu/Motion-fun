package com.motionguardian.kid

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.motionguardian.kid.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint

/**
 * 唯一 Activity（单 Activity 架构）。
 *
 * 职责只有三件：
 * 1. 加载布局
 * 2. 拿到 NavController，交给 BottomNavigationView
 * 3. 处理系统栏 insets（Android 15 强制 edge-to-edge）
 *
 * 页面切换全部由 Navigation Component 在 Activity 内部完成，不启动新 Activity。
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // inflate 会真正解析 XML 并创建所有 View，然后 setContentView 挂到窗口上
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 必须在 setContentView 之后、布局测量之前设置，否则首帧会闪一下
        applySystemBarInsets()

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHost.navController

        binding.bottomNav.setupWithNavController(navController)
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }
}