package com.motionguardian.kid.core.pose

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicLong

/**
 * 接收 CameraX 相机传来的每一帧图像，转成 MediaPipe 能识别的图片，
 * 调用 PoseLandmarkerHelper 做姿态识别，把识别结果打包回调出去。
 */
class PoseFrameAnalyzer(
    private val onFrame: (PoseFrame) -> Unit, //画面检测到人，把姿态数据`PoseFrame`传给 Fragment 页面
    private val onStats: (PoseStats) -> Unit, //每一帧都会触发，不管有没有人，用来上报 FPS、推理耗时，调试面板用
    private val onError: (Throwable) -> Unit,
) : ImageAnalysis.Analyzer {

    /**
     * 由外部在模型加载完成后注入。
     * 用 @Volatile 是因为写发生在主线程、读发生在分析线程，
     * 不加的话分析线程可能永远读到 null。
     */
    @Volatile
    var engine: PoseLandmarkerHelper? = null

    //统计一共处理了多少帧图像。
    // AtomicLong 保证多线程计数不会错乱。
    private val frameCounter = AtomicLong(0)

    // ---- FPS 计算（1 秒滑动窗口） ----
    private var windowStartMs = 0L //当前 1 秒统计窗口的起始时间
    private var windowCount = 0 //当前窗口已经处理的帧数

    private var lastInferenceMs = 0L //上一张图片模型识别消耗的毫秒
    private var lastHasPose = false //标记这一帧有没有检测到人

    override fun analyze(image: ImageProxy) {
        // try/finally 保证 image 一定被 close：
        // 漏 close 一次，KEEP_ONLY_LATEST 就不再投递新帧，画面直接冻住。
        try {
            val helper = engine ?: return //模型还没准备好就直接 return，跳过当前帧。

            val bitmap = image.toRgbaBitmap()
            if (bitmap == null) {
                // 转换失败（格式不支持等），跳过这一帧但不影响后续
                return
            }

            val costMs: Long //保存本次推理耗时的变量
            val result = try {
                val begin = SystemClock.elapsedRealtime()
                val r = helper.detect(bitmap)
                costMs = SystemClock.elapsedRealtime() - begin
                r //// 返回识别结果
            } catch (t: Throwable) {
                Log.e(TAG, "推理失败", t)
                onError(t)
                return
            } finally {
                // 同步推理已结束，可以立刻回收。每帧新建再回收，
                // 虽然会产生 GC 压力，但比复用 Bitmap 更不容易出并发 bug（T14 再优化）。
                bitmap.recycle()
            }

            lastInferenceMs = costMs //// 保存本次推理耗时
            val timestampMs = SystemClock.uptimeMillis() // 获取当前时间戳，标记帧采集时间
            val total = frameCounter.incrementAndGet() // 总帧数+1，拿到新的总数量

            // 取出2D关键点，转为PosePoint
            val points = result?.landmarks()?.firstOrNull()?.map { it.toPosePoint() }
            // 取出3D世界坐标关键点，转为PosePoint
            val worldPoints = result?.worldLandmarks()?.firstOrNull()?.map { it.toPosePoint() }
            lastHasPose = !points.isNullOrEmpty() // points不为空，代表检测到人

            if (lastHasPose) {
                // 触发onFrame回调，传递姿态数据
                onFrame(
                    PoseFrame(
                        timestampMs = timestampMs,
                        inferenceMs = costMs,
                        imageWidth = bitmapWidth,
                        imageHeight = bitmapHeight,
                        points = points,
                        worldPoints = worldPoints,
                    ),
                )
            }

            onStats(
                PoseStats(
                    fps = updateFps(timestampMs),
                    lastInferenceMs = costMs,
                    totalFrames = total,
                    hasPose = lastHasPose,
                ),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "analyze 异常", t)
            onError(t)
        } finally {
            image.close()
        }
    }

    // 记录当前帧的尺寸，onFrame 里要用（image 已被 close，不能再读）
    private var bitmapWidth = 0
    private var bitmapHeight = 0

    /**
     * 把 ImageProxy 转成旋转校正后的 Bitmap。
     *
     * 返回 null 表示这一帧不可用（例如 buffer 尺寸和宽高对不上）。
     * 宁可丢一帧，也不要拿错乱的数据去推理。
     */
    private fun ImageProxy.toRgbaBitmap(): Bitmap? {
        val plane = planes.getOrNull(0) ?: return null
        val buffer = plane.buffer
        buffer.rewind()

        val w = width
        val h = height
        val expected = w * h * 4
        if (buffer.remaining() < expected) {
            // 理论上 RGBA_8888 输出格式不会有 rowStride 填充，
            // 但个别机型/驱动会有。宁可丢帧也不读越界。
            Log.w(TAG, "RGBA buffer 尺寸异常: remain=${buffer.remaining()} expected=$expected")
            return null
        }

        val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buffer)

        // 相机传感器方向固定，rotationDegrees 告诉你"要转多少度才是正的"
        val rotation = imageInfo.rotationDegrees
        val rotated = if (rotation == 0) {
            raw
        } else {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, w, h, matrix, true).also {
                if (it !== raw) raw.recycle()
            }
        }

        bitmapWidth = rotated.width
        bitmapHeight = rotated.height
        return rotated
    }

    /** 1 秒窗口算 fps：窗口内帧数 / 经过秒数。 */
    private fun updateFps(nowMs: Long): Float {
        if (windowStartMs == 0L) {
            windowStartMs = nowMs
            windowCount = 1
            return 0f
        }
        windowCount++
        val elapsed = nowMs - windowStartMs
        if (elapsed < FPS_WINDOW_MS) {
            return if (elapsed <= 0) 0f else windowCount * 1000f / elapsed
        }
        val fps = windowCount * 1000f / elapsed
        windowStartMs = nowMs
        windowCount = 0
        return fps
    }

    private companion object {
        const val TAG = "PoseFrameAnalyzer"
        const val FPS_WINDOW_MS = 1000L
    }
}

/** NormalizedLandmark → PosePoint */
private fun com.google.mediapipe.tasks.components.containers.NormalizedLandmark.toPosePoint() =
    PosePoint(
        x = x(),
        y = y(),
        z = z(),
        visibility = visibility().orElse(0f),
        presence = presence().orElse(0f),
    )

/** Landmark（世界坐标）→ PosePoint */
private fun com.google.mediapipe.tasks.components.containers.Landmark.toPosePoint() =
    PosePoint(
        x = x(),
        y = y(),
        z = z(),
        visibility = visibility().orElse(0f),
        presence = presence().orElse(0f),
    )