package com.puretv.player.data.plugin

import com.squareup.moshi.JsonClass

/**
 * 官方插件目录（assets/plugins/catalog.json）数据结构。
 * 仅承载官方审核上架（official=true）的插件条目（需求 5.7.2：杜绝恶意插件）。
 */
@JsonClass(generateAdapter = true)
data class PluginCatalogDto(
    val schemaVersion: Int = 1,
    val updatedAt: Long = 0L,
    /** 目录整体摘要（全部条目 sha256 的二次汇总），用于目录一致性校验 */
    val checksum: String = "",
    val note: String = "",
    val plugins: List<PluginEntryDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class PluginEntryDto(
    val id: String,
    val name: String,
    val description: String = "",
    /** 插件类型：subtitle 字幕增强 / source 资源聚合 / skin 界面美化 */
    val type: String,
    val version: String = "1.0.0",
    /** 是否官方审核上架 */
    val official: Boolean = false,
    /** 插件描述串（id|type|version|name|description）的 SHA-256 摘要 */
    val sha256: String = "",
)

/** 插件类型常量（需求 5.7.1：字幕插件、资源插件、美化插件；追加：播放增强插件） */
object PluginType {
    const val SUBTITLE = "subtitle"
    const val SOURCE = "source"
    const val SKIN = "skin"
    const val PLAYBACK = "playback"

    val ALL = listOf(SUBTITLE, SOURCE, SKIN, PLAYBACK)

    fun label(type: String): String = when (type) {
        SUBTITLE -> "字幕增强"
        SOURCE -> "资源聚合"
        SKIN -> "界面美化"
        PLAYBACK -> "播放增强"
        else -> "其他"
    }
}

/** 插件操作异常（校验未通过 / 非官方插件等） */
class PluginException(message: String) : Exception(message)
