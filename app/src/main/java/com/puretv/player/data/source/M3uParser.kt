package com.puretv.player.data.source

import com.puretv.player.core.util.StringUtils
import com.puretv.player.data.local.entity.ChannelEntity
import java.io.Reader

/**
 * M3U 播放列表流式解析器（需求 5.3.1 / 5.3.2）。
 *
 * 逐行解析，不整读内存，支持大容量频道列表秒解析：
 * - #EXTM3U 头（含 x-tvg-url EPG 地址）
 * - #EXTINF:-1 tvg-id/tvg-name/tvg-logo/group-title/tvg-chno/quality 等属性 + 频道名
 * - #EXTGRP 分组
 * - #EXTVLCOPT / #IPVCOPT / #KODIPROP → 自定义请求头 headers
 * - catchup / catchup-source / catchup-days / catchup-shift → 回看配置
 * - EXTINF 后连续非注释行：第一行为主播放地址，其余为备用源 backupUrls
 * - 频道名去重保序
 */
object M3uParser {

    /** 解析结果摘要 */
    data class ParseResult(
        /** 成功解析并回调的频道数（已去重） */
        val channelCount: Int,
        /** 因重名被跳过的条目数 */
        val duplicateCount: Int,
        /** #EXTM3U 头部声明的 EPG 地址（x-tvg-url / url-tvg），无则 null */
        val epgUrl: String? = null,
    )

    /** 解析中的临时频道（收集到连续注释/空行边界后落盘） */
    private class PendingChannel {
        var name: String = ""
        var tvgId: String? = null
        var logo: String? = null
        var groupName: String? = null
        var number: Int? = null
        var quality: String? = null
        var catchupType: String? = null
        var catchupSource: String? = null
        var catchupDays: Int = 0
        val headers: MutableMap<String, String> = mutableMapOf()
        val urls: MutableList<String> = mutableListOf()

        fun toEntity(sourceId: String, index: Int, now: Long): ChannelEntity {
            val primary = urls.first()
            return ChannelEntity(
                sourceId = sourceId,
                id = StringUtils.stableId("$index|$primary|$name"),
                name = name,
                logo = logo,
                url = primary,
                groupName = groupName?.takeIf { it.isNotBlank() } ?: "未分组",
                epgId = tvgId?.takeIf { it.isNotBlank() },
                number = number,
                quality = quality ?: QualityDetector.detect(name),
                headers = headers.toMap(),
                backupUrls = urls.drop(1),
                catchupType = catchupType,
                catchupSource = catchupSource,
                catchupDays = catchupDays,
                updatedAt = now,
            )
        }
    }

    /**
     * 流式解析 M3U 文本。
     * @param onChannel 每解析完成一个频道（去重后）立即回调，调用方负责节流上报进度
     */
    fun parse(
        reader: Reader,
        sourceId: String,
        onChannel: (ChannelEntity) -> Unit,
    ): ParseResult {
        val buffered = reader.buffered()
        val seenNames = LinkedHashSet<String>() // 频道名去重保序
        var headerEpgUrl: String? = null
        var pending: PendingChannel? = null
        var index = 0
        var duplicates = 0
        var count = 0
        val now = System.currentTimeMillis()

        fun flush() {
            val channel = pending ?: return
            pending = null
            if (channel.urls.isEmpty()) return
            if (!seenNames.add(channel.name)) {
                duplicates++
                return
            }
            index++
            onChannel(channel.toEntity(sourceId, index, now))
            count++
        }

        buffered.forEachLine { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> {
                    // 空行：结束当前频道的地址收集
                    flush()
                }
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    // 播放列表头：可携带 x-tvg-url / url-tvg EPG 地址
                    headerEpgUrl = headerEpgUrl ?: parseAttributes(line)
                        .entries.firstOrNull {
                            it.key.equals("x-tvg-url", true) || it.key.equals("url-tvg", true) ||
                                it.key.equals("tvg-url", true)
                        }?.value
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    // 新频道开始：先落盘上一个
                    flush()
                    pending = PendingChannel().apply { parseExtInf(line) }
                }
                line.startsWith("#EXTGRP", ignoreCase = true) -> {
                    val group = line.substringAfter(':').trim()
                    if (group.isNotEmpty()) pending?.groupName = group
                }
                line.startsWith("#EXTVLCOPT", ignoreCase = true) ||
                    line.startsWith("#IPVCOPT", ignoreCase = true) -> {
                    parseOptLine(line.substringAfter(':'), pending?.headers)
                }
                line.startsWith("#KODIPROP", ignoreCase = true) -> {
                    parseKodiProp(line.substringAfter(':'), pending?.headers)
                }
                line.startsWith("#") -> {
                    // 其他注释行：不打断地址收集（部分源在地址间夹注释）
                }
                else -> {
                    // 非注释行：主地址 / 备用源
                    val current = pending
                    if (current != null && StringUtils.isHttpUrl(line)) {
                        current.urls.add(StringUtils.normalizeUrl(line))
                    } else if (current != null) {
                        // 非法地址行（如 udp 之外的奇怪协议），同样按备用源收集，交给播放内核处理
                        current.urls.add(StringUtils.normalizeUrl(line))
                    }
                    // pending 为 null 的裸地址行（无 EXTINF）直接忽略
                }
            }
        }
        flush()

        return ParseResult(
            channelCount = count,
            duplicateCount = duplicates,
            epgUrl = headerEpgUrl,
        )
    }

    /** 解析 #EXTINF 行：属性区 → 频道名 */
    private fun PendingChannel.parseExtInf(line: String) {
        val payload = line.substringAfter(':')
        // 属性区在第一个未被引号包裹的逗号处结束，其后为频道名
        var inQuote = false
        var splitAt = -1
        for (i in payload.indices) {
            when (payload[i]) {
                '"' -> inQuote = !inQuote
                ',' -> if (!inQuote) {
                    splitAt = i
                    break
                }
            }
        }
        val attrsPart = if (splitAt >= 0) payload.substring(0, splitAt) else payload
        val namePart = if (splitAt >= 0) payload.substring(splitAt + 1) else ""

        val attrs = parseAttributes(attrsPart)
        tvgId = attrs["tvg-id"] ?: attrs["tvg-id".uppercase()]
        logo = attrs["tvg-logo"] ?: attrs["logo"]
        groupName = attrs["group-title"] ?: attrs["group"]
        number = attrs["tvg-chno"]?.trim()?.toIntOrNull() ?: attrs["chno"]?.trim()?.toIntOrNull()
        // 自定义清晰度属性（quality=）：未提供时按频道名启发式识别
        quality = attrs["quality"]?.trim()?.uppercase()
        catchupType = attrs["catchup"]?.trim()?.lowercase()
            ?: if (attrs.containsKey("catchup-shift")) "shift" else null
        catchupSource = attrs["catchup-source"] ?: attrs["catchup-src"]
        catchupDays = (attrs["catchup-days"] ?: attrs["timeshift"])?.trim()?.toIntOrNull() ?: 0

        name = namePart.trim().ifEmpty {
            (attrs["tvg-name"]?.trim().orEmpty()).ifEmpty { "未命名频道" }
        }
    }

    /** 解析 key="value" / key=value 属性对 */
    private fun parseAttributes(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        val regex = Regex("([A-Za-z0-9_-]+)\\s*=\\s*(\"[^\"]*\"|[^\\s,]+)")
        for (match in regex.findAll(text)) {
            val key = match.groupValues[1].lowercase()
            val rawValue = match.groupValues[2]
            val value = rawValue.removeSurrounding("\"").trim()
            if (value.isNotEmpty()) result[key] = value
        }
        return result
    }

    /** #EXTVLCOPT / #IPVCOPT：key=value（如 http-user-agent / http-referrer） */
    private fun parseOptLine(body: String, headers: MutableMap<String, String>?) {
        if (headers == null) return
        val key = body.substringBefore('=').trim()
        val value = body.substringAfter('=', "").trim()
        if (key.isEmpty() || value.isEmpty()) return
        when (key.lowercase()) {
            "http-user-agent", "user-agent" -> headers["User-Agent"] = value
            "http-referrer", "http-referer", "referrer" -> headers["Referer"] = value
            else -> headers[key] = value
        }
    }

    /** #KODIPROP：http-user-agent / http-header.X 等 */
    private fun parseKodiProp(body: String, headers: MutableMap<String, String>?) {
        if (headers == null) return
        val key = body.substringBefore('=').trim()
        val value = body.substringAfter('=', "").trim()
        if (key.isEmpty() || value.isEmpty()) return
        val lower = key.lowercase()
        when {
            lower == "http-user-agent" -> headers["User-Agent"] = value
            lower == "http-referrer" || lower == "http-referer" -> headers["Referer"] = value
            lower.startsWith("http-header.") -> headers[key.substringAfter("http-header.")] = value
            else -> headers[key] = value
        }
    }
}
