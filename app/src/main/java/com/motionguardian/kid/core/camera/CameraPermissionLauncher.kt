package com.motionguardian.kid.core.camera

import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment

/**
 * 把「申请相机权限」这件事包成一个可复用对象。
 *
 * 为什么必须包一层：registerForActivityResult 有严格的注册时机要求——
 * 必须在 Fragment 到达 STARTED 之前调用，否则抛
 * IllegalStateException: attempting to register while current state is RESUMED。
 * 所以不能在按钮点击时临时注册，只能在 Fragment 创建期注册一次、之后反复 launch。
 * 把这个约束收进构造函数，调用方就不可能用错。
 *
 * @param fragment 宿主 Fragment。用它注册，权限结果会绑定到 Fragment 的生命周期，
 *                 Fragment 销毁后回调自动失效，不会泄漏、不会回调到已销毁的 UI。
 * @param onResult 用户做出选择后的回调。true=已授权，false=拒绝或被系统直接驳回。
 */
class CameraPermissionLauncher(
    fragment: Fragment,
    private val onResult: (granted: Boolean) -> Unit
) {

    // 构造函数里立即注册。此时 Fragment 还在 INITIALIZED 状态，满足注册时机要求。
    private val launcher = fragment.registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
    ) {
        granted ->
        onResult(granted)
    }

    /** 弹出系统权限框。若已被永久拒绝，系统不会弹框，直接回调 false。 */
    fun launch() {
        launcher.launch(Manifest.permission.CAMERA)
    }
}