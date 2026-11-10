package com.motionguardian.kid.domain.model

/**
 * 一帧算出来的五项体态指标。
 *
 * 全部是「相对图像自身的无量纲量」，不依赖分辨率、不依赖设备：
 *  - 角度类：单位是度，正的表示一侧高/一侧低（取绝对值后不区分方向）
 *  - 比值类：没有单位，可以跨设备比较
 *
 * 为什么不做成 null 字段而是整帧 optional（计算失败返回 null）：
 * 缺一个指标就没法做多指标联合判定，半残的指标对象比没有更危险。
 *
 * @param headDropRatio    头下垂比 = (肩中点y − 耳中点y) / 肩宽。**主指标**。
 *                         正常坐姿约 0.35~0.55（取决于距离和身材），
 *                         低头时显著下降。T08 会用"相对基线下降百分比"判定。
 * @param shoulderTiltDeg  肩线倾角（度）= 左肩→右肩连线与水平线的夹角，0~90。
 *                         高低肩/歪坐时升高。正常人也有 1~3° 的自然不对称。
 * @param headTiltDeg      头侧倾角（度）= 左耳→右耳连线与水平线夹角。
 *                         歪头时升高，比肩线更灵敏。
 * @param trunkTiltDeg     躯干倾角（度）= 肩中点→髋中点连线与竖直线的夹角。
 *                         整个人往一侧倒时升高。趴在桌上时髋部可能出画，
 *                         所以这个指标的可信度天然比肩线低。
 * @param neckForwardRatio 颈前伸比（参考值）= 世界坐标下(耳中点z − 肩中点z) / 肩宽。
 *                         正值表示头在肩前面。**注意：正面摄像头下 z 精度很差，
 *                         只作参考，T09 判定时权重减半甚至不用。**
 * @param shoulderWidth    当帧肩宽（单位：画面高度）。用于判断"人太远/太近"，
 *                         也方便调试时确认比例是否合理。
 * @param timestampMs      采集时刻，T07/T08 的时间窗要用。
 */
data class PostureMetrics(
    val headDropRatio: Float, // 头下垂比值，判断低头的核心指标
    val shoulderTiltDeg: Float, // 肩膀倾斜角度，判断高低肩
    val headTiltDeg: Float, // 头部左右倾斜角度，判断歪头
    val trunkTiltDeg: Float, // 躯干倾斜角度，判断身体侧倾
    val neckForwardRatio: Float, // 头前伸比值，参考指标，z轴不准，权重低
    val shoulderWidth: Float, // 当前帧肩宽，用来判断用户离镜头远近
    val timestampMs: Long, // 当前帧时间戳，单位毫秒，用于滑动窗口，做一段时间内的体态统计
)

/**
 * 一帧的处理状态。UI 需要知道"为什么没数据"，才能给对提示：
 * 是"画面里没人"，还是"有人但看不清"。
 */
enum class GateStatus {
    /** 指标可用。 */
    OK,

    /** 画面里没检测到人（MediaPipe 就没返回关键点）。 */
    NO_POSE,

    /** 检测到人，但关键点置信度不够（遮挡/背光/太远）。 */
    LOW_CONFIDENCE,
}