package com.puretv.player.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretv.player.data.tvbox.TvBoxConfigImporter
import com.puretv.player.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject

/**
 * 在线搜索添加线路（用户追加需求：设置内在线搜索资源站，多选批量添加进系统）。
 *
 * 搜索通道：DuckDuckGo Lite（无需 API Key 的公开 HTML 搜索页），抓取后解析跳转链接
 * 中的真实目标地址（uddg 参数），去重后作为候选采集站地址。
 * - 默认每次搜索最多收集 **10 条**自动停止；搜索中可随时手动停止；
 * - 结果跨关键词累计（可多次搜索后一起勾选），上限 50 条；
 * - 批量添加时由 [TvBoxConfigImporter.addCmsSources] 逐条探测
 *   `api.php/provide/vod` 采集端点（MacCMS 协议，与内置线路同一体系），
 *   仅把可用线路写入源列表。
 */
@HiltViewModel
class AddSourceViewModel @Inject constructor(
    private val client: OkHttpClient,
    private val tvBoxImporter: TvBoxConfigImporter,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    /** 推荐搜索词：围绕 MacCMS 采集协议（api.php/provide/vod）与 TVBox 直连线路的高频特征词 */
    val recommendedKeywords: List<String> = listOf(
        "苹果CMS采集接口",
        "MacCMS 采集站",
        "api.php/provide/vod",
        "影视采集线路 m3u8",
        "TVBox 直连资源站",
        "maccms 采集 json",
    )

    private val _results = MutableStateFlow<List<String>>(emptyList())
    val results: StateFlow<List<String>> = _results.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _adding = MutableStateFlow(false)
    val adding: StateFlow<Boolean> = _adding.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _addProgress = MutableStateFlow<String?>(null)
    val addProgress: StateFlow<String?> = _addProgress.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var searchJob: Job? = null

    /** 搜索：默认收满 10 条新结果自动停止；结果跨关键词累计，总上限 50 条 */
    fun search(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty() || _searching.value || _adding.value) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _searching.value = true
            _status.value = "正在搜索「$query」…"
            try {
                val fetched = withContext(io) { fetchDuckDuckGoLite(query) }
                val merged = _results.value.toMutableList()
                var addedNew = 0
                for (url in fetched) {
                    if (addedNew >= MAX_PER_SEARCH) break // 默认匹配 10 条自动停止
                    if (url in merged) continue
                    merged.add(url)
                    addedNew++
                }
                _results.value = merged.take(MAX_TOTAL)
                _status.value = when {
                    _results.value.isEmpty() -> "未找到结果，换个关键词（如「苹果CMS采集」）再试"
                    addedNew == 0 -> "没有新结果，已收集 ${_results.value.size} 条"
                    else -> "已收集 ${_results.value.size} 条（本次 +$addedNew，单次满 10 条自动停止）"
                }
            } catch (e: CancellationException) {
                _status.value = "已手动停止收集（保留已收集的 ${_results.value.size} 条）"
                throw e
            } catch (t: Throwable) {
                _status.value = "搜索失败：${t.message ?: "网络或搜索服务受限"}，可换关键词重试"
            } finally {
                _searching.value = false
            }
        }
    }

    /** 手动停止收集 */
    fun stopSearch() {
        searchJob?.cancel()
    }

    fun toggle(url: String) {
        _selected.value = _selected.value.let {
            if (url in it) it - url else it + url
        }
    }

    fun selectAll() {
        _selected.value = _results.value.toSet()
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun clearResults() {
        searchJob?.cancel()
        _results.value = emptyList()
        _selected.value = emptySet()
        _status.value = null
        _notice.value = null
    }

    /** 批量添加已勾选地址：逐条探测采集端点，仅可用线路入库 */
    fun addSelected() {
        val targets = _results.value.filter { it in _selected.value }
        if (targets.isEmpty() || _adding.value) return
        viewModelScope.launch {
            _adding.value = true
            _notice.value = null
            try {
                var lastPercent = -1
                val added = tvBoxImporter.addCmsSources(targets) { done, total ->
                    val percent = if (total > 0) done * 100 / total else 100
                    if (percent != lastPercent) {
                        lastPercent = percent
                        _addProgress.value = "正在探测采集端点 $done/$total…"
                    }
                }
                val skipped = targets.size - added
                _notice.value = buildString {
                    append("已添加 $added 条可用线路")
                    if (skipped > 0) append("，$skipped 条不可用或已存在已跳过")
                    append("；可在「设置 → 源管理」查看")
                }
                if (added > 0) _selected.value = emptySet()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _notice.value = "添加失败：${t.message ?: "未知错误"}"
            } finally {
                _adding.value = false
                _addProgress.value = null
            }
        }
    }

    /**
     * DuckDuckGo Lite 抓取与链接解析：
     * `lite.duckduckgo.com/lite/?q=` 返回极简 HTML，外部链接经 `/l/?uddg=<编码后的真实地址>` 跳转，
     * 提取并解码 uddg 参数即得候选地址（无需 API Key / Cookie）。
     */
    private suspend fun fetchDuckDuckGoLite(query: String): List<String> = withContext(io) {
        val url = "https://lite.duckduckgo.com/lite/?q=" +
            URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
            )
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        }
        val out = LinkedHashSet<String>()
        for (match in UDDG_REGEX.findAll(html)) {
            val encoded = match.groupValues[1]
            val decoded = runCatching { URLDecoder.decode(encoded, "UTF-8") }
                .getOrDefault(encoded)
                .trim()
                .trimEnd('/')
            if (!decoded.startsWith("http://") && !decoded.startsWith("https://")) continue
            if (INTERNAL_HOSTS.any { decoded.contains(it) }) continue // 跳过搜索引擎自身链接
            out.add(decoded)
            if (out.size >= MAX_PER_SEARCH * 3) break // 单页解析到足够数量即可
        }
        out.toList()
    }

    companion object {
        private const val MAX_PER_SEARCH = 10
        private const val MAX_TOTAL = 50
        private val UDDG_REGEX = Regex("uddg=([^&\"'<>\\s]+)")
        private val INTERNAL_HOSTS = listOf(
            "duckduckgo.com", "google.", "bing.com", "baidu.com", "yandex.",
        )
    }
}
