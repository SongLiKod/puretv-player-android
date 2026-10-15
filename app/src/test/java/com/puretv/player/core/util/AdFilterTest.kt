package com.puretv.player.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 广告/推广条目过滤（AdFilter）单元测试。
 * 回归背景：词库含「澳门赌场」等词，正常影片《澳门风云》不能被误杀；
 * 空配置必须回退默认词库（否则过滤形同虚设）。
 */
class AdFilterTest {

    /* ------------------------------ resolveKeywords ------------------------------ */

    @Test
    fun nullOrBlankConfigFallsBackToDefaultKeywords() {
        assertEquals(AdFilter.DEFAULT_KEYWORDS, AdFilter.resolveKeywords(null))
        assertEquals(AdFilter.DEFAULT_KEYWORDS, AdFilter.resolveKeywords(""))
        assertEquals(AdFilter.DEFAULT_KEYWORDS, AdFilter.resolveKeywords("  \n "))
        assertTrue(AdFilter.isDefaultKeywords(null))
        assertTrue(AdFilter.isDefaultKeywords(""))
    }

    @Test
    fun customConfigParsesAllSeparatorsAndDedupes() {
        assertEquals(
            listOf("网赌", "加微信"),
            AdFilter.resolveKeywords("网赌，加微信、网赌"),
        )
        assertEquals(
            listOf("网赌", "加微信", "刷单"),
            AdFilter.resolveKeywords("网赌\n加微信;刷单，网赌"),
        )
    }

    @Test
    fun customConfigIsLowercasedAndTrimmed() {
        assertEquals(listOf("abc", "net"), AdFilter.resolveKeywords("  ABC , Net "))
        assertFalse(AdFilter.isDefaultKeywords("自定义词"))
    }

    /* --------------------------------- isAd --------------------------------- */

    @Test
    fun defaultKeywordsFilterGamblingAndSpamTitles() {
        assertTrue(AdFilter.isAd("娱乐城在线注册就送", null, AdFilter.DEFAULT_KEYWORDS))
        assertTrue(AdFilter.isAd("线上赌场VIP专区", null, AdFilter.resolveKeywords(null)))
        assertTrue(AdFilter.isAd("兼职日结速来", null, AdFilter.DEFAULT_KEYWORDS))
    }

    @Test
    fun normalMovieTitlesAreNotKilled() {
        // 回归：默认词库含「澳门赌场」，《澳门风云》系列标题/简介不能被误杀
        assertFalse(AdFilter.isAd("澳门风云3", "赌神传奇故事", AdFilter.DEFAULT_KEYWORDS))
        assertFalse(
            AdFilter.isAd("西游记之大圣归来", "一段经典的神魔故事", AdFilter.DEFAULT_KEYWORDS),
        )
    }

    @Test
    fun titleMatchesUserKeywordCaseInsensitively() {
        assertTrue(AdFilter.isAd("FREE-APP下载", null, listOf("app")))
        assertTrue(AdFilter.isAd("看片群分享", null, listOf("看片")))
        assertFalse(AdFilter.isAd("FREE-APP", null, listOf("网赌")))
    }

    @Test
    fun overviewStrongKeywordsTriggerEvenWhenTitleClean() {
        assertTrue(
            AdFilter.isAd("正常标题", "详情访问 www.spam.com 在线观看", AdFilter.DEFAULT_KEYWORDS),
        )
        assertTrue(
            AdFilter.isAd("正常标题", "加微信联系客服获取", AdFilter.DEFAULT_KEYWORDS),
        )
        assertFalse(
            AdFilter.isAd("正常标题", "一段普通的剧情简介", AdFilter.DEFAULT_KEYWORDS),
        )
    }

    @Test
    fun emptyTitleAndOverviewAreNotAds() {
        assertFalse(AdFilter.isAd(null, null, AdFilter.DEFAULT_KEYWORDS))
        assertFalse(AdFilter.isAd("", "", AdFilter.DEFAULT_KEYWORDS))
        // 标题为空且简介无强广告词：不因关键词库误判
        assertFalse(AdFilter.isAd(null, "剧情简介", AdFilter.DEFAULT_KEYWORDS))
    }
}
