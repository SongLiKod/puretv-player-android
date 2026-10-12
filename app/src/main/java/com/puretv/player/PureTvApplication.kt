package com.puretv.player

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * 应用入口（Dagger Hilt 全局依赖注入）。
 * 仅做轻量初始化，保证启动速度 ≤1 秒、后台重启无加载延迟。
 */
@HiltAndroidApp
class PureTvApplication : Application()
