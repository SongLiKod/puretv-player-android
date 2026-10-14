package com.puretv.player.player

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * m3u8 片头广告剪切（插件「过滤广告」→ 设置「过滤视频广告」）。
 *
 * 资源站正片分片与片头广告分片通常分属不同域名（独立广告 CDN），
 * 规则：媒体播放列表开头连续的「异域域名」分片视为片头广告剪掉，
 * 剩余正片分片改写为绝对地址后写入本地播放列表，交给播放器加载。
 *
 * 保守策略（任一不满足即不剪，按原地址播放）：
 * - 非 m3u8 / 拉取失败 / 非 #EXTM3U 文本；
 * - 多清晰度主列表（#EXT-X-STREAM-INF，交播放器选流）；
 * - 加密分片（#EXT-X-KEY METHOD≠NONE，不重排不删除）；
 * - 单分片列表；广告累计 <10s 或 >120s；剪切量 ≥ 总时长一半。
 */
object M3u8AdCutter {

    /** 检测超时（一次 GET 拉取播放列表，几 KB 文本） */
    const val TIMEOUT_MS = 4_000L

    private const val MIN_CUT_SEC = 10.0
    private const val MAX_CUT_SEC = 120.0

    /**
     * 检测并剪切片头广告。
     * @return 本地改写播放列表的 file:// 地址；无需剪切/检测失败返回 null
     */
    suspend fun cut(
        url: String,
        headers: Map<String, String>,
        client: OkHttpClient,
        cacheDir: File,
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val text = fetch(url, headers, client) ?: return@runCatching null
            val rewritten = rewrite(text, url) ?: return@runCatching null
            val dir = File(cacheDir, "adcut").apply { mkdirs() }
            // 只保留当前地址的剪切结果，避免旧文件堆积
            dir.listFiles()?.forEach { if (it.name != fileNameFor(url)) it.delete() }
            val out = File(dir, fileNameFor(url))
            out.writeText(rewritten)
            Uri.fromFile(out).toString()
        }.getOrNull()
    }

    private fun fileNameFor(url: String): String =
        "${url.hashCode().toUInt().toString(16)}.m3u8"

    private fun fetch(url: String, headers: Map<String, String>, client: OkHttpClient): String? {
        if (!url.contains(".m3u8", ignoreCase = true)) return null
        val req = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }

    /** 解析并改写播放列表；返回 null = 不满足剪切条件 */
    private fun rewrite(text: String, baseUrl: String): String? {
        if (!text.startsWith("#EXTM3U")) return null
        if (text.contains("#EXT-X-STREAM-INF")) return null
        if (KEY_ENCRYPTED_REGEX.containsMatchIn(text)) return null

        val lines = text.lines()
        // 解析分片：#EXTINF 行 + 紧随其后的 URI 行
        val segments = parseSegments(lines, baseUrl)
        if (segments.size <= 1) return null

        // 内容域名 = 末段域名（正片结尾必为内容分片）
        val contentHost = hostOf(segments.last().uri, baseUrl)
        if (contentHost.isEmpty()) return null

        // 前置异域段：从头连续、域名 ≠ 内容域名
        var cutDur = 0.0
        var cutCount = 0
        for (seg in segments) {
            val host = hostOf(seg.uri, baseUrl)
            if (host.equals(contentHost, ignoreCase = true)) break
            cutDur += seg.dur
            cutCount++
            if (cutDur > MAX_CUT_SEC) return null
        }
        if (cutCount == 0 || cutDur < MIN_CUT_SEC) return null
        val totalDur = segments.sumOf { it.dur }
        if (cutDur >= totalDur / 2) return null

        // 重建列表：丢弃广告分片的 EXTINF+URI 行，正片 URI 改写为绝对地址
        val droppedExtinf = segments.take(cutCount).map { it.extinfIndex }.toSet()
        val droppedUri = segments.take(cutCount).map { it.uriIndex }.toSet()
        val byUriLine = segments.associateBy { it.uriIndex }
        val out = StringBuilder(text.length)
        lines.forEachIndexed { index, raw ->
            when {
                index in droppedExtinf || index in droppedUri -> Unit
                index in byUriLine -> out.append(resolveUrl(baseUrl, byUriLine.getValue(index).uri)).append('\n')
                raw.trim().startsWith("#EXT-X-MAP:", ignoreCase = true) ->
                    out.append(absolutizeMapLine(raw, baseUrl)).append('\n')
                else -> out.append(raw).append('\n')
            }
        }
        return out.toString()
    }

    private class Segment(val extinfIndex: Int, val uriIndex: Int, val dur: Double, val uri: String)

    private fun parseSegments(lines: List<String>, baseUrl: String): List<Segment> {
        val result = ArrayList<Segment>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val dur = line.substringAfter(':', "").substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                val uriIndex = (i + 1 until lines.size).firstOrNull { idx ->
                    lines[idx].trim().isNotEmpty() && !lines[idx].trim().startsWith("#")
                }
                val uri = uriIndex?.let { lines[it].trim() }
                if (uri != null && uriIndex != null) {
                    result.add(Segment(i, uriIndex, dur, uri))
                }
            }
            i++
        }
        return result
    }

    private fun resolveUrl(base: String, uri: String): String {
        if (uri.startsWith("http://", true) || uri.startsWith("https://", true)) return uri
        return base.toHttpUrlOrNull()?.resolve(uri)?.toString() ?: uri
    }

    private fun hostOf(uri: String, base: String): String =
        resolveUrl(base, uri).toHttpUrlOrNull()?.host.orEmpty()

    private fun absolutizeMapLine(line: String, base: String): String {
        val match = MAP_URI_REGEX.find(line) ?: return line
        val original = match.groupValues[1]
        return line.replace(original, resolveUrl(base, original))
    }

    private val KEY_ENCRYPTED_REGEX = Regex("#EXT-X-KEY:[^\n]*METHOD=(?!NONE)", RegexOption.IGNORE_CASE)
    private val MAP_URI_REGEX = Regex("URI=\"([^\"]+)\"")
}
