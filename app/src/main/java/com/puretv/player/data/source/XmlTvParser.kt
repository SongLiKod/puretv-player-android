package com.puretv.player.data.source

import android.util.Xml
import com.puretv.player.core.util.StringUtils
import com.puretv.player.data.local.entity.EpgProgramEntity
import org.xmlpull.v1.XmlPullParser
import java.io.Reader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * XMLTV 节目单流式解析器（需求 5.3.4：EPG 实时节目单）。
 *
 * 解析 `<programme start="yyyyMMddHHmmss ±zzzz" stop="…" channel="epgId">`
 * 下的 `<title>` / `<desc>`，带时区转 epoch 毫秒。
 */
object XmlTvParser {

    /**
     * 流式解析 XMLTV。
     * @param onProgram 每解析一个节目立即回调，调用方负责分块入库与进度上报
     * @return 成功解析的节目数
     */
    suspend fun parse(reader: Reader, onProgram: suspend (EpgProgramEntity) -> Unit): Int {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(reader)

        var count = 0
        var inProgramme = false
        var startAt = 0L
        var endAt = 0L
        var epgId = ""
        var title: String? = null
        var desc: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    if (name.equals("programme", ignoreCase = true)) {
                        inProgramme = true
                        val channel = parser.getAttributeValue(null, "channel").orEmpty()
                        val start = parser.getAttributeValue(null, "start")
                        val stop = parser.getAttributeValue(null, "stop")
                        startAt = parseXmlTvTime(start)
                        endAt = parseXmlTvTime(stop)
                        epgId = channel
                        title = null
                        desc = null
                    } else if (inProgramme && name.equals("title", ignoreCase = true)) {
                        title = parser.nextText().trim()
                    } else if (inProgramme && name.equals("desc", ignoreCase = true)) {
                        desc = parser.nextText().trim()
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("programme", ignoreCase = true)) {
                        inProgramme = false
                        if (epgId.isNotEmpty() && startAt > 0 && endAt > startAt) {
                            val finalTitle = title?.takeIf { it.isNotEmpty() } ?: "未知节目"
                            onProgram(
                                EpgProgramEntity(
                                    id = StringUtils.stableId("$epgId|$startAt|$finalTitle"),
                                    epgId = epgId,
                                    title = finalTitle,
                                    description = desc?.takeIf { it.isNotEmpty() },
                                    startAt = startAt,
                                    endAt = endAt,
                                )
                            )
                            count++
                        }
                    }
                }
            }
            event = parser.next()
        }
        return count
    }

    // SimpleDateFormat 非线程安全：解析统一在 IO 协程执行，这里用 ThreadLocal 兜底避免并发污染
    private val spacedFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { timeZone = TimeZone.getDefault() }
    }
    private val tightFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyyMMddHHmmssZ", Locale.US).apply { timeZone = TimeZone.getDefault() }
    }
    private val plainFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getDefault() }
    }

    /**
     * XMLTV 时间：yyyyMMddHHmmss ±zzzz（时区可缺省，缺省按设备时区）
     */
    fun parseXmlTvTime(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        val normalized = value.trim().replace(Regex("\\s+"), " ")
        for (format in listOf(spacedFormat, tightFormat, plainFormat)) {
            try {
                val date = format.get()!!.parse(normalized) ?: continue
                return date.time
            } catch (_: Exception) {
                // 尝试下一种格式
            }
        }
        // 无时区的紧凑格式兜底
        val digits = normalized.filter { it.isDigit() }
        if (digits.length >= 14) {
            try {
                return plainFormat.get()!!.parse(digits.substring(0, 14))?.time ?: 0L
            } catch (_: Exception) {
                return 0L
            }
        }
        return 0L
    }
}
