package com.puretv.player.core.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import com.puretv.player.core.util.DeviceUtils
import androidx.compose.ui.platform.LocalContext

/**
 * 当前是否竖屏（手机形态界面自适应判断，追加需求：手机端竖屏界面）。
 * TV 恒为横屏，返回 false 走 TV 布局。
 */
@Composable
fun isPortrait(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT

/** 是否 TV 设备（TV 走遥控器 D-pad 布局，手机走触屏布局） */
@Composable
fun isTvDevice(): Boolean = DeviceUtils.isTvDevice(LocalContext.current)
