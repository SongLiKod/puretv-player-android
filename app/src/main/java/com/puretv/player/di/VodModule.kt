package com.puretv.player.di

import com.puretv.player.data.vod.TmdbClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton

/** TMDB 刮削专用网络客户端限定符（5 秒短超时，网络不可达时快速降级） */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TmdbOkHttp

/**
 * 影视点播模块装配（需求 5.4）：
 * - TMDB 刮削专用 OkHttp（短超时，保证刮削失败不阻塞列表/播放）
 * 其余点播/拓展类均为带 @Inject 构造的具体类，由 Hilt 直接装配，无需额外绑定。
 */
@Module
@InstallIn(SingletonComponent::class)
object VodModule {

    /** TMDB 刮削客户端：connect/read/write 各 5 秒（需求 6.4：网络不可达优雅降级） */
    @Provides
    @Singleton
    @TmdbOkHttp
    fun provideTmdbOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    @Provides
    @Singleton
    fun provideTmdbClient(
        @TmdbOkHttp okHttpClient: OkHttpClient,
        moshi: com.squareup.moshi.Moshi,
    ): TmdbClient = TmdbClient(okHttpClient, moshi)
}
