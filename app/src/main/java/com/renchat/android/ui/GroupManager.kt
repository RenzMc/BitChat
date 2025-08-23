package com.renchat.android.ui

import android.util.Log
import com.renchat.android.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.*
import kotlin.random.Random

/**
 * WhatsApp-style Group Management System
 * 
 * Manages group creation, membership, roles, invites, and permissions
 * Integrates seamlessly with existing mesh networking and channel system
 */
class GroupManager(
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val dataManager: DataManager,
    private val coroutineScope: CoroutineScope
) {
    
    companion object {
        private const val TAG = "GroupManager"
        private const val INVITE_CODE_LENGTH = 8
        private const val MAX_GROUPS_PER_USER = 50
    }
    
    // Internal group storage
    private val groups = mutableMapOf<String, Group>()
    private val userGroups = mutableMapOf<String, MutableSet<String>>() // peerID -> group IDs
    
    // MARK: - Group Creation & Management
    
    /**
     * Create a new group with advanced WhatsApp-like features
     */
    fun createGroup(
        name: String,
        description: String? = null,
        creatorPeerID: String,
        creatorNickname: String,
        settings: GroupSettings = GroupSettings()
    ): Group? {
        
        if (name.isBlank()) {
            Log.w(TAG, "Cannot create group with empty name")
            return null
        }
        
        // Check user group limit
        val userGroupCount = userGroups[creatorPeerID]?.size ?: 0
        if (userGroupCount >= MAX_GROUPS_PER_USER) {
            Log.w(TAG, "User $creatorPeerID has reached maximum group limit")
            return null
        }
        
        // Generate unique group ID
        val groupId = generateGroupId()
        
        // Create group with creator as owner
        val creator = GroupMember(
            peerID = creatorPeerID,
            nickname = creatorNickname,
            role = GroupRole.OWNER,
            joinedAt = Date()
        )
        
        val group = Group(
            id = groupId,
            name = name,
            description = description,
            createdBy = creatorPeerID,
            createdAt = Date(),
            settings = settings,
            members = mapOf(creatorPeerID to creator)
        )
        
        // Store group
        groups[groupId] = group
        addUserToGroup(creatorPeerID, groupId)
        
        // Add to joined channels for compatibility
        val channelName = group.getChannelName()
        val updatedChannels = state.getJoinedChannelsValue().toMutableSet()
        updatedChannels.add(channelName)
        state.setJoinedChannels(updatedChannels)
        
        // Set creator as channel creator for compatibility
        dataManager.addChannelCreator(channelName, creatorPeerID)
        dataManager.addChannelMember(channelName, creatorPeerID)
        
        Log.i(TAG, "Created group '$name' (ID: $groupId) by $creatorNickname")
        
        // Send group creation announcement
        sendGroupSystemMessage(group, "$creatorNickname created the group", creatorPeerID)
        
        return group
    }
    
    /**
     * Generate unique group ID
     */
    private fun generateGroupId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        var generatedId: String
        do {
            generatedId = (1..8).map { chars.random() }.joinToString("")
        } while (groups.containsKey(generatedId))
        return generatedId
    }
    
    /**
     * Join group by invite code
     */
    fun joinGroupByInvite(
        inviteCode: String,
        joinerPeerID: String,
        joinerNickname: String
    ): Boolean {
        
        // Find group with active invite
        val groupWithInvite = groups.values.firstOrNull { group ->
            group.getActiveInvite(inviteCode) != null
        }
        
        if (groupWithInvite == null) {
            Log.w(TAG, "Invalid or expired invite code: $inviteCode")
            return false
        }
        
        return joinGroup(groupWithInvite.id, joinerPeerID, joinerNickname, inviteCode)
    }
    
    /**
     * Join group directly (for internal use)
     */
    fun joinGroup(
        groupId: String,
        joinerPeerID: String,
        joinerNickname: String,
        inviteCode: String? = null,
        invitedBy: String? = null
    ): Boolean {
        
        val group = groups[groupId] ?: return false
        
        // Check if already a member
        if (group.members.containsKey(joinerPeerID)) {
            Log.i(TAG, "User $joinerNickname already member of group ${group.name}")
            return switchToGroup(groupId)
        }
        
        // Check if banned
        if (group.isBanned(joinerPeerID)) {
            Log.w(TAG, "User $joinerNickname is banned from group ${group.name}")
            return false
        }
        
        // Check capacity
        if (group.isAtCapacity()) {
            Log.w(TAG, "Group ${group.name} is at capacity")
            return false
        }
        
        // Process invite if provided
        if (inviteCode != null) {
            val invite = group.getActiveInvite(inviteCode)
            if (invite == null) {
                Log.w(TAG, "Invalid invite code for group ${group.name}")
                return false
            }
            
            // Update invite usage
            val updatedInvite = invite.copy(currentUses = invite.currentUses + 1)
            val updatedGroup = group.copy(
                invites = group.invites + (inviteCode to updatedInvite)
            )
            groups[groupId] = updatedGroup
        }
        
        // Add member
        val newMember = GroupMember(
            peerID = joinerPeerID,
            nickname = joinerNickname,
            role = GroupRole.MEMBER,
            joinedAt = Date(),
            invitedBy = invitedBy
        )
        
        val updatedGroup = groups[groupId]!!.copy(
            members = group.members + (joinerPeerID to newMember),
            lastActivity = Date()
        )
        groups[groupId] = updatedGroup
        
        addUserToGroup(joinerPeerID, groupId)
        
        // Add to joined channels for compatibility
        val channelName = updatedGroup.getChannelName()
        val updatedChannels = state.getJoinedChannelsValue().toMutableSet()
        updatedChannels.add(channelName)
        state.setJoinedChannels(updatedChannels)
        
        dataManager.addChannelMember(channelName, joinerPeerID)
        
        Log.i(TAG, "$joinerNickname joined group ${group.name}")
        
        // Send join announcement
        sendGroupSystemMessage(updatedGroup, "$joinerNickname joined the group", joinerPeerID)
        
        return true
    }
    
    /**
     * Leave group
     */
    fun leaveGroup(groupId: String, leaverPeerID: String): Boolean {
        val group = groups[groupId] ?: return false
        val member = group.getMember(leaverPeerID) ?: return false
        
        // Owner cannot leave unless transferring ownership
        if (member.role == GroupRole.OWNER && group.getMemberCount() > 1) {
            Log.w(TAG, "Group owner must transfer ownership before leaving")
            return false
        }
        
        // Remove member
        val updatedMembers = group.members.toMutableMap()
        updatedMembers.remove(leaverPeerID)
        
        val updatedGroup = group.copy(
            members = updatedMembers,
            lastActivity = Date()
        )
        groups[groupId] = updatedGroup
        
        removeUserFromGroup(leaverPeerID, groupId)
        
        // Remove from joined channels
        val channelName = group.getChannelName()
        val updatedChannels = state.getJoinedChannelsValue().toMutableSet()
        updatedChannels.remove(channelName)
        state.setJoinedChannels(updatedChannels)
        
        dataManager.removeChannelMember(channelName, leaverPeerID)
        
        Log.i(TAG, "${member.nickname} left group ${group.name}")
        
        // Send leave announcement
        sendGroupSystemMessage(updatedGroup, "${member.nickname} left the group", leaverPeerID)
        
        // If group is empty, mark as inactive
        if (updatedGroup.members.isEmpty()) {
            groups[groupId] = updatedGroup.copy(isActive = false)
        }
        
        return true
    }
    
    // MARK: - Role Management
    
    /**
     * Promote member to higher role
     */
    fun promoteMember(
        groupId: String,
        targetPeerID: String,
        newRole: GroupRole,
        promoterPeerID: String
    ): Boolean {
        
        val group = groups[groupId] ?: return false
        val promoter = group.getMember(promoterPeerID) ?: return false
        val target = group.getMember(targetPeerID) ?: return false
        
        // Check permissions
        if (!promoter.role.canPerformAction(GroupAction.PROMOTE_MEMBER)) {
            Log.w(TAG, "Insufficient permissions to promote member")
            return false
        }
        
        // Can't promote to same or higher role than promoter (except owner can promote to admin)
        if (newRole.value >= promoter.role.value && promoter.role != GroupRole.OWNER) {
            Log.w(TAG, "Cannot promote to same or higher role")
            return false
        }
        
        // Can't promote owner
        if (target.role == GroupRole.OWNER) {
            Log.w(TAG, "Cannot promote group owner")
            return false
        }
        
        // Update member role
        val updatedMember = target.copy(role = newRole)
        val updatedMembers = group.members.toMutableMap()
        updatedMembers[targetPeerID] = updatedMember
        
        val updatedGroup = group.copy(
            members = updatedMembers,
            lastActivity = Date()
        )
        groups[groupId] = updatedGroup
        
        Log.i(TAG, "${target.nickname} promoted to ${newRole.displayName} by ${promoter.nickname}")
        
        // Send promotion announcement
        sendGroupSystemMessage(
            updatedGroup, 
            "${target.nickname} was promoted to ${newRole.displayName} by ${promoter.nickname}",
            promoterPeerID
        )
        
        return true
    }
    
    /**
     * Demote member to lower role
     */
    fun demoteMember(
        groupId: String,
        targetPeerID: String,
        newRole: GroupRole,
        demoterPeerID: String
    ): Boolean {
        
        val group = groups[groupId] ?: return false
        val demoter = group.getMember(demoterPeerID) ?: return false
        val target = group.getMember(targetPeerID) ?: return false
        
        // Check permissions
        if (!demoter.role.canPerformAction(GroupAction.DEMOTE_MEMBER)) {
            Log.w(TAG, "Insufficient permissions to demote member")
            return false
        }
        
        // Can't demote equal or higher role (except owner)
        if (target.role.value >= demoter.role.value && demoter.role != GroupRole.OWNER) {
            Log.w(TAG, "Cannot demote equal or higher role")
            return false
        }
        
        // Can't demote owner
        if (target.role == GroupRole.OWNER) {
            Log.w(TAG, "Cannot demote group owner")
            return false
        }
        
        // Update member role
        val updatedMember = target.copy(role = newRole)
        val updatedMembers = group.members.toMutableMap()
        updatedMembers[targetPeerID] = updatedMember
        
        val updatedGroup = group.copy(
            members = updatedMembers,
            lastActivity = Date()
        )
        groups[groupId] = updatedGroup
        
        Log.i(TAG, "${target.nickname} demoted to ${newRole.displayName} by ${demoter.nickname}")
        
        // Send demotion announcement
        sendGroupSystemMessage(
            updatedGroup,
            "${target.nickname} was demoted to ${newRole.displayName} by ${demoter.nickname}",
            demoterPeerID
        )
        
        return true
    }
    
    // MARK: - Member Management
    
    /**
     * Kick member from group
     */
    fun kickMember(
        groupId: String,
        targetPeerID: String,
        kickerPeerID: String,
        reason: String? = null
    ): Boolean {
        
        val group = groups[groupId] ?: return false
        val kicker = group.getMember(kickerPeerID) ?: return false
        val target = group.getMember(targetPeerID) ?: return false
        
        // Check permissions
        if (!kicker.role.canPerformAction(GroupAction.KICK_MEMBER)) {
            Log.w(TAG, "Insufficient permissions to kick member")
            return false
        }
        
        // Can't kick equal or higher role (except owner)
        if (target.role.value >= kicker.role.value && kicker.role != GroupRole.OWNER) {
            Log.w(TAG, "Cannot kick equal or higher role")
            return false
        }
        
        // Can't kick owner
        if (target.role == GroupRole.OWNER) {
            Log.w(TAG, "Cannot kick group owner")
            return false
        }
        
        // Remove member
        val updatedMembers = group.members.toMutableMap()
        updatedMembers.remove(targetPeerID)
        
        val updatedGroup = group.copy(
            members = updatedMembers,
            lastActivity = Date()
        )
        groups[groupId] = updatedGroup
        
        removeUserFromGroup(targetPeerID, groupId)
        
        // Remove from joined channels
        val channelName = group.getChannelName()
        dataManager.removeChannelMember(channelName, targetPeerID)
        
        Log.i(TAG, "${target.nickname} kicked from group ${group.name} by ${kicker.nickname}")
        
        // Send kick announcement
        val reasonText = reason?.let { " (Reason: $it)" } ?: ""
        sendGroupSystemMessage(
            updatedGroup,
            "${target.nickname} was removed from the group by ${kicker.nickname}$reasonText",
            kickerPeerID
        )
        
        return true
    }
    
    /**
     * Ban member from group
     */
    fun banMember(
        groupId: String,
        targetPeerID: String,
        bannerPeerID: String,
        reason: String? = null
    ): Boolean {
        
        val group = groups[groupId] ?: return false
        val banner = group.getMember(bannerPeerID) ?: return false
        val target = group.getMember(targetPeerID)
        
        // Check permissions
        if (!banner.role.canPerformAction(GroupAction.BAN_MEMBER)) {
            Log.w(TAG, "Insufficient permissions to ban member")
            return false
        }
        
        // Can't ban equal or higher role (except owner)
        if (target != null && target.role.value >= banner.role.value && banner.role != GroupRole.OWNER) {
            Log.w(TAG, "Cannot ban equal or higher role")
            return false
        }
        
        // Can't ban owner
        if (target?.role == GroupRole.OWNER) {
            Log.w(TAG, "Cannot ban group owner")
            return false
        }
        
        // Add to banned list
        val updatedBanned = group.bannedMembers.toMutableSet()
        updatedBanned.add(targetPeerID)
        
        // Remove from members if present
        val updatedMembers = group.members.toMutableMap()
        val wasRemoved = updatedMembers.remove(targetPeerID) != null
        
        val updatedGroup = group.copy(
            members = updatedMembers,
            bannedMembers = updatedBanned,
            lastActivity = Date()
        )
        groups[groupId] = updatedGroup
        
        if (wasRemoved) {
            removeUserFromGroup(targetPeerID, groupId)
            
            // Remove from joined channels
            val channelName = group.getChannelName()
            dataManager.removeChannelMember(channelName, targetPeerID)
        }
        
        Log.i(TAG, "User $targetPeerID banned from group ${group.name} by ${banner.nickname}")
        
        // Send ban announcement
        val targetName = target?.nickname ?: targetPeerID
        val reasonText = reason?.let { " (Reason: $it)" } ?: ""
        sendGroupSystemMessage(
            updatedGroup,
            "$targetName was banned from the group by ${banner.nickname}$reasonText",
            bannerPeerID
        )
        
        return true
    }
    
    /**
     * Unban member from group
     */
    fun unbanMember(
        groupId: String,
        targetPeerID: String,
        unbannerPeerID: String
    ): Boolean {
        
        val group = groups[groupId] ?: return false
        val unbanner = group.getMember(unbannerPeerID) ?: return false
        
        // Check permissions
        if (!unbanner.role.canPerformAction(GroupAction.BAN_MEMBER)) {
            Log.w(TAG, "Insufficient permissions to unban member")
            return false
        }
        
        // Check if actually banned
        if (!group.isBanned(targetPeerID)) {
            Log.w(TAG, "User $targetPeerID is not banned from group")
            return false
        }
        
        // Remove from banned list
        val updatedBanned = group.bannedMembers.toMutableSet()
        updatedBanned.remove(targetPeerID)
        
        val updatedGroup = group.copy(
            bannedMembers = updatedBanned,
            lastActivity = Date()
        )
        groups[groupId] = updatedGroup
        
        Log.i(TAG, "User $targetPeerID unbanned from group ${group.name} by ${unbanner.nickname}")
        
        // Send unban announcement
        sendGroupSystemMessage(
            updatedGroup,
            "User $targetPeerID was unbanned by ${unbanner.nickname}",
            unbannerPeerID
        )
        
        return true
    }
    
    // MARK: - Invite Management
    
    /**
     * Create group invite link
     */
    fun createGroupInvite(
        groupId: String,
        creatorPeerID: String,
        expiresInHours: Int? = null,
        maxUses: Int? = null
    ): GroupInvite? {
        
        val group = groups[groupId] ?: return null
        val creator = group.getMember(creatorPeerID) ?: return null
        
        // Check permissions
        if (!creator.role.canPerformAction(GroupAction.INVITE_MEMBER)) {
            Log.w(TAG, "Insufficient permissions to create invite")
            return null
        }
        
        // Generate unique invite code
        val inviteCode = generateInviteCode()
        
        val expiresAt = expiresInHours?.let {
            Calendar.getInstance().apply {
                add(Calendar.HOUR_OF_DAY, it)
            }.time
        }
        
        val invite = GroupInvite(
            inviteCode = inviteCode,
            groupId = groupId,
            groupName = group.name,
            createdBy = creatorPeerID,
            createdAt = Date(),
            expiresAt = expiresAt,
            maxUses = maxUses
        )
        
        // Store invite
        val updatedGroup = group.copy(
            invites = group.invites + (inviteCode to invite)
        )
        groups[groupId] = updatedGroup
        
        Log.i(TAG, "Created invite for group ${group.name}: $inviteCode")
        
        return invite
    }
    
    /**
     * Generate unique invite code
     */
    private fun generateInviteCode(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        var generatedCode: String
        do {
            generatedCode = (1..INVITE_CODE_LENGTH).map { chars.random() }.joinToString("")
        } while (groups.values.any { it.invites.containsKey(generatedCode) })
        return generatedCode
    }
    
    /**
     * Revoke group invite
     */
    fun revokeGroupInvite(
        groupId: String,
        inviteCode: String,
        revokerPeerID: String
    ): Boolean {
        
        val group = groups[groupId] ?: return false
        val revoker = group.getMember(revokerPeerID) ?: return false
        val invite = group.invites[inviteCode] ?: return false
        
        // Check permissions (can revoke own invites or admin+)
        if (invite.createdBy != revokerPeerID && !revoker.role.canPerformAction(GroupAction.INVITE_MEMBER)) {
            Log.w(TAG, "Insufficient permissions to revoke invite")
            return false
        }
        
        // Deactivate invite
        val updatedInvite = invite.copy(isActive = false)
        val updatedGroup = group.copy(
            invites = group.invites + (inviteCode to updatedInvite)
        )
        groups[groupId] = updatedGroup
        
        Log.i(TAG, "Revoked invite $inviteCode for group ${group.name}")
        
        return true
    }
    
    // MARK: - Group Settings
    
    /**
     * Update group settings
     */
    fun updateGroupSettings(
        groupId: String,
        newSettings: GroupSettings,
        updaterPeerID: String
    ): Boolean {
        
        val group = groups[groupId] ?: return false
        val updater = group.getMember(updaterPeerID) ?: return false
        
        // Check permissions
        if (!updater.role.canPerformAction(GroupAction.CHANGE_SETTINGS)) {
            Log.w(TAG, "Insufficient permissions to change group settings")
            return false
        }
        
        val updatedGroup = group.copy(
            settings = newSettings,
            lastActivity = Date()
        )
        groups[groupId] = updatedGroup
        
        Log.i(TAG, "Updated settings for group ${group.name}")
        
        return true
    }
    
    /**
     * Update group description
     */
    fun updateGroupDescription(
        groupId: String,
        newDescription: String,
        updaterPeerID: String
    ): Boolean {
        
        val group = groups[groupId] ?: return false
        val updater = group.getMember(updaterPeerID) ?: return false
        
        // Check permissions
        if (!updater.role.canPerformAction(GroupAction.CHANGE_SETTINGS)) {
            Log.w(TAG, "Insufficient permissions to change group description")
            return false
        }
        
        val updatedGroup = group.copy(
            description = newDescription.takeIf { it.isNotBlank() },
            lastActivity = Date()
        )
        groups[groupId] = updatedGroup
        
        Log.i(TAG, "Updated description for group ${group.name}")
        
        // Send description change announcement
        sendGroupSystemMessage(
            updatedGroup,
            "Group description updated by ${updater.nickname}",
            updaterPeerID
        )
        
        return true
    }
    
    /**
     * Transfer group ownership
     */
    fun transferOwnership(
        groupId: String,
        newOwnerPeerID: String,
        currentOwnerPeerID: String
    ): Boolean {
        
        val group = groups[groupId] ?: return false
        val currentOwner = group.getMember(currentOwnerPeerID) ?: return false
        val newOwner = group.getMember(newOwnerPeerID) ?: return false
        
        // Check permissions
        if (currentOwner.role != GroupRole.OWNER) {
            Log.w(TAG, "Only current owner can transfer ownership")
            return false
        }
        
        // Update roles
        val updatedCurrentOwner = currentOwner.copy(role = GroupRole.ADMIN)
        val updatedNewOwner = newOwner.copy(role = GroupRole.OWNER)
        
        val updatedMembers = group.members.toMutableMap()
        updatedMembers[currentOwnerPeerID] = updatedCurrentOwner
        updatedMembers[newOwnerPeerID] = updatedNewOwner
        
        val updatedGroup = group.copy(
            members = updatedMembers,
            lastActivity = Date()
        )
        groups[groupId] = updatedGroup
        
        // Update channel creator for compatibility
        val channelName = group.getChannelName()
        dataManager.removeChannelCreator(channelName)
        dataManager.addChannelCreator(channelName, newOwnerPeerID)
        
        Log.i(TAG, "Transferred ownership of group ${group.name} to ${newOwner.nickname}")
        
        // Send ownership transfer announcement
        sendGroupSystemMessage(
            updatedGroup,
            "${currentOwner.nickname} transferred group ownership to ${newOwner.nickname}",
            currentOwnerPeerID
        )
        
        return true
    }
    
    // MARK: - Utility Functions
    
    /**
     * Get group by ID
     */
    fun getGroup(groupId: String): Group? {
        return groups[groupId]
    }
    
    /**
     * Get group by channel name
     */
    fun getGroupByChannel(channelName: String): Group? {
        if (!channelName.startsWith("#group:")) return null
        val groupId = channelName.removePrefix("#group:")
        return getGroup(groupId)
    }
    
    /**
     * Get all groups for user
     */
    fun getUserGroups(peerID: String): List<Group> {
        val groupIds = userGroups[peerID] ?: return emptyList()
        return groupIds.mapNotNull { groups[it] }.filter { it.isActive }
    }
    
    /**
     * Switch to group (compatibility with channel system)
     */
    fun switchToGroup(groupId: String): Boolean {
        val group = getGroup(groupId) ?: return false
        val channelName = group.getChannelName()
        state.setCurrentChannel(channelName)
        return true
    }
    
    /**
     * Send system message to group
     */
    private fun sendGroupSystemMessage(group: Group, message: String, senderPeerID: String) {
        val systemMessage = RenChatMessage(
            sender = "System",
            content = message,
            timestamp = Date(),
            senderPeerID = senderPeerID,
            channel = group.getChannelName()
        )
        
        messageManager.addChannelMessage(group.getChannelName(), systemMessage)
    }
    
    /**
     * Add user to group mapping
     */
    private fun addUserToGroup(peerID: String, groupId: String) {
        val currentGroups = userGroups[peerID] ?: mutableSetOf()
        currentGroups.add(groupId)
        userGroups[peerID] = currentGroups
    }
    
    /**
     * Remove user from group mapping
     */
    private fun removeUserFromGroup(peerID: String, groupId: String) {
        val currentGroups = userGroups[peerID] ?: return
        currentGroups.remove(groupId)
        if (currentGroups.isEmpty()) {
            userGroups.remove(peerID)
        }
    }
}