package com.motionguardian.kid.core.pose

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.io.Closeable

/**
 * 用于 MediaPipe 姿态模型的创建
 *              、图片识别
 *              、资源释放。
 *
 * MediaPipe 底层是 C++ 写的，会占用手机内存，必须手动关闭，不关闭就内存泄漏。
 * 这个类就是把这套麻烦的底层操作打包起来，上层 Fragment、PoseFrameAnalyzer 直接调用就行，
 * 不用管底层细节。
 */

//实现 Closeable 接口，必须重写close()方法，用来释放 native 资源
class PoseLandmarkerHelper(context: Context) : Closeable {

    //createFromOptions()：创建检测器实例
    private val landmarker: PoseLandmarker = PoseLandmarker.createFromOptions(
        context,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    // 路径相对 assets/，不要写成 "/assets/xxx" 或绝对路径
                    .setModelAssetPath(MODEL_ASSET)
                    .build(),
            )
            // IMAGE 模式：每帧独立推理、同步返回，见任务点说明里的取舍
            .setRunningMode(RunningMode.IMAGE)
            // 只检测一个人。孩子端场景永远是单人，多目标只会拖慢速度
            .setNumPoses(1)
            // 三个阈值都用 0.5：低于 0.5 认为"没检测到"。
            // 调高 → 更严格，容易丢检测；调低 → 更容易误检到背景
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .build(),
    )

    /**
     * 同步识别一帧。
     *
     * @return 识别结果；画面里没人时返回 null（不是抛异常）
     * @throws IllegalStateException 模型损坏或已被 close
     */
    fun detect(bitmap: Bitmap): PoseLandmarkerResult? = landmarker.detect(
        BitmapImageBuilder(bitmap).build(),
    )

    /**
     * 关闭 native 资源。
     * 必须调用，否则每次重建页面都会泄漏一份模型（约 20MB 常驻）。
     */
    override fun close() {
        try {
            landmarker.close()
        } catch (t: Throwable) {
            Log.w(TAG, "关闭 PoseLandmarker 失败", t)
        }
    }

    companion object {
        private const val TAG = "PoseLandmarker"

        /** assets 下的模型文件名，必须和实际文件名完全一致（大小写敏感）。 */
        const val MODEL_ASSET = "pose_landmarker_lite.task"
    }
}