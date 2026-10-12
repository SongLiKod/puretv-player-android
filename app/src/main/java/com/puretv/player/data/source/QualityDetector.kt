package com.puretv.player.data.source

/**
 * 频道清晰度启发式识别（需求 5.3.3：清晰度记忆）。
 * M3U 未显式提供 quality 属性、Xtream 仅有频道名时，从名称推断清晰度标签。
 */
internal object QualityDetector {

    /** 从频道名推断清晰度（HD/FHD/4K/2160/1080/720/SD），无法判断返回 null */
    fun detect(name: String): String? {
        val upper = name.uppercase()
        return when {
            upper.contains("4K") || upper.contains("2160") || upper.contains("UHD") -> "4K"
            upper.contains("FHD") || upper.contains("1080") -> "FHD"
            upper.contains("HD") || upper.contains("720") || upper.contains("H265") ||
                upper.contains("H.265") || upper.contains("HEVC") -> "HD"
            upper.contains("SD") || upper.contains("576") || upper.contains("480") -> "SD"
            else -> null
        }
    }
}
