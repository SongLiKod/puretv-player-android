package com.puretv.player.data.repository

import android.net.Uri
import com.puretv.player.data.local.entity.SourceEntity
import com.puretv.player.data.model.ImportProgress
import com.puretv.player.data.model.SourceCheckReport
import kotlinx.coroutines.flow.Flow

/**
 * 直播源仓库（接口，保证「设置中心/新手引导」与「直播模块」解耦）：
 * - M3U / Xtream 主流协议导入（需求 5.3.1）
 * - 大容量频道列表带进度解析（需求 5.3.2）
 * - 源检测工具（需求 5.6.4）
 */
interface SourceRepository {

    /** 全部直播源 */
    val sources: Flow<List<SourceEntity>>

    suspend fun getSource(id: String): SourceEntity?

    /**
     * 导入 M3U 源（网络地址）
     * @param onProgress 解析进度回调（在 IO 协程中调用，主线程可安全转发）
     */
    suspend fun importM3u(
        name: String,
        url: String,
        epgUrl: String? = null,
        onProgress: (ImportProgress) -> Unit = {},
    ): SourceEntity

    /** 从本地文件导入 M3U 源（SAF 文档选择器返回的 Uri） */
    suspend fun importM3uFromFile(
        name: String,
        uri: Uri,
        epgUrl: String? = null,
        onProgress: (ImportProgress) -> Unit = {},
    ): SourceEntity

    /** 导入 Xtream Codes 源（需求 5.3.1） */
    suspend fun importXtream(
        name: String,
        baseUrl: String,
        username: String,
        password: String,
        epgUrl: String? = null,
        onProgress: (ImportProgress) -> Unit = {},
    ): SourceEntity

    /** 刷新（重新拉取）指定源 */
    suspend fun refresh(sourceId: String, onProgress: (ImportProgress) -> Unit = {})

    suspend fun removeSource(sourceId: String)

    suspend fun setSourceEnabled(sourceId: String, enabled: Boolean)

    /** 源检测：检测已保存源的可用性、频道数、EPG、抽样播放地址 */
    suspend fun checkSource(sourceId: String): SourceCheckReport

    /** 源检测：检测任意地址（设置中心/引导页输入的临时地址） */
    suspend fun checkUrl(
        url: String,
        username: String? = null,
        password: String? = null,
    ): SourceCheckReport
}
