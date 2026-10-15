package com.puretv.player.data.vod

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MacCMS DTO 数值转义单元测试。
 * 回归背景（两个真实线上问题）：
 * 1. Moshi 把 JSON 数字统一解析为 Double → pagecount "7919.0" 令 toIntOrNull 失败，
 *    总页数被误判为 1 → 整源同步只拉第 1 页（每条线路只加载 20 部）；
 * 2. type_id 6 → 6.0 → 向服务器发 t=6.0，部分站点不认 → 标签服务端过滤失效。
 */
class CmsVodDtoTest {

    private val adapter = Moshi.Builder().build().adapter(CmsVodListDto::class.java)

    /* ------------------------------- toIntOrDefault ------------------------------- */

    @Test
    fun doubleIntegralValueConvertsToInt() {
        assertEquals(7919, 7919.0.toIntOrDefault(1))
    }

    @Test
    fun doubleNonIntegralFallsBackToDefault() {
        assertEquals(1, 7919.5.toIntOrDefault(1))
    }

    @Test
    fun nullFallsBackToDefault() {
        assertEquals(0, (null as Any?).toIntOrDefault(0))
    }

    @Test
    fun numericStringWithDecimalPointConverts() {
        assertEquals(7919, "7919.0".toIntOrDefault(1))
    }

    @Test
    fun plainNumericStringConverts() {
        assertEquals(12, "12".toIntOrDefault(1))
    }

    @Test
    fun nonNumericStringFallsBackToDefault() {
        assertEquals(1, "abc".toIntOrDefault(1))
    }

    @Test
    fun intPassesThrough() {
        assertEquals(42, 42.toIntOrDefault(-1))
    }

    /* ------------------------------ typeIdToString ------------------------------ */

    @Test
    fun doubleTypeIdNormalizesToPlainIntegerString() {
        assertEquals("6", typeIdToString(6.0))
    }

    @Test
    fun intTypeIdPassesThroughAsDigits() {
        assertEquals("6", typeIdToString(6))
    }

    @Test
    fun decimalStringTypeIdNormalizesToIntegerHead() {
        assertEquals("6", typeIdToString("6.0"))
    }

    @Test
    fun nonNumericTypeIdKeepsOriginal() {
        assertEquals("abc", typeIdToString("abc"))
        assertEquals("12a", typeIdToString("12a"))
    }

    @Test
    fun nullAndBlankTypeIdAreDropped() {
        assertNull(typeIdToString(null))
        assertNull(typeIdToString(""))
        assertNull(typeIdToString("   "))
    }

    /* ------------------------- Moshi 解析端到端（Double 形态） ------------------------- */

    @Test
    fun parsesMacCmsListResponseAndNormalizesIds() {
        val json = """{"code":1,"page":1,"pagecount":7919,"total":158373,"list":[
            {"vod_id":101,"type_id":6,"type_name":"动作片","vod_name":"战狼"}]}"""
        val dto = adapter.fromJson(json)!!

        assertEquals(7919, dto.pageInt)
        assertEquals(158373, dto.totalInt)

        val item = dto.list!!.single()
        assertEquals("战狼", item.vodName)
        // 数字字段已被 Moshi 解成 Double，但归一化后请求参数不再带 .0
        assertEquals("6", typeIdToString(item.typeId))
        assertEquals("101", typeIdToString(item.vodId))
    }

    @Test
    fun stringFormPagingFieldsConvert() {
        val dto = adapter.fromJson("""{"pagecount":"12","total":"345","list":[]}""")!!
        assertEquals(12, dto.pageInt)
        assertEquals(345, dto.totalInt)
    }

    @Test
    fun missingPagecountFallsBackToOne() {
        val dto = adapter.fromJson("""{"list":[]}""")!!
        assertEquals(1, dto.pageInt)
        assertEquals(0, dto.totalInt)
    }
}
