package com.puretv.player.ui.settings

import com.puretv.player.data.prefs.AppSettings
import org.json.JSONObject

/**
 * 自定义皮肤预设（需求 5.2.3：自定义皮肤）。
 *
 * 应用层（PureTvTheme.applySkin）只覆盖「主色 / 高亮色」两项取值，
 * 其余（背景/文字/卡片）保持全局深浅主题体系不变；
 * 追加需求：除品牌蓝体系外，额外提供青碧/翡翠/紫罗兰/暖阳橙/绯红等多种风格配色。
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
    /** 内置皮肤（常驻设置列表）；false = 皮肤插件，安装后才在设置出现、卸载即消失 */
    val builtIn: Boolean = false,
    /** 皮肤托管字号（如长辈模式 1.3）：应用该皮肤时联动写入字体缩放，换回无字号皮肤时还原 1.0 */
    val fontScale: Float? = null,
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
            builtIn = true,
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
            builtIn = true,
        )

        val STEEL = SkinPreset(
            id = "skin_steel",
            name = "钢蓝",
            description = "低饱和钢蓝，弱光环境不刺眼",
            accent = 0x1E40AF,
            highlight = 0x3B82F6,
            builtIn = true,
        )

        // —— 多风格配色（追加需求：增加不同风格颜色的主题皮肤）——

        val TEAL = SkinPreset(
            id = "skin_teal",
            name = "青碧",
            description = "松石青碧主色，清新明快不刺眼",
            accent = 0x0D9488,
            highlight = 0x2DD4BF,
        )

        val EMERALD = SkinPreset(
            id = "skin_emerald",
            name = "翡翠",
            description = "深翡翠绿，暗色界面下沉稳有质感",
            accent = 0x047857,
            highlight = 0x34D399,
        )

        val VIOLET = SkinPreset(
            id = "skin_violet",
            name = "紫罗兰",
            description = "优雅紫罗兰，焦点高亮醒目",
            accent = 0x7C3AED,
            highlight = 0xA78BFA,
        )

        val AMBER = SkinPreset(
            id = "skin_amber",
            name = "暖阳橙",
            description = "暖阳活力橙，夜间观影更温暖",
            accent = 0xD97706,
            highlight = 0xFBBF24,
        )

        val ROSE = SkinPreset(
            id = "skin_rose",
            name = "绯红",
            description = "玫红点缀，个性鲜明不失克制",
            accent = 0xE11D48,
            highlight = 0xFB7185,
        )

        // —— 第二批皮肤插件（插件市场「界面美化」上架）——

        val MONO = SkinPreset(
            id = "skin_mono",
            name = "无彩银灰",
            description = "单色银灰主色，极简纯净，纯黑背景更沉浸",
            accent = 0x64748B,
            highlight = 0x94A3B8,
        )

        val MIST = SkinPreset(
            id = "skin_mist",
            name = "晨雾蓝",
            description = "晨雾浅蓝，轻盈通透，白天观感清爽",
            accent = 0x0EA5E9,
            highlight = 0x7DD3FC,
        )

        val GOLD = SkinPreset(
            id = "skin_gold",
            name = "鎏金",
            description = "鎏金暖色，低调华贵，暗色界面更显档次",
            accent = 0xA16207,
            highlight = 0xEAB308,
        )

        val CRIMSON = SkinPreset(
            id = "skin_crimson",
            name = "朱砂",
            description = "朱砂正红，热烈醒目，焦点定位一眼可见",
            accent = 0xDC2626,
            highlight = 0xF87171,
        )

        /** 长辈模式：高对比主色 + 字号联动 130%（安装/启用即放大字号，卸载还原） */
        val ELDER = SkinPreset(
            id = "skin_elder",
            name = "长辈大字",
            description = "高对比主色并放大字号至 130%，长辈易读",
            accent = 0x2563EB,
            highlight = 0x60A5FA,
            fontScale = 1.3f,
        )

        val ALL = listOf(
            DEFAULT, DEEP_BLUE, NAVY, STEEL, TEAL, EMERALD, VIOLET, AMBER, ROSE,
            MONO, MIST, GOLD, CRIMSON, ELDER,
        )

        /** 由当前设置反查所选预设（skinId 优先——长辈/深蓝等同配色以 id 为准，色值匹配仅兜底） */
        fun from(settings: AppSettings): SkinPreset {
            ALL.firstOrNull { it.id == settings.skinId }?.let { return it }
            val custom = settings.customSkinJson
            if (custom.isNullOrBlank()) return DEFAULT
            val preset = runCatching {
                val obj = JSONObject(custom)
                val accent = parseHex(obj.optString("accent"))
                val highlight = parseHex(obj.optString("highlight"))
                ALL.firstOrNull { it.accent == accent && it.highlight == highlight }
            }.getOrNull()
            return preset ?: DEFAULT
        }

        private fun parseHex(value: String?): Long? {
            val v = value?.trim()?.removePrefix("#") ?: return null
            if (v.length != 6) return null
            return runCatching { v.toLong(16) }.getOrNull()
        }
    }
}
