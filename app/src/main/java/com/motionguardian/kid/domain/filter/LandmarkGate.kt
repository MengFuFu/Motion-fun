package com.motionguardian.kid.domain.filter

import com.motionguardian.kid.core.pose.PoseLandmark
import com.motionguardian.kid.core.pose.PosePoint

/**
 * 关键点门控：判断一组关键点是否"够可信到能拿来算指标"。
 *
 * 设计原则：**宁可丢帧，不可算错**。
 * 一个 0.3 置信度的肩点，位置可能是偏出去半个身位的，
 * 拿它算出来的肩宽会污染整帧所有比例指标，而且这种错误
 * 在下游的滤波里是洗不掉的——垃圾进了滑动窗口就再也出不来。
 *
 * 阈值定在 0.5 的依据：MediaPipe 官方示例用 0.5 作为"可见"分界，
 * 我们略严格一点（要求整组点都过关），因为体态判定比画骨架更怕错。
 */
object LandmarkGate {

    /** 单点可信度下限。低于这个值视为"这个点不可用"。 */
    const val MIN_CONFIDENCE = 0.5f

    /** 单点是否可用。 */
    fun isPointUsable(
        point: PosePoint,
        threshold: Float = MIN_CONFIDENCE,
    ): Boolean = point.confidence >= threshold

    /**
     * 整组关键点是否全部可用。
     *
     * @param points  33 个点（或世界坐标的 33 个点）
     * @param indices 本帧计算需要用到的下标，见 [PoseLandmark.KEY_POINTS]
     * @return true 表示这一帧可以进入几何计算
     */
    fun passes(
        points: List<PosePoint>,
        indices: IntArray = PoseLandmark.KEY_POINTS,
        threshold: Float = MIN_CONFIDENCE,
    ): Boolean {
        // 长度不足直接判死：下标访问会越界，不能靠 try-catch 兜
        if (points.size < PoseLandmark.COUNT) return false
        for (index in indices) {
            if (index !in points.indices) return false
            if (!isPointUsable(points[index], threshold)) return false
        }
        return true
    }

    /**
     * 这组点里最差的可信度。用于调试与 HUD：
     * 看到 0.47 就知道"差一点点就能过"，而不是干瞪眼不知道卡在哪。
     */
    fun worstConfidence(
        points: List<PosePoint>,
        indices: IntArray = PoseLandmark.KEY_POINTS,
    ): Float {
        if (points.size < PoseLandmark.COUNT) return 0f
        var worst = Float.MAX_VALUE
        for (index in indices) {
            if (index !in points.indices) return 0f
            val c = points[index].confidence
            if (c < worst) worst = c
        }
        return if (worst == Float.MAX_VALUE) 0f else worst
    }
}

/**
 * 连续无效帧计数器。
 *
 * 为什么需要它：单帧丢一次不该惊动用户（眨眼、抬手挠头都会丢帧），
 * 但连续丢 3 帧（约 0.2~0.3 秒）就说明真的有问题了——
 * 人走开了、灯关了、或者坐太远。这时候才提示"坐正对镜头"。
 *
 * 做成独立类是为了能直接单测，不用去跑界面。
 *
 * @param warnThreshold 连续多少次无效后开始提示
 */
class GateCounter(private val warnThreshold: Int = 3) {

    /** 当前连续无效帧数。 */
    var consecutiveMisses: Int = 0
        private set

    /** 这一帧无效。返回 true 表示"已经该提示用户了"。 */
    fun miss(): Boolean {
        consecutiveMisses++
        return consecutiveMisses >= warnThreshold
    }

    /** 这一帧有效，计数清零。 */
    fun reset() {
        consecutiveMisses = 0
    }

    /** 是否已经连续无效到该提示的程度。 */
    val shouldWarn: Boolean
        get() = consecutiveMisses >= warnThreshold
}