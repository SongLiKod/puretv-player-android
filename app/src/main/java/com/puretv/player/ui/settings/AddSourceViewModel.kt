package com.puretv.player.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretv.player.core.util.StringUtils
import com.puretv.player.data.local.dao.SourceDao
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject

/** 搜索结果候选（用户要求：结果必须先验证、可用才能加入） */
data class SearchCandidate(
    /** 搜索得到的原始地址（展示用） */
    val url: String,
    /** 候选类型：MacCMS 采集站 / TVBox 配置文件 */
    val kind: Kind = Kind.CMS,
    /** 验证状态：检测中 / 可用 / 不可用 */
    val status: Status = Status.TESTING,
    /** 验证通过后的可用地址（采集端点 / 配置直链），添加时用它而非原始地址 */
    val resolvedUrl: String? = null,
    /** 验证结果摘要（如「MacCMS 采集站 · 1254 部」） */
    val detail: String? = null,
) {
    enum class Kind { CMS, TVBOX }
    enum class Status { TESTING, OK, FAIL }
}

/**
 * 在线搜索添加线路（设置 → 在线搜索线路）。
 *
 * 用户反馈：旧版直接把搜索结果链接原样返回、加入时才探测，导致「结果完全不能用、
 * 每次搜索都一样、点击加入都是失败」。现改为「先验证、后加入」链路：
 *
 * 1. **搜索通道**（均国内直连可达）：
 *    - 必应 RSS（`cn.bing.com/search?q=..&format=rss`）：返回与关键词严格相关的
 *      `<item><link>` 真实结果地址，杜绝 HTML 页面里抓到的站点导航等固定链接（旧版「每次一样」的根因）；
 *    - GitHub（`api.github.com/search/repositories`）：搜索 TVBox 配置仓库，
 *      仓库首页地址作为采集站候选，仓库根目录下的 .json 配置经 contents API 还原为
 *      raw.githubusercontent 直链作为 TVBox 配置候选。
 * 2. **自动验证**：采集站候选按主机推导 `api.php/provide/vod` 端点（https/http 双试）
 *    并确认响应为 MacCMS 采集 JSON/XML（含片源总数）；配置候选下载后确认为含
 *    `sites`/`lives` 的 TVBox JSON。3 路并发，结果实时回填。
 * 3. **加入**：仅「可用」项可勾选；添加时用验证过的端点/直链——采集站走
 *    [TvBoxConfigImporter.addCmsSources] 入库，TVBox 配置走 [TvBoxConfigImporter.importFromUrl]
 *    真实解析导入（聚合线路 + 直播源）。
 */
@HiltViewModel
class AddSourceViewModel @Inject constructor(
    private val client: OkHttpClient,
    private val tvBoxImporter: TvBoxConfigImporter,
    private val sourceDao: SourceDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    /** 推荐搜索词：MacCMS 采集协议特征词 + TVBox 配置仓库高频词 */
    val recommendedKeywords: List<String> = listOf(
        "苹果CMS采集接口",
        "api.php/provide/vod",
        "MacCMS 采集站",
        "tvbox 配置",
        "tvbox 接口",
        "影视采集线路 m3u8",
    )

    private val _candidates = MutableStateFlow<List<SearchCandidate>>(emptyList())
    val candidates: StateFlow<List<SearchCandidate>> = _candidates.asStateFlow()

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

    /**
     * 搜索：必应 RSS + GitHub 双通道取候选 → 自动验证 → 可用项自动勾选。
     *
     * - **自动补搜**：一轮搜完后若「验证通过且不在系统中」的可用线路不足 [TARGET_USABLE] 条，
     *   自动换衍生关键词继续搜（最多 [MAX_ROUNDS] 轮或连续两轮无新候选即停）——
     *   用户要求「直到找到 5 条正确的线路」；
     * - **已在系统的不显示**：候选验证通过后，其可用地址的源 id（[StringUtils.stableId]）
     *   已存在于源库即从结果中剔除，避免重复添加已有的线路。
     */
    fun search(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty() || _searching.value || _adding.value) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _searching.value = true
            _notice.value = null
            try {
                val queue = buildSearchQueue(query)
                var idleRounds = 0
                for ((round, keyword) in queue.withIndex()) {
                    _status.value =
                        if (round == 0) "正在搜索「$keyword」…（必应 + GitHub）"
                        else "可用线路不足 $TARGET_USABLE 条，继续搜索「$keyword」…"
                    val fetched = withContext(io) { searchAll(keyword) }
                    val existingUrls = _candidates.value.map { it.url }.toSet()
                    val systemIds = systemSourceIds()
                    val fresh = fetched
                        .filter { (url, _) ->
                            url !in existingUrls &&
                                StringUtils.stableId(url.trimEnd('/')) !in systemIds
                        }
                        .take(MAX_PER_SEARCH)
                        .map { (url, kind) -> SearchCandidate(url = url, kind = kind) }
                    if (fresh.isNotEmpty()) {
                        _candidates.value = (_candidates.value + fresh).take(MAX_TOTAL)
                        idleRounds = 0
                    } else {
                        idleRounds++
                    }
                    val pending = _candidates.value.filter { it.status == SearchCandidate.Status.TESTING }
                    if (pending.isNotEmpty()) {
                        if (fresh.isNotEmpty()) {
                            _status.value = "已获取 ${fresh.size} 条候选，正在验证可用性…"
                        }
                        verifyAll(pending)
                    }
                    // 验证完成后剔除「已在系统中」的可用线路（用户要求：已有的不再显示）
                    pruneInSystem()
                    if (usableCount() >= TARGET_USABLE) break
                    if (idleRounds >= 2) break // 连续两轮搜不到新候选，提前结束
                }
                val ok = usableCount()
                _status.value = if (ok >= TARGET_USABLE) {
                    "搜索完成：找到 $ok 条可用线路（已自动勾选，均不在系统中）"
                } else {
                    "搜索完成：找到 $ok 条可用线路，可换关键词继续搜索"
                }
            } catch (e: CancellationException) {
                _status.value = "已停止搜索（保留已收集的 ${_candidates.value.size} 条）"
                throw e
            } catch (t: Throwable) {
                _status.value = "搜索失败：${t.message ?: "网络受限"}，可换关键词或稍后重试"
            } finally {
                _searching.value = false
            }
        }
    }

    /** 衍生关键词队列：原词优先，不足时按「接口 / 采集站 / tvbox」补搜 */
    private fun buildSearchQueue(query: String): List<String> = listOfNotNull(
        query,
        "$query 接口".takeIf { !query.contains("接口") },
        "$query 采集站".takeIf { !query.contains("采集站") },
        "tvbox $query".takeIf { !query.contains("tvbox", ignoreCase = true) },
    ).take(MAX_ROUNDS)

    /** 系统现有源 id 集合（内置线路与已添加线路的 id 均为 stableId(url)） */
    private suspend fun systemSourceIds(): Set<String> = withContext(io) {
        runCatching { sourceDao.getAll().map { it.id }.toSet() }.getOrDefault(emptySet())
    }

    /** 剔除已存在于系统源库的可用线路（验证通过后按解析出的可用地址判定） */
    private suspend fun pruneInSystem() {
        val systemIds = systemSourceIds()
        val kept = _candidates.value.filter { candidate ->
            val resolved = candidate.resolvedUrl
            resolved == null || StringUtils.stableId(resolved) !in systemIds
        }
        if (kept.size != _candidates.value.size) {
            _candidates.value = kept
            val urls = kept.map { it.url }.toSet()
            _selected.value = _selected.value intersect urls
        }
    }

    /** 当前「可用且不在系统中」的线路数（自动补搜的目标计数） */
    private fun usableCount(): Int =
        _candidates.value.count { it.status == SearchCandidate.Status.OK }

    /** 手动停止（取消搜索与验证） */
    fun stopSearch() {
        searchJob?.cancel()
    }

    /** 勾选/取消（仅「可用」项可勾选） */
    fun toggle(candidate: SearchCandidate) {
        if (candidate.status != SearchCandidate.Status.OK) return
        val url = candidate.url
        _selected.value = _selected.value.let { if (url in it) it - url else it + url }
    }

    /** 全选（仅「可用」项） */
    fun selectAll() {
        _selected.value = _candidates.value
            .filter { it.status == SearchCandidate.Status.OK }
            .map { it.url }
            .toSet()
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun clearResults() {
        searchJob?.cancel()
        _candidates.value = emptyList()
        _selected.value = emptySet()
        _status.value = null
        _notice.value = null
    }

    /**
     * 批量添加已勾选项：只处理「可用」项，用验证过的地址真实入库——
     * 采集站 → [TvBoxConfigImporter.addCmsSources]；TVBox 配置 → [TvBoxConfigImporter.importFromUrl]。
     */
    fun addSelected() {
        val okRows = _candidates.value.filter {
            it.url in _selected.value && it.status == SearchCandidate.Status.OK
        }
        if (okRows.isEmpty() || _adding.value) return
        viewModelScope.launch {
            _adding.value = true
            _notice.value = null
            try {
                var added = 0
                val cms = okRows.filter { it.kind == SearchCandidate.Kind.CMS }
                    .mapNotNull { it.resolvedUrl }
                if (cms.isNotEmpty()) {
                    var lastPercent = -1
                    added = tvBoxImporter.addCmsSources(cms) { done, total ->
                        val percent = if (total > 0) done * 100 / total else 100
                        if (percent != lastPercent) {
                            lastPercent = percent
                            _addProgress.value = "正在添加采集线路 $done/$total…"
                        }
                    }
                }
                var tvCms = 0
                var tvLives = 0
                var tvFailed = 0
                val tvConfigs = okRows.filter { it.kind == SearchCandidate.Kind.TVBOX }
                    .mapNotNull { it.resolvedUrl }
                tvConfigs.forEachIndexed { index, url ->
                    _addProgress.value = "正在导入 TVBox 配置 ${index + 1}/${tvConfigs.size}…"
                    try {
                        val summary = tvBoxImporter.importFromUrl(url)
                        tvCms += summary.cmsImported
                        tvLives += summary.livesImported
                        if (summary.cmsImported + summary.livesImported == 0) tvFailed++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        tvFailed++
                    }
                }
                _notice.value = buildString {
                    append("已添加采集线路 $added 条")
                    if (tvConfigs.isNotEmpty()) {
                        append("；TVBox 配置导入聚合线路 $tvCms 条、直播源 $tvLives 条")
                        if (tvFailed > 0) append("（$tvFailed 个配置无可用内容或失败）")
                    }
                    append("；可在「设置 → 源管理」查看")
                }
                // 已添加项移出勾选
                val addedUrls = okRows.map { it.url }.toSet()
                _selected.value = _selected.value - addedUrls
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

    /* ============================== 搜索通道 ============================== */

    /** 双通道搜索：必应 RSS（网页结果）+ GitHub（TVBox 配置仓库），合并返回 */
    private suspend fun searchAll(query: String): List<Pair<String, SearchCandidate.Kind>> {
        val bing = runCatching { searchBingRss(query) }.getOrDefault(emptyList())
            .map { it to SearchCandidate.Kind.CMS }
        val github = runCatching { searchGithub(query) }.getOrDefault(emptyList())
        return bing + github
    }

    /**
     * 必应 RSS：`&format=rss` 返回标准订阅 XML，`<item><link>` 即真实结果地址，
     * 与关键词严格相关（旧版抓 HTML 把页面导航/推荐位固定链接当成结果，导致「每次搜索都一样」）。
     */
    private suspend fun searchBingRss(query: String): List<String> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        for (host in listOf("cn.bing.com", "www.bing.com")) {
            val xml = runCatching {
                fetchText("https://$host/search?q=$encoded&format=rss")
            }.getOrDefault("")
            if (xml.isEmpty()) continue
            val out = LinkedHashSet<String>()
            for (match in RSS_ITEM_REGEX.findAll(xml)) {
                val url = match.groupValues[1].trim()
                if (isUsableWebResult(url)) out.add(url)
                if (out.size >= MAX_PER_SEARCH * 2) break
            }
            if (out.isNotEmpty()) return out.toList()
        }
        return emptyList()
    }

    /**
     * GitHub 搜索：仓库搜索 API（无需鉴权）→
     * - 仓库 `homepage`（多为演示资源站地址）→ 采集站候选；
     * - 仓库根目录 contents API 找 .json 配置 → raw 直链 → TVBox 配置候选。
     */
    private suspend fun searchGithub(query: String): List<Pair<String, SearchCandidate.Kind>> {
        val out = LinkedHashSet<Pair<String, SearchCandidate.Kind>>()
        val json = fetchText(
            url = "https://api.github.com/search/repositories?q=" +
                URLEncoder.encode(query, "UTF-8") + "&per_page=6",
            accept = "application/vnd.github+json",
        )
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val items = root.optJSONArray("items") ?: return emptyList()
        var contentsQuota = GITHUB_CONTENTS_LIMIT
        for (i in 0 until items.length()) {
            val repo = items.optJSONObject(i) ?: continue
            if (repo.optBoolean("fork")) continue
            val fullName = repo.optString("full_name")
            if (fullName.isBlank() || fullName == "null") continue
            // 仓库首页 → 采集站候选（后续验证按主机推导采集端点）
            val homepage = repo.optString("homepage").trim()
            if (homepage.startsWith("http")) {
                out.add(homepage.trimEnd('/') to SearchCandidate.Kind.CMS)
            }
            // 仓库根目录 .json 配置 → TVBox 配置候选（限流：每次搜索最多扫 4 个仓库）
            if (contentsQuota > 0) {
                contentsQuota--
                val branch = repo.optString("default_branch").ifBlank { "main" }
                val contents = runCatching {
                    fetchText(
                        url = "https://api.github.com/repos/$fullName/contents/",
                        accept = "application/vnd.github+json",
                    )
                }.getOrDefault("")
                parseConfigUrls(contents, fullName, branch).forEach { raw ->
                    out.add(raw to SearchCandidate.Kind.TVBOX)
                }
                if (out.size >= MAX_PER_SEARCH * 2) break
            }
        }
        return out.toList().take(MAX_PER_SEARCH)
    }

    /** contents API 响应里挑出根目录 .json 配置文件的 raw 直链（排除依赖清单等非线路配置） */
    private fun parseConfigUrls(contentsJson: String, fullName: String, branch: String): List<String> {
        val arr = runCatching { org.json.JSONArray(contentsJson) }.getOrNull() ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val file = arr.optJSONObject(i) ?: continue
            if (file.optString("type") != "file") continue
            val name = file.optString("name").lowercase()
            if (!name.endsWith(".json") || name in SKIP_JSON_NAMES) continue
            val download = file.optString("download_url").ifBlank {
                "https://raw.githubusercontent.com/$fullName/$branch/${file.optString("name")}"
            }
            out.add(download)
            if (out.size >= MAX_CONFIGS_PER_REPO) break
        }
        return out
    }

    /* ============================== 候选验证 ============================== */

    /** 3 路并发验证全部「检测中」候选，逐条回填状态 */
    private suspend fun verifyAll(pending: List<SearchCandidate>) {
        val semaphore = Semaphore(VERIFY_CONCURRENCY)
        kotlinx.coroutines.coroutineScope {
            pending.forEach { candidate ->
                launch {
                    semaphore.withPermit {
                        val result = withContext(io) { verify(candidate) }
                        updateCandidate(result)
                    }
                }
            }
        }
    }

    /** 回填验证结果并自动勾选可用项 */
    private fun updateCandidate(result: SearchCandidate) {
        _candidates.value = _candidates.value.map { if (it.url == result.url) result else it }
        if (result.status == SearchCandidate.Status.OK) {
            _selected.value = _selected.value + result.url
        }
    }

    private suspend fun verify(candidate: SearchCandidate): SearchCandidate =
        if (candidate.kind == SearchCandidate.Kind.TVBOX) verifyTvBox(candidate) else verifyCms(candidate)

    /**
     * 采集站验证：候选地址按主机推导 `api.php/provide/vod` 端点（https/http 双试），
     * 响应须为 MacCMS 采集 JSON（含 list/vod 字段）或 RSS XML（<rss>/<list>），
     * 并解析片源总数；原始地址本身已是采集端点时直接探测。
     */
    private fun verifyCms(candidate: SearchCandidate): SearchCandidate {
        val base = candidate.url.trim().trimEnd('/')
        val host = hostOf(base)
        if (host.isBlank()) return candidate.copy(status = SearchCandidate.Status.FAIL, detail = "地址无效")
        val endpoints = if (base.contains("provide/vod")) {
            listOf(base)
        } else {
            listOf("https://$host/api.php/provide/vod?ac=list", "http://$host/api.php/provide/vod?ac=list")
        }
        for (endpoint in endpoints) {
            val count = probeCms(endpoint) ?: continue
            return candidate.copy(
                status = SearchCandidate.Status.OK,
                resolvedUrl = endpoint,
                detail = if (count > 0) "MacCMS 采集站 · $count 部" else "MacCMS 采集站",
            )
        }
        return candidate.copy(status = SearchCandidate.Status.FAIL, detail = "未发现可用采集接口")
    }

    /** 探测采集端点：HTTP 200 且响应为采集 JSON/XML 时返回片源总数（未知为 0），否则 null */
    private fun probeCms(url: String): Int? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json, application/xml, text/xml, */*")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string()?.take(65_536).orEmpty()
            val isJson = body.contains("\"code\"") && (body.contains("\"list\"") || body.contains("\"vod\""))
            val isXml = body.contains("<rss") || body.contains("<list")
            if (!isJson && !isXml) return null
            val total = TOTAL_JSON_REGEX.find(body)?.groupValues?.get(1)?.toIntOrNull()
                ?: TOTAL_XML_REGEX.find(body)?.groupValues?.get(1)?.toIntOrNull()
                ?: 0
            total.coerceAtLeast(0)
        }
    } catch (t: Throwable) {
        null
    }

    /** TVBox 配置验证：下载内容须为含 sites / lives 的 JSON，统计站点数 */
    private fun verifyTvBox(candidate: SearchCandidate): SearchCandidate {
        val body = try {
            val request = Request.Builder()
                .url(candidate.url)
                .header("User-Agent", UA)
                .header("Accept", "application/json, */*")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return candidate.copy(status = SearchCandidate.Status.FAIL, detail = "HTTP ${response.code}")
                response.body?.string()?.take(MAX_CONFIG_BYTES).orEmpty()
            }
        } catch (t: Throwable) {
            return candidate.copy(status = SearchCandidate.Status.FAIL, detail = "下载失败")
        }
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return candidate.copy(status = SearchCandidate.Status.FAIL, detail = "不是 TVBox JSON 配置")
        val sites = root.optJSONArray("sites")?.length() ?: 0
        val lives = root.optJSONArray("lives")?.length() ?: 0
        if (sites == 0 && lives == 0) {
            return candidate.copy(status = SearchCandidate.Status.FAIL, detail = "配置内无可用线路")
        }
        return candidate.copy(
            status = SearchCandidate.Status.OK,
            resolvedUrl = candidate.url,
            detail = "TVBox 配置 · ${sites} 站点" + if (lives > 0) " · $lives 直播" else "",
        )
    }

    /* ============================== 工具 ============================== */

    private suspend fun fetchText(url: String, accept: String = "text/html,application/xml,*/*"): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", accept)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    /** 网页结果可用性：http(s) 且非搜索引擎自身/内容农场域名（文章页无法作为线路） */
    private fun isUsableWebResult(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        if (JUNK_HOSTS.any { lower.contains(it) }) return false
        val path = lower.substringBefore('?').substringBefore('#')
        return SKIP_SUFFIXES.none { path.endsWith(it) }
    }

    /** 取地址主机名（去 www. 前缀） */
    private fun hostOf(url: String): String = try {
        java.net.URI(url).host?.removePrefix("www.")?.trim().orEmpty()
    } catch (t: Throwable) {
        ""
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        private const val MAX_PER_SEARCH = 10
        private const val MAX_TOTAL = 50
        private const val VERIFY_CONCURRENCY = 3
        private const val TARGET_USABLE = 5
        private const val MAX_ROUNDS = 4
        private const val GITHUB_CONTENTS_LIMIT = 4
        private const val MAX_CONFIGS_PER_REPO = 3
        private const val MAX_CONFIG_BYTES = 400_000

        /** RSS `<item>` 块内的第一个 `<link>` 即结果地址 */
        private val RSS_ITEM_REGEX = Regex("<item>[\\s\\S]*?<link>([^<]+)</link>")
        private val TOTAL_JSON_REGEX = Regex("\"total\"\\s*:\\s*(\\d+)")
        private val TOTAL_XML_REGEX = Regex("count=\"(\\d+)\"")

        /** 非线路配置的常见 .json（GitHub 仓库根目录噪声） */
        private val SKIP_JSON_NAMES = setOf(
            "package.json", "package-lock.json", "composer.json", "tsconfig.json",
            "renovate.json", ".gitignore", "license.json",
        )

        /** 搜索引擎自身与内容农场（文章页，无法作为线路地址） */
        private val JUNK_HOSTS = listOf(
            "bing.com", "microsoft.com", "msn.com", "github.com",
            "baidu.com", "bdstatic.com", "bdimg.com", "comate.baidu",
            "duckduckgo.com", "google.", "yandex.",
            "csdn.net", "jianshu.com", "zhihu.com", "juejin.cn", "cnblogs.com",
            "cloud.tencent.com", "51cto.com", "segmentfault.com",
            "baijiahao.baidu.com", "zhidao.baidu.com", "download.csdn",
        )

        /** 静态资源后缀（结果里混入的附件直链无意义） */
        private val SKIP_SUFFIXES = listOf(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg", ".ico",
            ".mp4", ".zip", ".rar", ".7z", ".apk", ".css", ".js",
        )
    }
}
