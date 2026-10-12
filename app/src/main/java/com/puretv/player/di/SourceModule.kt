package com.puretv.player.di

import com.puretv.player.data.repository.SourceRepository
import com.puretv.player.data.source.SourceRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 直播源依赖装配：SourceRepository 接口绑定实现（与设置中心/新手引导解耦）
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SourceModule {

    @Binds
    @Singleton
    abstract fun bindSourceRepository(impl: SourceRepositoryImpl): SourceRepository
}
