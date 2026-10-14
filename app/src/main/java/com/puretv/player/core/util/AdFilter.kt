package com.puretv.player.core.util

/**
 * 广告/推广条目过滤（资源插件「过滤广告」，设置 → 播放）。
 * 资源站（MacCMS 等采集源）常混入博彩、兼职、引流类垃圾条目，按关键词保守匹配，
 * 命中即在首页/影视列表/搜索/榜单中隐藏；详情页直达不受影响，避免误杀正片。
 *
 * 关键词可配置：[DEFAULT_KEYWORDS] 是插件安装后的默认词库，
 * 用户在设置中修改后以逗号串持久化，由 [resolveKeywords] 解析。
 */
object AdFilter {

    /**
     * 默认词库（插件安装后的初始配置，用户可在 设置 → 播放 → 过滤关键词 中增删）。
     * 保守选取：正常影视标题几乎不会出现的推广用语（如「澳门风云」不受影响）。
     */
    val DEFAULT_KEYWORDS: List<String> = listOf(
        // 博彩/网赌推广
        "博彩", "菠菜", "网赌", "赌博", "线上赌场", "澳门赌场", "太阳城",
        "娱乐城", "百家乐", "赌场直营", "送体验金",
        "注册就送", "首存就送", "免费开户", "代理开户",
        // 引流/联系方式
        "刷单", "兼职日结", "加微信", "加v信", "加威", "加vx", "薇信",
        "微信号", "加qq", "扣扣号", "企鹅号", "看片",
        // 违规内容引流
        "一夜情", "约炮", "色情", "情色", "三级片",
        "成人电影", "成人影片", "成人直播", "成人网站", "成人网",
        // 标题内嵌网址/下载引流
        "www.", ".com/", ".net/", "http", "app下载", "下载app",
    )

    /**
     * 简介强广告特征（固定内置，不可配置）：简介是长文本，误杀风险高，
     * 仅命中确定性推广语/网址时才隐藏。
     */
    private val OVERVIEW_KEYWORDS = listOf(
        "博彩", "网赌", "赌博", "娱乐城", "百家乐",
        "注册就送", "首存", "加微信", "加v信", "加qq", "微信号",
        "www.", "http",
    )

    /** 分隔符：中英文逗号 / 顿号 / 分号 / 换行 */
    private val SEPARATORS = charArrayOf(',', '，', '、', ';', '；', '\n', '\r')

    /** 解析用户配置词库（小写化、去重、去空白）；空配置 = 默认词库 */
    fun resolveKeywords(raw: String?): List<String> {
        val parsed = raw.orEmpty()
            .split(*SEPARATORS)
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
        return parsed.ifEmpty { DEFAULT_KEYWORDS }
    }

    /** 当前配置是否为默认词库（设置页展示用） */
    fun isDefaultKeywords(raw: String?): Boolean =
        resolveKeywords(raw) == DEFAULT_KEYWORDS

    /** 是否广告/推广条目（标题命中用户词库，或简介命中内置强广告词） */
    fun isAd(title: String?, overview: String?, keywords: List<String>): Boolean {
        val t = title?.lowercase().orEmpty()
        if (t.isNotEmpty() && keywords.any { t.contains(it) }) return true
        val o = overview?.lowercase().orEmpty()
        return o.isNotEmpty() && OVERVIEW_KEYWORDS.any { o.contains(it) }
    }
}
