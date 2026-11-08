package com.motionguardian.kid.core.camera

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellationException

/**
 * 启动相机预览的结果。用 sealed interface 而不是 Boolean：
 * 「失败」有三种完全不同的原因，UI 要给的提示也不同，
 * 用 Boolean 会把它们糊成一个 false，后面根本没法区分。
 */
sealed interface CameraStartResult {

    /** 启动成功。lensFacing 用于在胶囊上显示「前置 / 后置」。 */
    data class Success(val lensFacing: Int?) : CameraStartResult

    /** 设备上没有可用的摄像头。 */
    data object NoCamera : CameraStartResult

    /** 有摄像头但绑定失败（被占用、服务崩溃等）。 */
    data class Failed(val cause: Throwable) : CameraStartResult
}

/**
 * 相机预览的控制器：负责「绑定」和「解绑」这一对动作。
 *
 * 为什么不让 Fragment 直接写 CameraX 代码：
 *  - Bind 是一段有 6~7 步的流程（取 provider → 选镜头 → 建用例 → 解绑旧的 → 绑定），
 *    塞在 Fragment 里会让生命周期方法膨胀到没法读；
 *  - T05 要在这上面再加 ImageAnalysis 用例，T14 要加分辨率切换，
 *    这些都应该发生在这个类里，而不是改 Fragment。
 *
 * 这个类有意做成「无状态」：不持有 LifecycleOwner、不持有 PreviewView，
 * 每次 start 由调用方传进来。这样 Fragment 重建时不会有旧引用残留。
 */
class CameraPreviewController(
    private val context: Context
) {

    private var provider: ProcessCameraProvider? = null
    private var bound: Boolean = false

    /**
     * 启动预览。可重复调用，但重复绑定会先解绑旧的（幂等）。
     *
     * @param previewView 显示画面的容器
     * @param owner       绑定目标，必须传 viewLifecycleOwner
     * @return 结果；失败不会抛异常，由调用方决定怎么提示
     */
    @SuppressLint("RestrictedApi")
    suspend fun start(
        previewView: PreviewView,
        owner: LifecycleOwner
    ): CameraStartResult {
        //1. 拿进程级单例
        val cameraProvider = try {
            context.awaitCameraProvider()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "取 ProcessCameraProvider 失败", t)
            return CameraStartResult.Failed(t)
        }
        provider = cameraProvider

        //2. 挑镜头：前后
        val selector = pickSelector(cameraProvider) ?: return CameraStartResult.NoCamera

        //3. 建预览用例，把画面输出到preview
        val preview = Preview.Builder().build().apply {
            surfaceProvider = previewView.surfaceProvider
        }

        // ---- 4. 先解绑再绑定 ----
        // 不做这一步的话，从设置页返回时（或热重载后）会抛
        // IllegalArgumentException: LifecycleOwner is already bound
        return try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(owner, selector, preview)
            bound = true
            CameraStartResult.Success(selector.lensFacing)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "绑定相机失败", t)
            CameraStartResult.Failed(t)
        }
    }

    /**
     * 解绑所有用例。
     *
     * 必须在 onDestroyView 调用：CameraX 会持有 PreviewView 的 Surface，
     * 不解绑的话视图销毁了相机还在往一个死 Surface 上送帧，
     * 表现为「返回上一页再进来黑屏」。
     *
     * 幂等，可以随便重复调。
     */
    fun stop() {
        if(!bound) return
        try {
            provider?.unbindAll()
        } catch (t: Throwable) {
            // 解绑失败不影响 UI，最多是相机多开一会儿，记日志即可
            Log.w(TAG, "解绑相机失败", t)
        }
        bound = false
    }

    /**
     * 挑一个可用镜头。
     * 体态监测最理想是前置——屏幕和镜头在同一侧，孩子看屏幕时正对镜头。
     * 但很多平板/模拟器只有后置，所以必须回落，否则一装就白屏。
     */
    private suspend fun pickSelector(
        provider: ProcessCameraProvider
    ): CameraSelector? {
        if(provider.canUseCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            return CameraSelector.DEFAULT_FRONT_CAMERA
        }
        if (provider.canUseCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
            return CameraSelector.DEFAULT_BACK_CAMERA
        }
        return null
    }

    private companion object {
        const val TAG = "CameraPreview"
    }
}