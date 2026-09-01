package com.campusmesh.di

import com.campusmesh.platform.EpochClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun epochClock(): EpochClock = EpochClock { System.currentTimeMillis() }
}
