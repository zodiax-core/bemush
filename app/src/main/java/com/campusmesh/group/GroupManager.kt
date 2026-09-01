package com.campusmesh.group

import android.util.Base64
import com.campusmesh.crypto.NodeKeyManager
import com.campusmesh.data.GroupRepository
import com.campusmesh.identity.LocalNodeIdStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupManager @Inject constructor(
    private val groupRepository: GroupRepository,
    private val localNodeIdStore: LocalNodeIdStore,
    private val nodeKeyManager: NodeKeyManager,
) {
    private val _activeGroups = MutableStateFlow<List<GroupState>>(emptyList())
    val activeGroups: StateFlow<List<GroupState>> = _activeGroups.asStateFlow()

    suspend fun createGroup(groupName: String): GroupState {
        val groupId = "group_${System.currentTimeMillis()}"
        val groupKey = generateGroupKey()
        val groupKeyBase64 = Base64.encodeToString(groupKey.encoded, Base64.DEFAULT).trim()

        groupRepository.upsertGroup(
            groupId = groupId,
            groupName = groupName,
            creatorNodeId = localNodeIdStore.nodeId.toString(),
            groupKeyBase64 = groupKeyBase64,
            version = 1L,
        )

        groupRepository.upsertGroupMember(
            groupId = groupId,
            nodeId = localNodeIdStore.nodeId.toString(),
            isActive = true,
        )

        val state = GroupState(
            groupId = groupId,
            groupName = groupName,
            creatorNodeId = localNodeIdStore.nodeId.toString(),
            groupKey = groupKey,
            members = listOf(localNodeIdStore.nodeId.toString()),
            version = 1L,
        )

        _activeGroups.update { it + state }
        return state
    }

    suspend fun joinGroup(groupId: String, groupKeyBase64: String): Boolean {
        val group = groupRepository.getGroup(groupId) ?: return false
        val keyBytes = Base64.decode(groupKeyBase64, Base64.DEFAULT)
        val groupKey = SecretKeySpec(keyBytes, "AES")

        groupRepository.upsertGroupMember(
            groupId = groupId,
            nodeId = localNodeIdStore.nodeId.toString(),
            isActive = true,
        )

        val state = GroupState(
            groupId = groupId,
            groupName = group.groupName,
            creatorNodeId = group.creatorNodeId,
            groupKey = groupKey,
            members = listOf(localNodeIdStore.nodeId.toString()),
            version = group.version,
        )

        _activeGroups.update { it + state }
        return true
    }

    suspend fun leaveGroup(groupId: String) {
        groupRepository.leaveGroup(groupId, localNodeIdStore.nodeId.toString())
        _activeGroups.update { it.filter { it.groupId != groupId } }
    }

    private fun generateGroupKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256, SecureRandom())
        return keyGen.generateKey()
    }
}

data class GroupState(
    val groupId: String,
    val groupName: String,
    val creatorNodeId: String,
    val groupKey: SecretKey,
    val members: List<String>,
    val version: Long,
)
