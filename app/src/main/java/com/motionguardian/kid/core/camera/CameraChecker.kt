package com.motionguardian.kid.core.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.provider.Telephony
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 相机环境的「只读查询器」。
 *
 * 为什么单独抽一个类，而不是在 Fragment 里直接写：
 *  - Fragment 每旋转一次就重建，权限判断逻辑散在生命周期里极易写漏；
 *  - 后面 T04/T15 都要问「有没有相机」「给没给权限」，抽出来只写一次；
 *  - 纯查询、无状态副作用，方便以后写单测。
 *
 * 由 Hilt 注入，@Singleton 保证全局一份。构造参数 @ApplicationContext 是
 * Hilt 的内置 Qualifier，指向 Application 的 Context——它活得比任何 Activity 都久，
 * 拿它不会有内存泄漏风险。千万别在这里传 Activity 的 Context。
 */
@Singleton
class CameraChecker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * 设备上是否存在任意摄像头（前置或后置都算）。
     *
     * 用 FEATURE_CAMERA_ANY 而不是 FEATURE_CAMERA_FRONT：
     * 很多平板只有后摄，而体态监测其实后摄也能用（只需要画面里有上半身）。
     * 所以只要「有摄像头」就算能跑，具体选哪颗在 T04 里做优先级回落。
     */
    fun hasCameraHardware(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    /** 当前是否已持有 CAMERA 运行时权限。 */
    fun isPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * 是否曾经向用户申请过相机权限。
     *
     * 为什么需要这个：系统 API 无法区分「首次进入」和「已被永久拒绝」——
     * 两种情况都是 granted=false 且 shouldShowRationale=false。
     * 不记这个标记的话，用户第二次进页面会看到「授予权限」按钮，
     * 点了却没有任何反应（系统不再弹框），体验极差。
     *
     * 用 SharedPreferences 而不是 DataStore：这里只存一个布尔值，
     * DataStore 的 Flow/协程开销不值得。等 T09 引入 DataStore 处理设置项时再统一。
     */
    fun wasAskedBefore(): Boolean =
        prefs().getBoolean(KEY_ASKED, false)

    /** 记录「已经问过了」。必须在 launch 之前调用。 */
    fun markAsked() {
        prefs().edit().putBoolean(KEY_ASKED, true).apply()
    }

    /**
     * 跳转到本应用的系统设置页。
     *
     * 用于「永久拒绝」后的唯一出路——系统不会再弹框，只能引导用户手动开。
     * FLAG_ACTIVITY_NEW_TASK 是必须的：这里用的是 Application Context，
     * 没有 Activity 栈可挂，不加这个 flag 会抛 AndroidRuntimeException。
     *
     * 注意 ACTION_APPLICATION_DETAILS_SETTINGS 在各家 ROM 上路径不完全一致，
     * 但这是官方推荐写法，兼容性最好。不要用 ACTION_SETTINGS 直接跳总设置，
     * 用户还得自己找「应用」。
     */
    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun prefs() =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val PREFS_NAME = "camera_permission"
        const val KEY_ASKED = "asked_once"
    }
}