package com.puretv.player.di

import com.puretv.player.player.PlaybackEngine
import com.puretv.player.player.PlaybackEngineImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 播放内核装配：ExoPlayer(Media3) 实现以 @Singleton 绑定到 [PlaybackEngine] 契约。
 * 实现类同时提供 [com.puretv.player.player.PlayerProvider]，供播放页获取底层 Player 绑定 PlayerView。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlayerModule {

    @Binds
    @Singleton
    abstract fun bindPlaybackEngine(impl: PlaybackEngineImpl): PlaybackEngine
}
