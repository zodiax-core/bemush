package com.campusmesh.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "group_members",
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["groupId"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class GroupMemberEntity(
    @PrimaryKey
    val memberId: String,
    val groupId: String,
    val nodeId: String,
    val joinedAt: Long,
    val isActive: Boolean,
)
