package com.campusmesh.group

import com.campusmesh.data.GroupRepository
import com.campusmesh.db.GroupDao
import com.campusmesh.db.GroupEntity
import com.campusmesh.db.GroupMemberEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GroupTest {

    @Test
    fun testGroupRepositoryCreateAndJoin() = runBlocking {
        val dao = FakeGroupDao()
        val repo = GroupRepository(dao)

        repo.upsertGroup(
            groupId = "group_123",
            groupName = "Campus Devs",
            creatorNodeId = "node_creator",
            groupKeyBase64 = "dummy_key",
            version = 1L,
        )

        repo.upsertGroupMember(
            groupId = "group_123",
            nodeId = "node_member",
            isActive = true,
        )

        val group = repo.getGroup("group_123")
        assertNotNull(group)
        assertEquals("Campus Devs", group?.groupName)

        val members = repo.getGroupMembers("group_123")
        assertEquals(1, members.size)
        assertEquals("node_member", members.first().nodeId)
    }

    private class FakeGroupDao : GroupDao {
        private val groups = mutableMapOf<String, GroupEntity>()
        private val members = mutableMapOf<String, MutableList<GroupMemberEntity>>()

        override suspend fun getGroup(groupId: String): GroupEntity? = groups[groupId]

        override fun observeAllGroups(): Flow<List<GroupEntity>> = flowOf(groups.values.toList())

        override suspend fun upsertGroup(group: GroupEntity) {
            groups[group.groupId] = group
        }

        override suspend fun deleteGroup(groupId: String) {
            groups.remove(groupId)
            members.remove(groupId)
        }

        override suspend fun getGroupMembers(groupId: String): List<GroupMemberEntity> = members[groupId] ?: emptyList()

        override suspend fun getGroupMember(groupId: String, nodeId: String): GroupMemberEntity? {
            return members[groupId]?.find { it.nodeId == nodeId }
        }

        override suspend fun upsertGroupMember(member: GroupMemberEntity) {
            members.getOrPut(member.groupId) { mutableListOf() }.add(member)
        }

        override suspend fun removeGroupMember(groupId: String, nodeId: String) {
            members[groupId]?.removeIf { it.nodeId == nodeId }
        }

        override suspend fun leaveGroup(groupId: String, nodeId: String) {
            removeGroupMember(groupId, nodeId)
            if (getGroupMembers(groupId).isEmpty()) {
                deleteGroup(groupId)
            }
        }
    }
}
