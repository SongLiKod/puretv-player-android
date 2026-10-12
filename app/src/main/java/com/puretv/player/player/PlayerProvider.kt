package com.puretv.player.player

import androidx.media3.common.Player

/**
 * 播放页获取底层播放器实例的扩展接口（不修改共享的 [PlaybackEngine] 契约）。
 *
 * [PlaybackEngineImpl] 同时实现 [PlaybackEngine] 与本接口，
 * UI 层通过 `(engine as? PlayerProvider)?.player` 拿到播放器绑定 PlayerView。
 */
interface PlayerProvider {

    /** 当前播放器实例；未创建或已释放时为 null */
    val player: Player?
}
