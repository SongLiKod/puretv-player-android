package com.puretv.player.data.vod

import com.puretv.player.data.model.VodKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 品类归类启发式（VodKindClassifier）单元测试。
 * 回归背景：旧规则把「少儿/国漫/卡通/动漫电影」等动画面向分类误分进电影，
 * 导致「全部」里大量动漫内容、切到「动漫」标签却看不到。
 */
class VodKindClassifierTest {

    @Test
    fun animeKeywordCategoriesClassifyAsAnime() {
        val animeCategories = listOf(
            "日本动漫", "国产动画", "卡通片", "漫画改", "少儿频道",
            "国漫精选", "日漫剧场", "国创", "奥特曼", "熊出没",
        )
        animeCategories.forEach { category ->
            assertEquals(category, VodKind.ANIME, VodKindClassifier.ofCategory(category))
        }
    }

    @Test
    fun animeTakesPriorityOverMovieAndSeries() {
        assertEquals(VodKind.ANIME, VodKindClassifier.ofCategory("动漫电影"))
        assertEquals(VodKind.ANIME, VodKindClassifier.ofCategory("少儿剧场"))
        assertEquals(VodKind.ANIME, VodKindClassifier.ofCategory("动画连续剧"))
    }

    @Test
    fun movieCategoriesClassifyAsMovie() {
        assertEquals(VodKind.MOVIE, VodKindClassifier.ofCategory("动作电影"))
        assertEquals(VodKind.MOVIE, VodKindClassifier.ofCategory("高分剧场"))
    }

    @Test
    fun seriesCategoriesClassifyAsSeries() {
        assertEquals(VodKind.SERIES, VodKindClassifier.ofCategory("国产电视剧"))
        assertEquals(VodKind.SERIES, VodKindClassifier.ofCategory("连续剧"))
        assertEquals(VodKind.SERIES, VodKindClassifier.ofCategory("韩剧"))
    }

    @Test
    fun varietyCategoriesClassifyAsVariety() {
        assertEquals(VodKind.VARIETY, VodKindClassifier.ofCategory("真人秀综艺"))
        assertEquals(VodKind.VARIETY, VodKindClassifier.ofCategory("搞笑综艺"))
    }

    @Test
    fun unknownCategoryDefaultsToMovie() {
        assertEquals(VodKind.MOVIE, VodKindClassifier.ofCategory("纪录片"))
        assertEquals(VodKind.MOVIE, VodKindClassifier.ofCategory(""))
    }

    @Test
    fun streamTypeSeriesOverridesCategory() {
        assertEquals(VodKind.SERIES, VodKindClassifier.of("series", "动作电影"))
        assertEquals(VodKind.SERIES, VodKindClassifier.of("  SERIES ", "任意分类"))
        assertEquals(VodKind.MOVIE, VodKindClassifier.of(null, "动作电影"))
        assertEquals(VodKind.MOVIE, VodKindClassifier.of("movie", "动作电影"))
    }

    @Test
    fun isAnimeCategoryMatchesOnlyAnimeOrientedNames() {
        assertTrue(VodKindClassifier.isAnimeCategory("少儿动画"))
        assertTrue(VodKindClassifier.isAnimeCategory("国产动漫"))
        assertFalse(VodKindClassifier.isAnimeCategory("动作电影"))
        assertFalse(VodKindClassifier.isAnimeCategory(""))
    }
}
