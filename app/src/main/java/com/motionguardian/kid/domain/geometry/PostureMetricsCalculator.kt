package com.motionguardian.kid.domain.geometry

import com.motionguardian.kid.core.pose.PoseFrame
import com.motionguardian.kid.core.pose.PoseLandmark
import com.motionguardian.kid.domain.filter.LandmarkGate
import com.motionguardian.kid.domain.model.PostureMetrics
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 把 33 个关键点算成 5 个标量指标。
 *
 * 这个类是纯函数 + 无状态 + 零 Android 依赖，
 * 目的就是能在 JVM 上直接跑单元测试（见同目录测试文件）。
 * 别在这里 import 任何 android.*，否则测试会变成 instrumented test，
 * 跑一次要装 APK、要真机，代价高一个数量级。
 */
object PostureMetricsCalculator {

    /**
     * 肩宽下限（单位：画面高度）。
     * 低于这个值说明人离镜头太远，几个像素的抖动就会让角度指标剧烈跳变，
     * 这种帧算出来的数字没有意义，直接放弃。
     * 0.05 约等于"肩宽不到画面高度的 5%"。
     */
    private const val MIN_SHOULDER_WIDTH = 0.05f

    /**
     * 计算一帧指标。
     *
     * @return 计算成功返回指标；关键点不全/置信度不足/人太远 返回 null。
     *         调用方拿到 null 时应该累加 [com.motionguardian.kid.domain.filter.GateCounter]。
     */
    fun calculate(frame: PoseFrame): PostureMetrics? {
        val points = frame.points ?: return null

        // ---- 1. 门控：所有必需点都要够可信 ----
        if (!LandmarkGate.passes(points, PoseLandmark.KEY_POINTS)) return null

        // ---- 2. 坐标各向同性化 ----
        // 归一化 x 的分母是画面宽、y 的分母是画面高，两把尺子不一样长。
        // 把 x 乘上宽高比之后，x 和 y 都以"一个画面高度"为单位，距离才有意义。
        if (frame.imageHeight <= 0) return null
        val aspect = frame.imageWidth.toFloat() / frame.imageHeight.toFloat()

        fun px(i: Int): Float = points[i].x * aspect
        fun py(i: Int): Float = points[i].y

        // ---- 3. 取四个中点 ----
        val shoulderMidX = (px(PoseLandmark.LEFT_SHOULDER) + px(PoseLandmark.RIGHT_SHOULDER)) / 2f
        val shoulderMidY = (py(PoseLandmark.LEFT_SHOULDER) + py(PoseLandmark.RIGHT_SHOULDER)) / 2f
        val hipMidX = (px(PoseLandmark.LEFT_HIP) + px(PoseLandmark.RIGHT_HIP)) / 2f
        val hipMidY = (py(PoseLandmark.LEFT_HIP) + py(PoseLandmark.RIGHT_HIP)) / 2f
        val earMidY = (py(PoseLandmark.LEFT_EAR) + py(PoseLandmark.RIGHT_EAR)) / 2f

        // ---- 4. 肩宽：比例指标的分母 ----
        val shoulderDx = px(PoseLandmark.RIGHT_SHOULDER) - px(PoseLandmark.LEFT_SHOULDER)
        val shoulderDy = py(PoseLandmark.RIGHT_SHOULDER) - py(PoseLandmark.LEFT_SHOULDER)
        val shoulderWidth = hypot(shoulderDx, shoulderDy)

        // 人太远 / 关键点挤在一起：分母太小，比值会爆炸
        if (shoulderWidth < MIN_SHOULDER_WIDTH) return null

        // ---- 5. 主指标：头下垂比 ----
        // 图像坐标 y 向下增大：耳中点 y 越小 = 越靠上。
        // 抬头时 肩y−耳y 变大；低头时耳朵下移、差值变小 → 比值下降。
        // 这是正面视角下唯一稳健的"低头"信号。
        val headDropRatio = (shoulderMidY - earMidY) / shoulderWidth

        // ---- 6. 三个角度 ----
        val shoulderTiltDeg = tiltFromHorizontal(shoulderDx, shoulderDy)

        val headTiltDeg = tiltFromHorizontal(
            px(PoseLandmark.RIGHT_EAR) - px(PoseLandmark.LEFT_EAR),
            py(PoseLandmark.RIGHT_EAR) - py(PoseLandmark.LEFT_EAR),
        )

        // 躯干：肩中点 → 髋中点。图像里髋在肩下方，dy 为正。
        // 与竖直方向（向下，即 +y）的夹角。
        val trunkTiltDeg = tiltFromVertical(
            hipMidX - shoulderMidX,
            hipMidY - shoulderMidY,
        )

        // ---- 7. 参考指标：颈前伸 ----
        val neckForwardRatio = neckForwardRatio(frame)

        return PostureMetrics(
            headDropRatio = headDropRatio,
            shoulderTiltDeg = shoulderTiltDeg,
            headTiltDeg = headTiltDeg,
            trunkTiltDeg = trunkTiltDeg,
            neckForwardRatio = neckForwardRatio,
            shoulderWidth = shoulderWidth,
            timestampMs = frame.timestampMs,
        )
    }

    /**
     * 颈前伸：用世界坐标（米制 3D）算，正数表示头在肩平面之前。
     *
     * 为什么用 worldLandmarks 而不是归一化坐标的 z：
     * 归一化 z 的尺度随画面变化、且和 x/y 不同源，算出来的东西没有物理意义。
     * 世界坐标是 MediaPipe 反投影出来的 3D 骨架，髋部中心为原点、单位约等于米，
     * 这才是"头往前伸了多少"能用的量。
     *
     * **但要知道它的局限**：正面单目摄像头估深度本来就不可靠，
     * 环境光、衣服纹理都会影响。所以这个值只作为参考指标，
     * 判定时不能让它单独触发提醒（T09 会给它低权重）。
     *
     * 拿不到世界坐标时返回 0（中性值 = 不参与判定），而不是返回 null
     * 让整帧作废——这一个指标不可用不该拖累另外四个。
     */
    private fun neckForwardRatio(frame: PoseFrame): Float {
        val world = frame.worldPoints ?: return 0f
        if (!LandmarkGate.passes(world, PoseLandmark.KEY_POINTS)) return 0f

        fun w(i: Int) = world[i]

        // 3D 肩宽（真实空间里的长度）
        val dx = w(PoseLandmark.RIGHT_SHOULDER).x - w(PoseLandmark.LEFT_SHOULDER).x
        val dy = w(PoseLandmark.RIGHT_SHOULDER).y - w(PoseLandmark.LEFT_SHOULDER).y
        val dz = w(PoseLandmark.RIGHT_SHOULDER).z - w(PoseLandmark.LEFT_SHOULDER).z
        val shoulderWidth3D = sqrt(dx * dx + dy * dy + dz * dz)

        // 分母过小说明世界坐标不可信（退化情形），直接给中性值
        if (shoulderWidth3D < 1e-3f) return 0f

        val earMidZ = (w(PoseLandmark.LEFT_EAR).z + w(PoseLandmark.RIGHT_EAR).z) / 2f
        val shoulderMidZ = (w(PoseLandmark.LEFT_SHOULDER).z + w(PoseLandmark.RIGHT_SHOULDER).z) / 2f

        return (earMidZ - shoulderMidZ) / shoulderWidth3D
    }

    /**
     * 一条线段相对**水平线**的倾角，返回 0~90 度。
     *
     * 为什么要折叠到 0~90：左右肩谁高谁低取决于人朝哪边歪、以及前摄镜像，
     * 但"歪了多少度"是一样的。后面判定只看幅度，折叠掉方向能省一层判断。
     *
     * 例：dx=0.2, dy=0.02 → atan2(0.02, 0.2) ≈ 5.7°
     */
    private fun tiltFromHorizontal(dx: Float, dy: Float): Float {
        if (dx == 0f && dy == 0f) return 0f
        val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val abs = abs(deg)
        // atan2 在 dx<0 时给出 90~180，镜像成了 180-a，折叠回来
        return if (abs > 90f) 180f - abs else abs
    }

    /**
     * 一条线段相对**竖直线（向下为正）**的倾角，返回 0~180 度。
     *
     * 用 atan2(dx, dy) 而不是 atan2(dy, dx)：后者是相对水平线，
     * 躯干本来是竖的，相对水平线算出来的会是 80 多度，不直观。
     *
     * 例：肩中点(0.5, 0.6)、髋中点(0.55, 0.85) → atan2(0.05, 0.25) ≈ 11.3°
     */
    private fun tiltFromVertical(dx: Float, dy: Float): Float {
        if (dx == 0f && dy == 0f) return 0f
        val deg = Math.toDegrees(atan2(dx.toDouble(), dy.toDouble())).toFloat()
        return abs(deg)
    }
}