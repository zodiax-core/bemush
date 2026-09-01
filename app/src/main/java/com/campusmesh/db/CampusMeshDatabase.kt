package com.campusmesh.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MessageEntity::class,
        PeerEntity::class,
        RelayPacketEntity::class,
        ProfileEntity::class,
        AttachmentEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class,
        BroadcastEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class CampusMeshDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun peerDao(): PeerDao
    abstract fun relayPacketDao(): RelayPacketDao
    abstract fun profileDao(): ProfileDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun groupDao(): GroupDao
    abstract fun broadcastDao(): BroadcastDao

    companion object {
        const val DATABASE_NAME = "campus_mesh.db"

        /** v6 → v7: adds displayName column to the peers table. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE peers ADD COLUMN displayName TEXT DEFAULT NULL")
            }
        }

        /** v7 → v8: adds avatarPath & avatarHash to peers, isRead to messages. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE peers ADD COLUMN avatarPath TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE peers ADD COLUMN avatarHash TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN isRead INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
