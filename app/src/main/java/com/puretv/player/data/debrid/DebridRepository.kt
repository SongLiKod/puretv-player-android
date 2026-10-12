package com.puretv.player.data.debrid

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.puretv.player.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 拓展服务连接状态（详情页/设置页状态指示） */
enum class DebridState {
    /** 未配置 */
    NONE,
    /** 测试中 */
    TESTING,
    /** 连接正常 */
    OK,
    /** 连接失败 */
    FAILED,
}

/** 某一项拓展服务的配置 + 连接状态 */
data class DebridStatus(
    val state: DebridState = DebridState.NONE,
    val message: String? = null,
) {
    val configured: Boolean get() = state != DebridState.NONE
    val usable: Boolean get() = state == DebridState.OK
}

/**
 * 拓展服务仓库（需求 5.4.5：支持 Real-Debrid / TorBox 高级拓展，加载高清稳定资源）。
 * - Token 存 DataStore（"debrid_real_token" / "debrid_torbox_token"，与设置中心共用）
 * - resolve(url)：Real-Debrid unrestrict 取直链；磁力/种子走 TorBox 转存取直链
 * - 任何失败返回 null，由详情页降级提示「拓展服务不可用，使用普通源」（需求 6.4 稳定性）
 */
@Singleton
class DebridRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val realDebridClient: RealDebridClient,
    private val torBoxClient: TorBoxClient,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Real-Debrid Token */
    val realToken: Flow<String?> = dataStore.data.map { it[KEY_REAL] }

    /** TorBox Token */
    val torBoxToken: Flow<String?> = dataStore.data.map { it[KEY_TORBOX] }

    suspend fun realTokenValue(): String? = realToken.first()?.trim()?.takeIf { it.isNotEmpty() }

    suspend fun torBoxTokenValue(): String? = torBoxToken.first()?.trim()?.takeIf { it.isNotEmpty() }

    suspend fun setRealToken(token: String) {
        val trimmed = token.trim()
        dataStore.edit { preferences ->
            if (trimmed.isEmpty()) preferences.remove(KEY_REAL) else preferences[KEY_REAL] = trimmed
        }
    }

    suspend fun setTorBoxToken(token: String) {
        val trimmed = token.trim()
        dataStore.edit { preferences ->
            if (trimmed.isEmpty()) preferences.remove(KEY_TORBOX) else preferences[KEY_TORBOX] = trimmed
        }
    }

    /** 是否已配置任一拓展服务 */
    suspend fun hasAnyToken(): Boolean = realTokenValue() != null || torBoxTokenValue() != null

    /**
     * 解析高清直链：
     * 1. Real-Debrid：unrestrict/link（已缓存资源取直链）
     * 2. TorBox：磁力/种子链接转存后 requestdl 取直链
     * 失败返回 null（降级普通源，不阻塞播放）。
     */
    suspend fun resolve(url: String): String? = withContext(io) {
        val link = url.trim()
        if (link.isEmpty()) return@withContext null
        realTokenValue()?.let { token ->
            try {
                realDebridClient.unrestrict(token, link)?.let { return@withContext it }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // 降级 TorBox / 返回 null
            }
        }
        if (link.startsWith("magnet:", ignoreCase = true) || link.endsWith(".torrent", ignoreCase = true)) {
            torBoxTokenValue()?.let { token ->
                try {
                    resolveViaTorBox(token, link)?.let { return@withContext it }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // 降级返回 null
                }
            }
        }
        null
    }

    /** TorBox 转存链路：createtorrent -> 轮询状态 -> requestdl */
    private suspend fun resolveViaTorBox(token: String, link: String): String? {
        val torrentId = torBoxClient.createTorrent(token, link) ?: return null
        repeat(TORBOX_POLL_TIMES) { attempt ->
            val info = torBoxClient.torrentInfo(token, torrentId)
            val state = info?.downloadState
            when {
                state != null && state in TorBoxClient.FINISHED_STATES -> {
                    val fileId = info.files.orEmpty().maxByOrNull { it.size ?: 0L }?.id
                    torBoxClient.requestDirectLink(token, torrentId, fileId)?.let { return it }
                    return torBoxClient.requestDirectLink(token, torrentId, null)
                }
                state == "error" || state == "dead" -> return null
            }
            if (attempt < TORBOX_POLL_TIMES - 1) delay(TORBOX_POLL_INTERVAL_MS)
        }
        return null
    }

    /** 测试 Real-Debrid 连接（设置中心「测试连接」按钮） */
    suspend fun testReal(): DebridStatus = withContext(io) {
        val token = realTokenValue() ?: return@withContext DebridStatus(DebridState.NONE, "未配置 Real-Debrid Token")
        if (realDebridClient.testToken(token)) {
            DebridStatus(DebridState.OK, "Real-Debrid 连接正常")
        } else {
            DebridStatus(DebridState.FAILED, "Real-Debrid 连接失败，请检查 Token")
        }
    }

    /** 测试 TorBox 连接 */
    suspend fun testTorBox(): DebridStatus = withContext(io) {
        val token = torBoxTokenValue() ?: return@withContext DebridStatus(DebridState.NONE, "未配置 TorBox Token")
        if (torBoxClient.checkToken(token)) {
            DebridStatus(DebridState.OK, "TorBox 连接正常")
        } else {
            DebridStatus(DebridState.FAILED, "TorBox 连接失败，请检查 Token")
        }
    }

    companion object {
        /** 与设置中心共用的 Token 存储键（勿改） */
        val KEY_REAL: Preferences.Key<String> = stringPreferencesKey("debrid_real_token")
        val KEY_TORBOX: Preferences.Key<String> = stringPreferencesKey("debrid_torbox_token")

        /** TorBox 转存轮询参数 */
        private const val TORBOX_POLL_TIMES = 10
        private const val TORBOX_POLL_INTERVAL_MS = 1_500L
    }
}
