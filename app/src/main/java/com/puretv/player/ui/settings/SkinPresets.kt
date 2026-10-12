package com.puretv.player.ui.settings

import com.puretv.player.data.prefs.AppSettings
import org.json.JSONObject

/**
 * 自定义皮肤预设（需求 5.2.3：自定义皮肤）。
 *
 * 配色禁忌（技术文档 7.3）：全局仅蓝白灰黑体系，因此自定义皮肤仅开放
 * 「主色 / 高亮色」两项取值，且限定为品牌蓝体系内的官方预设，
 * 不接受任意色值，杜绝杂色混用；应用层（PureTvTheme.applySkin）也只覆盖主色与高亮色。
 */
data class SkinPreset(
    val id: String,
    val name: String,
    val description: String,
    /** 主色（品牌主蓝体系） */
    val accent: Long,
    /** 辅助高亮色（焦点高亮） */
    val highlight: Long,
    val isDefault: Boolean = false,
) {
    /** 序列化为应用层皮肤 JSON（{"accent":"#RRGGBB","highlight":"#RRGGBB"}） */
    fun toJson(): String = JSONObject()
        .put("accent", toHex(accent))
        .put("highlight", toHex(highlight))
        .toString()

    private fun toHex(value: Long): String = String.format("#%06X", value and 0xFFFFFF)

    companion object {
        val DEFAULT = SkinPreset(
            id = "default",
            name = "品牌蓝（默认）",
            description = "深浅主题统一天蓝主色，焦点高亮柔和",
            accent = 0x3B82F6,
            highlight = 0x60A5FA,
            isDefault = true,
        )

        val DEEP_BLUE = SkinPreset(
            id = "skin_deep_blue",
            name = "深蓝",
            description = "主色更深一档，白天强光环境对比更清晰",
            accent = 0x2563EB,
            highlight = 0x60A5FA,
        )

        val NAVY = SkinPreset(
            id = "skin_navy",
            name = "靛蓝",
            description = "沉稳靛蓝，长时间观影更耐看",
            accent = 0x1D4ED8,
            highlight = 0x3B82F6,
        )

        val STEEL = SkinPreset(
            id = "skin_steel",
            name = "钢蓝",
            description = "低饱和钢蓝，弱光环境不刺眼",
            accent = 0x1E40AF,
            highlight = 0x3B82F6,
        )

        val ALL = listOf(DEFAULT, DEEP_BLUE, NAVY, STEEL)

        /** 由当前设置反查所选预设 */
        fun from(settings: AppSettings): SkinPreset {
            val custom = settings.customSkinJson
            if (custom.isNullOrBlank()) return ALL.firstOrNull { it.id == settings.skinId } ?: DEFAULT
            val preset = runCatching {
                val obj = JSONObject(custom)
                val accent = parseHex(obj.optString("accent"))
                val highlight = parseHex(obj.optString("highlight"))
                ALL.firstOrNull { it.accent == accent && it.highlight == highlight }
            }.getOrNull()
            return preset ?: ALL.firstOrNull { it.id == settings.skinId } ?: DEFAULT
        }

        private fun parseHex(value: String?): Long? {
            val v = value?.trim()?.removePrefix("#") ?: return null
            if (v.length != 6) return null
            return runCatching { v.toLong(16) }.getOrNull()
        }
    }
}
