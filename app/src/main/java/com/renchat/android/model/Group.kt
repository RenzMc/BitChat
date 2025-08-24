package com.renchat.android.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.*

/**
 * Group role definitions - hierarchical permission system
 */
enum class GroupRole(val value: Int, val displayName: String) {
    MEMBER(0, "Member"),           // Basic group member
    MODERATOR(1, "Moderator"),     // Can kick members, delete messages
    ADMIN(2, "Admin"),             // Can promote/demote, invite, manage settings
    OWNER(3, "Owner");             // Full control, can transfer ownership

    fun canManageRole(targetRole: GroupRole): Boolean {
        return this.value > targetRole.value
    }

    fun canPerformAction(action: GroupAction): Boolean {
        return when (action) {
            GroupAction.SEND_MESSAGE -> true // All roles can send messages
            GroupAction.DELETE_OWN_MESSAGE -> true // All can delete own messages
            GroupAction.DELETE_ANY_MESSAGE -> this.value >= MODERATOR.value
            GroupAction.KICK_MEMBER -> this.value >= MODERATOR.value
            GroupAction.BAN_MEMBER -> this.value >= ADMIN.value
            GroupAction.INVITE_MEMBER -> this.value >= ADMIN.value
            GroupAction.PROMOTE_MEMBER -> this.value >= ADMIN.value
            GroupAction.DEMOTE_MEMBER -> this.value >= ADMIN.value
            GroupAction.CHANGE_SETTINGS -> this.value >= ADMIN.value
            GroupAction.TRANSFER_OWNERSHIP -> this == OWNER
        }
    }
}

/**
 * Group actions for permission checking
 */
enum class GroupAction {
    SEND_MESSAGE,
    DELETE_OWN_MESSAGE,
    DELETE_ANY_MESSAGE,
    KICK_MEMBER,
    BAN_MEMBER,
    INVITE_MEMBER,
    PROMOTE_MEMBER,
    DEMOTE_MEMBER,
    CHANGE_SETTINGS,
    TRANSFER_OWNERSHIP
}

/**
 * Group member representation
 */
@Parcelize
data class GroupMember(
    val peerID: String,
    val nickname: String,
    val role: GroupRole,
    val joinedAt: Date,
    val invitedBy: String? = null,
    val isActive: Boolean = true
) : Parcelable

/**
 * Group invite information
 */
@Parcelize
data class GroupInvite(
    val inviteCode: String,
    val groupId: String,
    val groupName: String,
    val createdBy: String,
    val createdAt: Date,
    val expiresAt: Date? = null,
    val maxUses: Int? = null,
    val currentUses: Int = 0,
    val isActive: Boolean = true
) : Parcelable {
    
    fun isValid(): Boolean {
        if (!isActive) return false
        if (expiresAt != null && Date().after(expiresAt)) return false
        if (maxUses != null && currentUses >= maxUses) return false
        return true
    }
    
    fun getInviteUrl(): String {
        return "renchat://group/join?invite=$inviteCode"
    }
    
    fun getWebInviteUrl(): String {
        return "https://renchat.app/group/join?invite=$inviteCode"
    }
    
    fun getShareableInviteText(): String {
        return "Join my RenChat group '$groupName'!\n\nApp link: ${getInviteUrl()}\nWeb link: ${getWebInviteUrl()}"
    }
}

/**
 * Group settings and configuration
 */
@Parcelize
data class GroupSettings(
    val allowMemberInvites: Boolean = false,      // Can members invite others
    val allowMemberPromote: Boolean = false,      // Can admins promote to admin
    val messageRetention: Boolean = false,        // Store message history
    val requireApprovalToJoin: Boolean = false,   // Require admin approval
    val allowMediaSharing: Boolean = true,        // Allow file/media sharing
    val maxMembers: Int = 256,                    // Maximum group size
    val description: String? = null               // Group description
) : Parcelable

/**
 * Main Group data class - WhatsApp-style group with advanced management
 */
@Parcelize
data class Group(
    val id: String,                              // Unique group identifier
    val name: String,                            // Group display name
    val description: String? = null,             // Optional group description
    val createdBy: String,                       // Creator peer ID
    val createdAt: Date,                         // Creation timestamp
    val settings: GroupSettings = GroupSettings(),
    val members: Map<String, GroupMember> = mapOf(),
    val bannedMembers: Set<String> = setOf(),    // Banned peer IDs
    val invites: Map<String, GroupInvite> = mapOf(),
    val isActive: Boolean = true,
    val lastActivity: Date = Date()
) : Parcelable {
    
    /**
     * Get member by peer ID
     */
    fun getMember(peerID: String): GroupMember? {
        return members[peerID]
    }
    
    /**
     * Check if user has specific role or higher
     */
    fun hasRoleOrHigher(peerID: String, requiredRole: GroupRole): Boolean {
        val member = getMember(peerID) ?: return false
        return member.role.value >= requiredRole.value
    }
    
    /**
     * Check if user can perform specific action
     */
    fun canPerformAction(peerID: String, action: GroupAction): Boolean {
        val member = getMember(peerID) ?: return false
        return member.role.canPerformAction(action)
    }
    
    /**
     * Get all members with specific role
     */
    fun getMembersByRole(role: GroupRole): List<GroupMember> {
        return members.values.filter { it.role == role && it.isActive }
    }
    
    /**
     * Get group owner
     */
    fun getOwner(): GroupMember? {
        return members.values.firstOrNull { it.role == GroupRole.OWNER }
    }
    
    /**
     * Get all admins (including owner)
     */
    fun getAdmins(): List<GroupMember> {
        return members.values.filter { 
            (it.role == GroupRole.ADMIN || it.role == GroupRole.OWNER) && it.isActive 
        }
    }
    
    /**
     * Check if member is banned
     */
    fun isBanned(peerID: String): Boolean {
        return bannedMembers.contains(peerID)
    }
    
    /**
     * Get active invite by code
     */
    fun getActiveInvite(inviteCode: String): GroupInvite? {
        val invite = invites[inviteCode]
        return if (invite?.isValid() == true) invite else null
    }
    
    /**
     * Get group channel name (for compatibility with existing channel system)
     */
    fun getChannelName(): String {
        return "#group:$id"
    }
    
    /**
     * Get member count
     */
    fun getMemberCount(): Int {
        return members.values.count { it.isActive }
    }
    
    /**
     * Check if group is at capacity
     */
    fun isAtCapacity(): Boolean {
        return getMemberCount() >= settings.maxMembers
    }
}