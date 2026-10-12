package com.puretv.player.core.util

/** 通用字符串工具 */
object StringUtils {

    /** 从 M3U/Xtream 地址推导安全的源 ID */
    fun stableId(input: String): String = Integer.toHexString(input.trim().hashCode())

    /** 直播地址归一化（去掉首尾空白、统一大写 scheme 以外部分保持原样） */
    fun normalizeUrl(url: String): String = url.trim()

    /** 是否为网络地址 */
    fun isHttpUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("rtmp://") || lower.startsWith("rtsp://") ||
            lower.startsWith("udp://") || lower.startsWith("file://")
    }

    /** XTIMC 回看时间戳占位替换 {utc}/{lutc}/{utcend}/{lutcend}/{timestamp}/{lutcend} */
    fun applyCatchupTemplate(
        template: String,
        startAt: Long,
        endAt: Long = startAt + 3600_000L,
    ): String {
        val utc = startAt / 1000
        val utcEnd = endAt / 1000
        val now = System.currentTimeMillis() / 1000
        return template
            .replace("{utc}", utc.toString())
            .replace("{lutc}", utc.toString())
            .replace("{utcend}", utcEnd.toString())
            .replace("{lutcend}", utcEnd.toString())
            .replace("{timestamp}", utc.toString())
            .replace("{now}", now.toString())
    }
}
