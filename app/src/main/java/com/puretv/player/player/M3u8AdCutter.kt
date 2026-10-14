package com.puretv.player.player

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.File
import kotlin.math.roundToInt

/** 视频内广告剪切的回传结果（播放层据此展示「已过滤广告」等提示） */
sealed interface AdCutResult {
    /** 已剪切并生成本地播放列表（[cutSeconds] 秒 / [cutCount] 段） */
    data class Applied(val localUrl: String, val cutSeconds: Int, val cutCount: Int) : AdCutResult

    /** 检测失败（拉取被拒/网络异常），按原地址播放 */
    data class Failed(val reason: String) : AdCutResult

    /** 非 HLS 资源（mp4 直链等），本过滤不适用 */
    object NotApplicable : AdCutResult

    /** 是 HLS 但未发现广告，原样播放 */
    object NoAds : AdCutResult
}

/**
 * 视频内广告剪切（插件「过滤广告」→ 设置「过滤视频广告」）。
 *
 * 拉取 m3u8 播放列表，按「内容地址键（域名+目录）」识别广告分片并剪掉：
 * - 片头前置广告（开头连续异键段）与中插广告（列表中部的连续异键段）；
 * - 多清晰度主列表（master）：逐清晰度清单剪切后重写本地主列表，保留自适应切档；
 * - 加密清单（AES-128）：平移 EXT-X-MEDIA-SEQUENCE 保留分片序列号，IV 不破坏；
 * - 地址嗅探：无 m3u8 扩展名的地址先看响应头 7 字节是否 #EXTM3U，mp4 等容器直链直接跳过。
 *
 * 保守策略（任一不满足即不剪，按原地址播放）：
 * - 单个可剪区间时长 <10s（可能是正片冷开场）或 >120s（判定存疑）；
 * - 累计剪切量 ≥ 总时长一半；分片地址解析失败；非 #EXTM3U 文本；
 * - 拉取默认补浏览器 UA 与同源 Referer，规避部分 CDN 防盗链 403。
 */
object M3u8AdCutter {

    /** 检测超时（master 需逐清晰度拉取，放宽到 8 秒；期间播放页显示加载圈） */
    const val TIMEOUT_MS = 8_000L

    private const val MIN_CUT_SEC = 10.0
    private const val MAX_CUT_SEC = 120.0

    /** master 最多处理的清晰度数（全部处理，避免降档/升档后又看到广告） */
    private const val MAX_VARIANTS = 6

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 明显非 HLS 的容器扩展：直接不拉取，避免误下载整段视频 */
    private val NON_HLS_EXTENSIONS = setOf(
        "mp4", "mkv", "flv", "avi", "mov", "wmv", "ts", "rmvb", "mpg", "mpeg", "webm",
    )

    /**
     * 检测并剪切视频内广告。
     * @return 结果语义见 [AdCutResult]；任何异常都会被吞掉并归入 [AdCutResult.Failed]
     */
    suspend fun cut(
        url: String,
        headers: Map<String, String>,
        client: OkHttpClient,
        cacheDir: File,
    ): AdCutResult = withContext(Dispatchers.IO) {
        runCatching {
            if (!looksLikeHls(url)) return@runCatching AdCutResult.NotApplicable
            val text = fetch(url, headers, client)
                ?: return@runCatching AdCutResult.Failed("拉取播放列表失败")
            if (text.isEmpty()) return@runCatching AdCutResult.NotApplicable
            if (!text.startsWith("#EXTM3U")) return@runCatching AdCutResult.NotApplicable
            val dir = File(cacheDir, "adcut").apply { mkdirs() }
            if (text.contains("#EXT-X-STREAM-INF")) {
                cutMaster(text, url, headers, client, dir)
            } else {
                cutSingle(text, url, dir)
            }
        }.getOrElse { e ->
            AdCutResult.Failed(e.message?.take(40) ?: "检测异常")
        }
    }

    /** 单一媒体清单：剪切后写本地文件 */
    private fun cutSingle(text: String, baseUrl: String, dir: File): AdCutResult {
        val (rewritten, stats) = cutMedia(text, baseUrl) ?: return AdCutResult.NoAds
        val file = writePlaylist(dir, baseUrl, rewritten)
            ?: return AdCutResult.Failed("写缓存失败")
        return AdCutResult.Applied(
            localUrl = Uri.fromFile(file).toString(),
            cutSeconds = stats.seconds.roundToInt(),
            cutCount = stats.count,
        )
    }

    /**
     * 多清晰度主列表：逐清晰度清单剪切，重写本地 master。
     * 未剪的清晰度也改为绝对地址（本地 file:// 基准无法解析相对路径），
     * 播放器仍在各清晰度间自适应切换，切换后的清单同样已剪好。
     */
    private fun cutMaster(
        text: String,
        baseUrl: String,
        headers: Map<String, String>,
        client: OkHttpClient,
        dir: File,
    ): AdCutResult {
        val lines = text.lines()
        val variants = parseVariantUris(lines, baseUrl).take(MAX_VARIANTS)
        if (variants.isEmpty()) return AdCutResult.NoAds

        var cutSeconds = 0.0
        var cutCount = 0
        var anyCut = false
        var fetched = 0
        // 原 URI 行文本 → 输出地址（默认绝对化远端，剪中则换成本地文件）
        val replacements = LinkedHashMap<String, String>()

        for ((uriText, absolute) in variants) {
            replacements[uriText] = absolute
            val variantText = fetch(absolute, headers, client)
            if (variantText != null) fetched++
            if (variantText.isNullOrEmpty() || !variantText.startsWith("#EXTM3U")) continue
            if (variantText.contains("#EXT-X-STREAM-INF")) continue // 嵌套主列表：跳过
            val cut = cutMedia(variantText, absolute) ?: continue
            val file = writePlaylist(dir, "$absolute#variant", cut.first) ?: continue
            replacements[uriText] = Uri.fromFile(file).toString()
            anyCut = true
            cutSeconds += cut.second.seconds
            cutCount += cut.second.count
        }

        if (fetched == 0) return AdCutResult.Failed("拉取清晰度清单失败")
        if (!anyCut) return AdCutResult.NoAds
        val master = rewriteMaster(lines, baseUrl, replacements)
        val file = writePlaylist(dir, baseUrl, master)
            ?: return AdCutResult.Failed("写缓存失败")
        return AdCutResult.Applied(
            localUrl = Uri.fromFile(file).toString(),
            cutSeconds = cutSeconds.roundToInt(),
            cutCount = cutCount,
        )
    }

    /** 主列表里的清晰度 URI（#EXT-X-STREAM-INF 后的非注释行），保持出现顺序并去重 */
    private fun parseVariantUris(lines: List<String>, baseUrl: String): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        var expectUri = false
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                expectUri = true
                continue
            }
            if (line.startsWith("#")) continue // 属性行不改变「等待 URI」状态
            if (expectUri) {
                if (!out.containsKey(line)) out[line] = resolveUrl(baseUrl, line)
                expectUri = false
            }
        }
        return out.map { it.key to it.value }
    }

    /** 重写主列表：清晰度按 [placements] 替换；音轨/画中画 URI 属性绝对化（仍指远端） */
    private fun rewriteMaster(
        lines: List<String>,
        baseUrl: String,
        placements: Map<String, String>,
    ): String {
        val out = StringBuilder()
        var expectUri = false
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) -> {
                    out.append(raw).append('\n')
                    expectUri = true
                }

                expectUri && line.isNotEmpty() && !line.startsWith("#") -> {
                    out.append(placements[line] ?: resolveUrl(baseUrl, line)).append('\n')
                    expectUri = false
                }

                line.startsWith("#EXT-X-MEDIA:", ignoreCase = true) ||
                    line.startsWith("#EXT-X-I-FRAME-STREAM-INF:", ignoreCase = true) ->
                    out.append(absolutizeUriAttr(raw, baseUrl)).append('\n')

                else -> out.append(raw).append('\n')
            }
        }
        return out.toString()
    }

    private data class CutStats(val seconds: Double, val count: Int)

    /**
     * 媒体清单剪切核心。
     * @return 改写后的清单文本 + 剪切统计；不满足条件返回 null（按原样播放）
     */
    private fun cutMedia(text: String, baseUrl: String): Pair<String, CutStats>? {
        if (!text.startsWith("#EXTM3U")) return null
        val lines = text.lines()
        val segments = parseSegments(lines, baseUrl)
        if (segments.size < 2) return null

        // 内容地址键 = 域名 + 目录；取后半段（正片所在）的键集合：
        // 抵御多 CDN 轮换（不依赖单个末段域名），也能识别「同域异目录」的广告
        val keys = segments.map { segKey(it.uri, baseUrl) }
        if (keys.any { it.isEmpty() }) return null // 有地址解析失败 → 保守不剪
        val contentKeys = keys.drop(segments.size / 2).toSet()

        // 连续「异键」区间 = 广告（片头前置 + 中插/片尾）
        val runs = ArrayList<List<Int>>()
        var i = 0
        while (i < segments.size) {
            if (keys[i] !in contentKeys) {
                val start = i
                while (i < segments.size && keys[i] !in contentKeys) i++
                runs.add((start until i).toList())
            } else {
                i++
            }
        }
        if (runs.isEmpty()) return null

        // 保守守卫：每段 [10s, 120s]；累计 < 总时长一半（防止误剪正片）
        val totalDur = segments.sumOf { it.dur }
        if (totalDur <= 0.0) return null
        val accepted = ArrayList<List<Int>>()
        var cutDur = 0.0
        var cutCount = 0
        for (run in runs) {
            val duration = run.sumOf { segments[it].dur }
            if (duration < MIN_CUT_SEC || duration > MAX_CUT_SEC) continue
            if (cutDur + duration >= totalDur / 2) break
            accepted.add(run)
            cutDur += duration
            cutCount += run.size
        }
        if (accepted.isEmpty()) return null

        val rebuilt = rebuild(lines, segments, accepted, baseUrl, text, cutCount)
        return rebuilt to CutStats(cutDur, cutCount)
    }

    /**
     * 重建播放列表：
     * 1. 剪除广告分片的 EXTINF+URI 行及区间内全部标签；
     * 2. 保留分片地址、KEY/MAP 地址绝对化（本地文件基准下可解析）；
     * 3. 加密清单平移 EXT-X-MEDIA-SEQUENCE（分片序列号不变 → IV 正确）；
     * 4. DISCONTINUITY-SEQUENCE 同步平移；
     * 5. 区间内出现过 KEY/MAP 而剪后未再声明的，把剪前生效的补回剪切点。
     */
    private fun rebuild(
        lines: List<String>,
        segments: List<Segment>,
        accepted: List<List<Int>>,
        baseUrl: String,
        originalText: String,
        cutCount: Int,
    ): String {
        val dropRanges = accepted
            .map { run -> segments[run.first()].extinfIndex..segments[run.last()].uriIndex }
            .sortedBy { it.first }

        val dropped = BooleanArray(lines.size)
        for (range in dropRanges) for (index in range) dropped[index] = true

        // —— 序列号平移（AES-128 的 IV 依赖分片序列号）——
        val encrypted = KEY_ENCRYPTED_REGEX.containsMatchIn(originalText)
        val seqLine = lines.indexOfFirst { it.trim().startsWith("#EXT-X-MEDIA-SEQUENCE:", ignoreCase = true) }
        val originalSeq = if (seqLine >= 0) {
            lines[seqLine].substringAfter(':').trim().toLongOrNull() ?: 0L
        } else 0L
        val rewriteSeq = seqLine >= 0 || (encrypted && cutCount > 0)

        // —— DISCONTINUITY-SEQUENCE 同步平移 ——
        val discSeqLine = lines.indexOfFirst { it.trim().startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:", ignoreCase = true) }
        val originalDiscSeq = if (discSeqLine >= 0) {
            lines[discSeqLine].substringAfter(':').trim().toLongOrNull() ?: 0L
        } else 0L
        val removedDiscs = dropRanges.sumOf { range ->
            lines.subList(range.first, range.last + 1).count { raw ->
                val line = raw.trim()
                line.startsWith("#EXT-X-DISCONTINUITY", ignoreCase = true) &&
                    !line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE", ignoreCase = true)
            }
        }

        // —— KEY/MAP 卫生：区间内出现、剪后未再声明的，把剪前生效版本补回剪切点 ——
        val insertBefore = HashMap<Int, MutableList<String>>()
        for (range in dropRanges) {
            val inner = lines.subList(range.first, range.last + 1)
            val innerHasKey = inner.any { it.trim().startsWith("#EXT-X-KEY:", ignoreCase = true) }
            val innerHasMap = inner.any { it.trim().startsWith("#EXT-X-MAP:", ignoreCase = true) }
            if (!innerHasKey && !innerHasMap) continue
            val afterLines = (range.last + 1 until lines.size).takeWhile { index ->
                lines[index].trim().isEmpty() || lines[index].trim().startsWith("#")
            }
            val inserts = mutableListOf<String>()
            if (innerHasKey && afterLines.none { lines[it].trim().startsWith("#EXT-X-KEY:", ignoreCase = true) }) {
                lines.subList(0, range.first)
                    .lastOrNull { it.trim().startsWith("#EXT-X-KEY:", ignoreCase = true) }
                    ?.let { inserts.add(it) }
            }
            if (innerHasMap && afterLines.none { lines[it].trim().startsWith("#EXT-X-MAP:", ignoreCase = true) }) {
                lines.subList(0, range.first)
                    .lastOrNull { it.trim().startsWith("#EXT-X-MAP:", ignoreCase = true) }
                    ?.let { inserts.add(it) }
            }
            if (inserts.isNotEmpty()) insertBefore.getOrPut(range.last + 1) { mutableListOf() }.addAll(inserts)
        }

        val segByUriLine = segments.associateBy { it.uriIndex }
        val out = StringBuilder(originalText.length)
        lines.forEachIndexed { index, raw ->
            if (dropped[index]) return@forEachIndexed
            insertBefore[index]?.forEach { tag ->
                out.append(absolutizeUriAttr(tag, baseUrl)).append('\n')
            }
            val line = raw.trim()
            when {
                index in segByUriLine ->
                    out.append(resolveUrl(baseUrl, segByUriLine.getValue(index).uri)).append('\n')

                rewriteSeq && index == seqLine ->
                    out.append("#EXT-X-MEDIA-SEQUENCE: ").append(originalSeq + cutCount).append('\n')

                rewriteSeq && seqLine < 0 && index == 0 -> {
                    out.append(raw).append('\n')
                    out.append("#EXT-X-MEDIA-SEQUENCE: ").append(cutCount).append('\n')
                }

                discSeqLine >= 0 && index == discSeqLine ->
                    out.append("#EXT-X-DISCONTINUITY-SEQUENCE: ").append(originalDiscSeq + removedDiscs).append('\n')

                line.startsWith("#EXT-X-KEY:", ignoreCase = true) ||
                    line.startsWith("#EXT-X-MAP:", ignoreCase = true) ->
                    out.append(absolutizeUriAttr(raw, baseUrl)).append('\n')

                else -> out.append(raw).append('\n')
            }
        }
        return out.toString()
    }

    private class Segment(val extinfIndex: Int, val uriIndex: Int, val dur: Double, val uri: String)

    /** 解析分片：#EXTINF 行 + 紧随其后的 URI 行 */
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

    /**
     * 是否值得拉取检测：http(s) 地址，含 m3u8 特征、或扩展未知（嗅探兜底）；
     * mp4 等容器直链直接排除。
     */
    private fun looksLikeHls(url: String): Boolean {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return false
        if (url.contains("m3u8", ignoreCase = true)) return true
        val path = url.toHttpUrlOrNull()?.encodedPath ?: return false
        val ext = path.substringAfterLast('.').lowercase()
        return ext !in NON_HLS_EXTENSIONS
    }

    /**
     * 拉取播放列表文本：
     * - 默认补浏览器 UA 与同源 Referer（部分 CDN 防盗链会拒绝播放器默认 UA/空 Referer）；
     * - 先嗅探响应前 7 字节是否 #EXTM3U，不是则返回 ""（避免误下载 mp4 直链）。
     * @return 播放列表文本；HTTP 失败/无响应体返回 null；内容非 HLS 返回 ""
     */
    private fun fetch(url: String, headers: Map<String, String>, client: OkHttpClient): String? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val hasUa = headers.keys.any { it.equals("User-Agent", true) }
        val hasReferer = headers.keys.any { it.equals("Referer", true) }
        val req = Request.Builder()
            .url(httpUrl)
            .apply {
                if (!hasUa) header("User-Agent", BROWSER_UA)
                if (!hasReferer) header("Referer", "${httpUrl.scheme}://${httpUrl.host}/")
                headers.forEach { (k, v) -> addHeader(k, v) }
            }
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val source = resp.body?.source() ?: return null
            val head = Buffer().also { source.peek().read(it, 7L) }.readUtf8()
            if (!head.startsWith("#EXTM3U")) return ""
            return source.readUtf8()
        }
    }

    private fun writePlaylist(dir: File, nameKey: String, content: String): File? = runCatching {
        val file = File(dir, "${nameKey.hashCode().toUInt().toString(16)}.m3u8")
        file.writeText(content)
        file
    }.getOrNull()

    private fun resolveUrl(base: String, uri: String): String {
        if (uri.startsWith("http://", true) || uri.startsWith("https://", true)) return uri
        return base.toHttpUrlOrNull()?.resolve(uri)?.toString() ?: uri
    }

    /** 分片地址键 = 域名 + 目录（同目录视为同一来源）；无法解析返回空串 */
    private fun segKey(uri: String, base: String): String {
        val http = resolveUrl(base, uri).toHttpUrlOrNull() ?: return ""
        return http.host + http.encodedPath.substringBeforeLast('/')
    }

    /** 把标签里的 URI="..." 属性改写为绝对地址（KEY/MAP/MEDIA/I-FRAME 通用） */
    private fun absolutizeUriAttr(line: String, base: String): String {
        val match = URI_ATTR_REGEX.find(line) ?: return line
        val original = match.groupValues[1]
        return line.replace(original, resolveUrl(base, original))
    }

    private val KEY_ENCRYPTED_REGEX = Regex("#EXT-X-KEY:[^\n]*METHOD=(?!NONE)", RegexOption.IGNORE_CASE)
    private val URI_ATTR_REGEX = Regex("URI=\"([^\"]+)\"")
}
