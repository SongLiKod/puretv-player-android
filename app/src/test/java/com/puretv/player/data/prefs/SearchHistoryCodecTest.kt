package com.puretv.player.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索历史纯逻辑（SearchHistoryCodec）单元测试。
 * 需求：搜索历史动态记录真实搜过的关键词、最近在前、可单条删除/清空、上限 20 条、持久化保序。
 */
class SearchHistoryCodecTest {

    /* -------------------------- decode / encode -------------------------- */

    @Test
    fun decodeNullOrEmptyYieldsEmptyList() {
        assertTrue(SearchHistoryCodec.decode(null).isEmpty())
        assertTrue(SearchHistoryCodec.decode("").isEmpty())
    }

    @Test
    fun decodeDropsBlankLinesAndKeepsOrder() {
        assertEquals(
            listOf("仙逆", "斗破"),
            SearchHistoryCodec.decode("仙逆\n\n斗破\n"),
        )
    }

    @Test
    fun encodeDecodeRoundTripKeepsRecencyOrder() {
        val words = listOf("仙逆", "凡人修仙传", "海贼王")
        assertEquals(words, SearchHistoryCodec.decode(SearchHistoryCodec.encode(words)))
    }

    /* -------------------------------- add -------------------------------- */

    @Test
    fun addPutsNewWordFirst() {
        assertEquals(listOf("斗破", "仙逆"), SearchHistoryCodec.add(listOf("仙逆"), "斗破"))
    }

    @Test
    fun addMovesDuplicateToFrontAndDedupes() {
        assertEquals(
            listOf("仙逆", "凡人修仙传"),
            SearchHistoryCodec.add(listOf("凡人修仙传", "仙逆"), "仙逆"),
        )
    }

    @Test
    fun addTrimsInputAndIgnoresBlank() {
        val current = listOf("仙逆")
        assertEquals(current, SearchHistoryCodec.add(current, "   "))
        assertEquals(current, SearchHistoryCodec.add(current, ""))
        assertEquals(listOf("斗破", "仙逆"), SearchHistoryCodec.add(current, "  斗破 "))
    }

    @Test
    fun addCapsAtLimitDroppingOldest() {
        var words = emptyList<String>()
        repeat(SearchHistoryCodec.LIMIT + 5) { i ->
            words = SearchHistoryCodec.add(words, "词$i")
        }
        assertEquals(SearchHistoryCodec.LIMIT, words.size)
        assertEquals("词${SearchHistoryCodec.LIMIT + 4}", words.first())
        // 最旧的几条被挤出
        assertFalse(words.contains("词0"))
        assertFalse(words.contains("词4"))
        assertTrue(words.contains("词5"))
    }

    /* ------------------------------- remove ------------------------------- */

    @Test
    fun removeDeletesOnlyExactMatch() {
        assertEquals(listOf("仙逆"), SearchHistoryCodec.remove(listOf("斗破", "仙逆"), "斗破"))
    }

    @Test
    fun removeMissingWordKeepsListUnchanged() {
        assertEquals(listOf("仙逆"), SearchHistoryCodec.remove(listOf("仙逆"), "不存在"))
    }

    @Test
    fun removeLastWordYieldsEmptyList() {
        assertTrue(SearchHistoryCodec.remove(listOf("仙逆"), "仙逆").isEmpty())
    }
}
