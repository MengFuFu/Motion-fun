package com.motionguardian.kid

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * 应用入口。
 *
 * 现在它是空的，但必须先存在：
 * Hilt 需要一个挂载点来创建全局唯一的 SingletonComponent，
 * 之后所有 @Inject 出来的单例（相机提供者、姿态检测器、数据库）都挂在这里。
 *
 * TODO(T05)：在这里预热的 MediaPipe 模型加载会挪到后台线程，
 *            避免首次进入监测页时那一两百毫秒的主线程阻塞。
 */
@HiltAndroidApp
class MotionGuardianApp : Application()