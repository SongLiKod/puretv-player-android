package com.puretv.player.core.util

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * 设备形态判断与方向策略（手机/TV 双形态）：
 * - Android TV / 盒子：锁定横屏（TV 交互规范，遥控器 D-pad 导航）；
 * - 手机/平板：跟随传感器，支持竖屏手机界面（用户追加需求）。
 */
object DeviceUtils {

    /** 是否 Android TV / leanback 设备（电视、盒子） */
    fun isTvDevice(context: Context): Boolean {
        val uiType = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        return uiType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    /**
     * 按设备形态设置初始方向：
     * TV 锁定横屏；手机/平板不锁定（UNSPECIFIED，跟随系统自动旋转开关，默认竖屏手机界面）。
     */
    fun applyDeviceOrientation(activity: Activity) {
        activity.requestedOrientation = if (isTvDevice(activity)) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    /** 播放视频期间锁定横屏（手机/平板；TV 本身即横屏不受影响） */
    fun lockLandscape(activity: Activity) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    /** 锁定竖屏（手机端播放页默认方向：视频在上、控制在下，点全屏才转横屏） */
    fun lockPortrait(activity: Activity) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}
