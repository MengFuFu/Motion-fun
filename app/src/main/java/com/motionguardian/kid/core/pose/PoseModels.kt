package com.motionguardian.kid.core.pose

//单个身体关键点
data class PosePoint(
    /**
     * x、y：归一化坐标，范围 0~1，不是像素
     * 图片左上角是 x=0,y=0；右下角 x=1,y=1。
     */
    val x: Float,
    val y: Float,
    val z: Float,//z：深度信息（离镜头远近）
    val visibility: Float,//这个点能不能看见。比如手挡住肩膀，肩膀 visibility 就变低。范围 0~1，越接近 1 越清晰。
    val presence: Float//这个身体部位在不在画面里
) {
    /** 综合可信度。低于 0.5 的点一律当噪声丢弃（T06 的门控会用到）。 */
    val confidence: Float get() = minOf(visibility, presence)
}

//一整张照片（一帧）全部识别结果
data class PoseFrame(
    val timestampMs: Long,//这一帧的时间戳（毫秒）用来判断一段时间内的体态
    val inferenceMs: Long,//模型识别这张图花了多少毫秒
    val imageWidth: Int,
    val imageHeight: Int,
    val points: List<PosePoint>?,//33 个 PosePoint 的列表，也就是人体 33 个关键点
    val worldPoints: List<PosePoint>?//3D 世界坐标，单位米，以屁股中间作为原点。用来算真实空间姿态，
)

//性能统计数据
data class PoseStats(
    val fps: Float,//每秒成功识别多少帧
    val lastInferenceMs: Long,//上一帧识别耗时
    val totalFrames: Long,//一共处理了多少帧画面
    val hasPose: Boolean//这一帧画面里有没有检测到人
)

//人体 33 个关键点编号
object PoseLandmark {
    const val NOSE = 0
    const val LEFT_EAR = 7
    const val RIGHT_EAR = 8
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24

    const val COUNT = 33

    /** T06 起会频繁取用的一组点，抽出来免得每次手写下标。 */
    val KEY_POINTS = intArrayOf(
        NOSE,
        LEFT_EAR,
        RIGHT_EAR,
        LEFT_SHOULDER,
        RIGHT_SHOULDER,
        LEFT_HIP,
        RIGHT_HIP
    )

    fun label(index: Int): String = when(index) {
        NOSE -> "鼻"
        LEFT_EAR -> "左耳"
        RIGHT_EAR -> "右耳"
        LEFT_SHOULDER -> "左肩"
        RIGHT_SHOULDER -> "右肩"
        LEFT_HIP -> "左髋"
        RIGHT_HIP -> "右髋"
        else -> "#$index"
    }
}