package com.campusmesh.di

import android.content.Context
import androidx.room.Room
import com.campusmesh.db.AttachmentDao
import com.campusmesh.db.BroadcastDao
import com.campusmesh.db.CampusMeshDatabase
import com.campusmesh.db.GroupDao
import com.campusmesh.db.MessageDao
import com.campusmesh.db.PeerDao
import com.campusmesh.db.ProfileDao
import com.campusmesh.db.RelayPacketDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CampusMeshDatabase {
        return Room.databaseBuilder(
            context,
            CampusMeshDatabase::class.java,
            CampusMeshDatabase.DATABASE_NAME,
        )
            .addMigrations(
                CampusMeshDatabase.MIGRATION_6_7,
                CampusMeshDatabase.MIGRATION_7_8,
            )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideMessageDao(database: CampusMeshDatabase): MessageDao {
        return database.messageDao()
    }

    @Provides
    @Singleton
    fun providePeerDao(database: CampusMeshDatabase): PeerDao {
        return database.peerDao()
    }

    @Provides
    @Singleton
    fun provideRelayPacketDao(database: CampusMeshDatabase): RelayPacketDao {
        return database.relayPacketDao()
    }

    @Provides
    @Singleton
    fun provideProfileDao(database: CampusMeshDatabase): ProfileDao {
        return database.profileDao()
    }

    @Provides
    @Singleton
    fun provideAttachmentDao(database: CampusMeshDatabase): AttachmentDao {
        return database.attachmentDao()
    }

    @Provides
    @Singleton
    fun provideGroupDao(database: CampusMeshDatabase): GroupDao {
        return database.groupDao()
    }

    @Provides
    @Singleton
    fun provideBroadcastDao(database: CampusMeshDatabase): BroadcastDao {
        return database.broadcastDao()
    }
}
