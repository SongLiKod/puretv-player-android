package com.puretv.player.core.navigation

/** 全局路由表（Jetpack Navigation 页面路由管理） */
object Routes {
    const val GUIDE = "guide"                       // 新手引导
    const val HOME = "home"                         // 首页（模块化）
    const val LIVE = "live"                         // 直播专区
    const val VOD = "vod"                           // 影视点播（海报墙）
    const val LOCAL = "local"                       // 本地影音
    const val FAVORITES = "favorites"               // 我的收藏
    const val HISTORY = "history"                   // 播放记录
    const val SETTINGS = "settings"                 // 设置中心
    const val SETTINGS_SECTION = "settings/{section}" // 设置中心分区直达
    const val PLUGINS = "plugins"                   // 插件市场
    const val TUTORIAL = "tutorial"                 // 使用教程 + 常见问题
    const val PLAYBACK = "playback"                 // 播放页
    const val SOURCE_CHECK = "sourceCheck"          // 源检测工具
    const val ADD_SOURCE = "addSource"              // 在线搜索添加线路
    const val VOD_DETAIL = "vodDetail/{sourceId}/{itemId}" // 影视详情

    fun vodDetail(sourceId: String, itemId: String): String =
        "vodDetail/$sourceId/${java.net.URLEncoder.encode(itemId, "UTF-8")}"

    fun settings(section: String? = null): String =
        if (section.isNullOrBlank()) SETTINGS else "settings/$section"
}

/** 设置中心分区（首页模块与各页面可直达） */
object SettingsSections {
    const val APPEARANCE = "appearance"   // 主题、皮肤、字体、焦点、布局
    const val PLAYBACK = "playback"       // 解码、缓冲、清晰度参数
    const val SOURCES = "sources"         // 源管理、导入导出备份
    const val ACCOUNT = "account"         // TMDB Key、Debrid、云同步、增值权益
    const val ABOUT = "about"             // 教程、FAQ、源检测、关于版本
}
