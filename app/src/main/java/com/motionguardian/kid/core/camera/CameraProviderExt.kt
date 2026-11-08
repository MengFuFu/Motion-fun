package com.motionguardian.kid.core.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CameraX 的入口是 Java 时代的 ListenableFuture，不能直接 await。
 * 这里用 suspendCancellableCoroutine 把它桥接成挂起函数，
 * 让调用方可以写在协程里，不用回调套回调。
 *
 * 注意 addListener 的第二个参数必须是主线程 executor：
 * ProcessCameraProvider 内部有状态需要在主线程被唤醒，
 * 用 IO 线程会有线程安全问题（官方文档明确要求）。
 */
suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    continuation.resume(future.get())
                } catch (t: Throwable) {
                    // 相机服务不可用、被系统策略禁用等情况都会走到这里
                    continuation.resumeWithException(t)
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

/**
 * 判断某个 CameraSelector 在这台设备上可不可用。
 *
 * 为什么放在 IO 线程：hasCamera 内部要查 CameraCharacteristics，
 * 是个跨进程的 Binder 调用，主线程调可能卡几十毫秒。
 *
 * 为什么整体包 runCatching：hasCamera 在相机服务异常时不会返回 false，
 * 而是抛 CameraInfoUnavailableException。这里统一收敛成 false，
 * 让上层把「查不到」和「没有」当成一回事处理——对用户来说结果一样。
 *
 * 命名用 canUseCamera 而不是 hasCamera，是为了避免和
 * ProcessCameraProvider 自带的成员方法 hasCamera 混淆（虽然成员优先，
 * 但同名的扩展函数读代码时非常容易看错）。
 */
suspend fun ProcessCameraProvider.canUseCamera(selector: CameraSelector): Boolean =
    withContext(Dispatchers.IO) {
        try {
            hasCamera(selector)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            false
        }
    }