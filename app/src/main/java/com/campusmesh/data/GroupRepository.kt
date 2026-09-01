package com.campusmesh.data

import com.campusmesh.db.GroupDao
import com.campusmesh.db.GroupEntity
import com.campusmesh.db.GroupMemberEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupRepository @Inject constructor(
    private val groupDao: GroupDao,
) {
    val allGroups: Flow<List<GroupEntity>> = groupDao.observeAllGroups()

    suspend fun getGroup(groupId: String): GroupEntity? = groupDao.getGroup(groupId)

    suspend fun upsertGroup(
        groupId: String,
        groupName: String,
        creatorNodeId: String,
        groupKeyBase64: String,
        version: Long,
    ) {
        val entity = GroupEntity(
            groupId = groupId,
            groupName = groupName,
            creatorNodeId = creatorNodeId,
            createdAt = System.currentTimeMillis(),
            groupKeyBase64 = groupKeyBase64,
            version = version,
        )
        groupDao.upsertGroup(entity)
    }

    suspend fun getGroupMembers(groupId: String): List<GroupMemberEntity> = groupDao.getGroupMembers(groupId)

    suspend fun getGroupMember(groupId: String, nodeId: String): GroupMemberEntity? = groupDao.getGroupMember(groupId, nodeId)

    suspend fun upsertGroupMember(
        groupId: String,
        nodeId: String,
        isActive: Boolean,
    ) {
        val entity = GroupMemberEntity(
            memberId = "${groupId}_${nodeId}",
            groupId = groupId,
            nodeId = nodeId,
            joinedAt = System.currentTimeMillis(),
            isActive = isActive,
        )
        groupDao.upsertGroupMember(entity)
    }

    suspend fun removeGroupMember(groupId: String, nodeId: String) {
        groupDao.removeGroupMember(groupId, nodeId)
    }

    suspend fun leaveGroup(groupId: String, nodeId: String) {
        groupDao.leaveGroup(groupId, nodeId)
    }
}
