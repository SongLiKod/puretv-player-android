package com.puretv.player.player

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/**
 * 渲染器工厂（需求 5.5.1 格式兼容 / 5.5.2 4K、HEVC 硬件加速解码）：
 *
 * 1. 开启解码器回退（[setEnableDecoderFallback]），硬解不可用时自动回退，避免花屏/崩溃；
 * 2. 关闭扩展渲染器（FFmpeg 等 extension renderer），统一走系统 MediaCodec；
 * 3. 自定义 [MediaCodecSelector]：默认「硬件解码器优先，软件解码器（c2.android.* / omx.google.*）殿后」，
 *    设置中心关闭「硬件加速解码」时反转为软件优先（软解兜底，低配盒子可控功耗）。
 *
 * MP4/MKV/TS/FLV 走 Media3 默认 Extractors，HLS/DASH 走已依赖的 media3-exoplayer-hls/dash 模块，
 * 由 URI 后缀（.m3u8/.mpd）自动识别（见 [PlaybackEngineImpl] 的 MediaSourceFactory 装配）。
 */
class PlaybackRenderersFactory(
    context: Context,
    hardwareFirst: Boolean,
) : DefaultRenderersFactory(context) {

    init {
        setEnableDecoderFallback(true)
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_OFF)
        setMediaCodecSelector(HardwarePrioritySelector(hardwareFirst))
    }
}

/**
 * 硬件解码器优先的编解码器选择器：按「硬件组 / 软件组」稳定分组，
 * 组内保持 Media3 默认排序（按支持度与厂商优先级）。
 */
private class HardwarePrioritySelector(
    private val hardwareFirst: Boolean,
) : MediaCodecSelector {

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val infos = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        if (infos.size <= 1) return infos
        val software = ArrayList<MediaCodecInfo>(infos.size)
        val hardware = ArrayList<MediaCodecInfo>(infos.size)
        for (info in infos) {
            if (isSoftware(info)) software.add(info) else hardware.add(info)
        }
        if (software.isEmpty() || hardware.isEmpty()) return infos
        return if (hardwareFirst) hardware + software else software + hardware
    }

    /** 软件解码器判定：codec 自带标记 或 c2.android.* / omx.google.* 前缀 */
    private fun isSoftware(info: MediaCodecInfo): Boolean =
        info.softwareOnly ||
            info.name.startsWith("c2.android.", ignoreCase = true) ||
            info.name.startsWith("omx.google.", ignoreCase = true)
}
