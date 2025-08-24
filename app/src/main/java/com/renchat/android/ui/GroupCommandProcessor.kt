package com.renchat.android.ui

import android.util.Log
import com.renchat.android.mesh.BluetoothMeshService
import com.renchat.android.model.*
import java.util.*

/**
 * WhatsApp-style Group Command Processor
 * 
 * Handles all /group commands for comprehensive group management
 * Integrates seamlessly with existing command system and mesh networking
 */
class GroupCommandProcessor(
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val groupManager: GroupManager
) {
    
    companion object {
        private const val TAG = "GroupCommandProcessor"
    }
    
    /**
     * Process group commands - returns true if command was handled
     */
    fun processGroupCommand(
        command: String,
        meshService: BluetoothMeshService,
        myPeerID: String,
        sendMessageCallback: (String, List<String>?, String?) -> Unit
    ): Boolean {
        
        val parts = command.trim().split("\\s+".toRegex())
        if (parts.size < 2 || parts[0] != "/group") return false
        
        val myNickname = state.getNicknameValue() ?: myPeerID
        val subCommand = parts[1].lowercase()
        
        return when (subCommand) {
            "create" -> handleCreateGroup(parts, myPeerID, myNickname, sendMessageCallback)
            "join" -> handleJoinGroup(parts, myPeerID, myNickname, sendMessageCallback)
            "invite" -> handleCreateInvite(parts, myPeerID, myNickname, sendMessageCallback)
            "promote" -> handlePromoteMember(parts, myPeerID, myNickname, meshService, sendMessageCallback)
            "demote" -> handleDemoteMember(parts, myPeerID, myNickname, meshService, sendMessageCallback)
            "kick" -> handleKickMember(parts, myPeerID, myNickname, meshService, sendMessageCallback)
            "ban" -> handleBanMember(parts, myPeerID, myNickname, meshService, sendMessageCallback)
            "unban" -> handleUnbanMember(parts, myPeerID, myNickname, meshService, sendMessageCallback)
            "members" -> handleListMembers(parts, myPeerID, sendMessageCallback)
            "admins" -> handleListAdmins(parts, myPeerID, sendMessageCallback)
            "info" -> handleGroupInfo(parts, myPeerID, sendMessageCallback)
            "description" -> handleSetDescription(parts, myPeerID, myNickname, sendMessageCallback)
            "settings" -> handleGroupSettings(parts, myPeerID, sendMessageCallback)
            "leave" -> handleLeaveGroup(parts, myPeerID, myNickname, sendMessageCallback)
            "help" -> handleGroupHelp(sendMessageCallback)
            else -> {
                showErrorMessage("Unknown group command: $subCommand. Type '/group help' for available commands.", sendMessageCallback)
                true
            }
        }
    }
    
    /**
     * Handle /group create [name] [description]
     */
    private fun handleCreateGroup(
        parts: List<String>,
        myPeerID: String,
        myNickname: String,
        sendMessageCallback: (String, List<String>?, String?) -> Unit
    ): Boolean {
        
        if (parts.size < 3) {
            showErrorMessage("Usage: /group create <name> [description]", sendMessageCallback)
            return true
        }
        
        val name = parts[2]
        val description = if (parts.size > 3) {
            parts.drop(3).joinToString(" ")
        } else null
        
        // Validate group name
        if (name.length < 2 || name.length > 50) {
            showErrorMessage("Group name must be between 2-50 characters", sendMessageCallback)
            return true
        }
        
        val group = groupManager.createGroup(
            name = name,
            description = description,
            creatorPeerID = myPeerID,
            creatorNickname = myNickname
        )
        
        if (group != null) {
            // Switch to the new group
            groupManager.switchToGroup(group.id)
            
            val descText = description?.let { " - $it" } ?: ""
            showSuccessMessage("✅ Created group \"${group.name}\"$descText", sendMessageCallback)
            showInfoMessage("💡 Share invite: /group invite", sendMessageCallback)
        } else {
            showErrorMessage("❌ Failed to create group. You may have reached the maximum number of groups.", sendMessageCallback)
        }
        
        return true
    }
    
    /**
     * Handle /group join [invite-code]
     */
    private fun handleJoinGroup(
        parts: List<String>,
        myPeerID: String,
        myNickname: String,
        sendMessageCallback: (String, List<String>?, String?) -> Unit
    ): Boolean {
        
        if (parts.size < 3) {
            showErrorMessage("Usage: /group join <invite-code>", sendMessageCallback)
            return true
        }
        
        val inviteCode = parts[2].uppercase()
        
        val success = groupManager.joinGroupByInvite(
            inviteCode = inviteCode,
            joinerPeerID = myPeerID,
            joinerNickname = myNickname
        )
        
        if (success) {
            showSuccessMessage("✅ Successfully joined the group!", sendMessageCallback)
        } else {
            showErrorMessage("❌ Failed to join group. Invalid or expired invite code.", sendMessageCallback)
        }
        
        return true
    }
    
    /**
     * Handle /group invite [expires-hours] [max-uses]
     */
    private fun handleCreateInvite(
        parts: List<String>,
        myPeerID: String,
        myNickname: String,
        sendMessageCallback: (String, List<String>?, String?) -> Unit
    ): Boolean {
        
        val currentGroup = getCurrentGroup()
        if (currentGroup == null) {
            showErrorMessage("❌ You must be in a group to create invites", sendMessageCallback)
            return true
        }
        
        val expiresInHours = if (parts.size > 2 && parts[2] != "never") {
            parts[2].toIntOrNull()?.takeIf { it > 0 }
        } else null
        
        val maxUses = if (parts.size > 3) {
            parts[3].toIntOrNull()?.takeIf { it > 0 }
        } else null
        
        val invite = groupManager.createGroupInvite(
            groupId = currentGroup.id,
            creatorPeerID = myPeerID,
            expiresInHours = expiresInHours,
            maxUses = maxUses
        )
        
        if (invite != null) {
            var inviteInfo = "🔗 Group Invite Created\n"
            inviteInfo += "📝 Code: ${invite.inviteCode}\n"
            inviteInfo += "🏷️ Group: ${invite.groupName}\n"
            
            if (invite.expiresAt != null) {
                inviteInfo += "⏰ Expires: ${formatDate(invite.expiresAt)}\n"
            } else {
                inviteInfo += "⏰ Never expires\n"
            }
            
            if (invite.maxUses != null) {
                inviteInfo += "👥 Max uses: ${invite.maxUses}\n"
            } else {
                inviteInfo += "👥 Unlimited uses\n"
            }
            
            inviteInfo += "🌐 App: ${invite.getInviteUrl()}\n📱 Web: ${invite.getWebInviteUrl()}"
            
            showSuccessMessage(inviteInfo, sendMessageCallback)
        } else {
            showErrorMessage("❌ Failed to create invite. You may not have permission.", sendMessageCallback)
        }
        
        return true
    }
    
    /**
     * Handle /group promote @username [role]
     */
    private fun handlePromoteMember(
        parts: List<String>,
        myPeerID: String,
        myNickname: String,
        meshService: BluetoothMeshService,
        sendMessageCallback: (String, List<String>?, String?) -> Unit
    ): Boolean {
        
        if (parts.size < 3) {
            showErrorMessage("Usage: /group promote @username [admin|moderator]", sendMessageCallback)
            return true
        }
        
        val currentGroup = getCurrentGroup()
        if (currentGroup == null) {
            showErrorMessage("❌ You must be in a group to promote members", sendMessageCallback)
            return true
        }
        
        val targetUsername = parts[2].removePrefix("@")
        val roleStr = if (parts.size > 3) parts[3].lowercase() else "admin"
        
        val newRole = when (roleStr) {
            "admin" -> GroupRole.ADMIN
            "moderator" -> GroupRole.MODERATOR
            else -> {
                showErrorMessage("❌ Invalid role. Use 'admin' or 'moderator'", sendMessageCallback)
                return true
            }
        }
        
        // Find target peer ID by nickname
        val targetPeerID = findPeerByNickname(targetUsername, meshService)
        if (targetPeerID == null) {
            showErrorMessage("❌ User @$targetUsername not found", sendMessageCallback)
            return true
        }
        
        val success = groupManager.promoteMember(
            groupId = currentGroup.id,
            targetPeerID = targetPeerID,
            newRole = newRole,
            promoterPeerID = myPeerID
        )
        
        if (success) {
            showSuccessMessage("✅ @$targetUsername promoted to ${newRole.displayName}", sendMessageCallback)
        } else {
            showErrorMessage("❌ Failed to promote member. Check permissions and target user.", sendMessageCallback)
        }
        
        return true
    }
    
    /**
     * Handle /group demote @username [role]
     */
    private fun handleDemoteMember(
        parts: List<String>,
        myPeerID: String,
        myNickname: String,
        meshService: BluetoothMeshService,
        sendMessageCallback: (String, List<String>?, String?) -> Unit
    ): Boolean {
        
        if (parts.size < 3) {
            showErrorMessage("Usage: /group demote @username [member|moderator]", sendMessageCallback)
            return true
        }
        
        val currentGroup = getCurrentGroup()
        if (currentGroup == null) {
            showErrorMessage("❌ You must be in a group to demote members", sendMessageCallback)
            return true
        }
        
        val targetUsername = parts[2].removePrefix("@")
        val roleStr = if (parts.size > 3) parts[3].lowercase() else "member"
        
        val newRole = when (roleStr) {
            "member" -> GroupRole.MEMBER
            "moderator" -> GroupRole.MODERATOR
            else -> {
                showErrorMessage("❌ Invalid role. Use 'member' or 'moderator'", sendMessageCallback)
                return true
            }
        }
        
        // Find target peer ID by nickname
        val targetPeerID = findPeerByNickname(targetUsername, meshService)
        if (targetPeerID == null) {
            showErrorMessage("❌ User @$targetUsername not found", sendMessageCallback)
            return true
        }
        
        val success = groupManager.demoteMember(
            groupId = currentGroup.id,
            targetPeerID = targetPeerID,
            newRole = newRole,
            demoterPeerID = myPeerID
        )
        
        if (success) {
            showSuccessMessage("✅ @$targetUsername demoted to ${newRole.displayName}", sendMessageCallback)
        } else {
            showErrorMessage("❌ Failed to demote member. Check permissions and target user.", sendMessageCallback)
        }
        
        return true
    }
    
    /**
     * Handle /group kick @username [reason]
     */
    private fun handleKickMember(
        parts: List<String>,
        myPeerID: String,
        myNickname: String,
        meshService: BluetoothMeshService,
        sendMessageCallback: (String, List<String>?, String?) -> Unit
    ): Boolean {
        
        if (parts.size < 3) {
            showErrorMessage("Usage: /group kick @username [reason]", sendMessageCallback)
            return true
        }
        
        val currentGroup = getCurrentGroup()
        if (currentGroup == null) {
            showErrorMessage("❌ You must be in a group to kick members", sendMessageCallback)
            return true
        }
        
        val targetUsername = parts[2].removePrefix("@")
        val reason = if (parts.size > 3) {
            parts.drop(3).joinToString(" ")
        } else null
        
        // Find target peer ID by nickname
        val targetPeerID = findPeerByNickname(targetUsername, meshService)
        if (targetPeerID == null) {
            showErrorMessage("❌ User @$targetUsername not found", sendMessageCallback)
            return true
        }
        
        val success = groupManager.kickMember(
            groupId = currentGroup.id,
            targetPeerID = targetPeerID,
            kickerPeerID = myPeerID,
            reason = reason
        )
        
        if (success) {
            val reasonText = reason?.let { " (Reason: $it)" } ?: ""
            showSuccessMessage("✅ @$targetUsername removed from group$reasonText", sendMessageCallback)
        } else {
            showErrorMessage("❌ Failed to kick member. Check permissions and target user.", sendMessageCallback)
        }
        
        return true
    }
    
    /**
     * Handle /group ban @username [reason]
     */
    private fun handleBanMember(
        parts: List<String>,
        myPeerID: String,
        myNickname: String,
        meshService: BluetoothMeshService,
        sendMessageCallback: (String, List<String>?, String?) -> Unit
    ): Boolean {
        
        if (parts.size < 3) {
            showErrorMessage("Usage: /group ban @username [reason]", sendMessageCallback)
            return true
        }
        
        val currentGroup = getCurrentGroup()
        if (currentGroup == null) {
            showErrorMessage("❌ You must be in a group to ban members", sendMessageCallback)
            return true
        }
        
        val targetUsername = parts[2].removePrefix("@")
        val reason = if (parts.size > 3) {
            parts.drop(3).joinToString(" ")
        } else null
        
        // Find target peer ID by nickname
        val targetPeerID = findPeerByNickname(targetUsername, meshService)
        if (targetPeerID == null) {
            showErrorMessage("❌ User @$targetUsername not found", sendMessageCallback)
            return true
        }
        
        val success = groupManager.banMember(
            groupId = currentGroup.id,
            targetPeerID = targetPeerID,
            bannerPeerID = myPeerID,
            reason = reason
        )
        
        if (success) {
            val reasonText = reason?.let { " (Reason: $it)" } ?: ""
            showSuccessMessage("✅ @$targetUsername banned from group$reasonText", sendMessageCallback)
        } else {
            showErrorMessage("❌ Failed to ban member. Check permissions and target user.", sendMessageCallback)
        }
        
        return true
    }
    
    /**
     * Handle /group unban @username
     */
    private fun handleUnbanMember(
        parts: List<String>,
        myPeerID: String,
        myNickname: String,
        meshService: BluetoothMeshService,
        sendMessageCallback: (String, List<String>?, String?) -> Unit
    ): Boolean {
        
        if (parts.size < 3) {
            showErrorMessage("Usage: /group unban @username", sendMessageCallback)
            return true
        }
        
        val currentGroup = getCurrentGroup()
        if (currentGroup == null) {
            showErrorMessage("❌ You must be in a group to unban members", sendMessageCallback)
            return true
        }
        
        val targetUsername = parts[2].removePrefix("@")
        
        // Find target peer ID by nickname (may not be online)
        val targetPeerID = findPeerByNickname(targetUsername, meshService) ?: targetUsername
        
        val success = groupManager.unbanMember(
            groupId = currentGroup.id,
            targetPeerID = targetPeerID,
            unbannerPeerID = myPeerID
        )
        
        if (success) {
            showSuccessMessage("✅ @$targetUsername unbanned from group", sendMessageCallback)
        } else {
            showErrorMessage("❌ Failed to unban member. User may not be banned or you lack permissions.", sendMessageCallback)
        }
        
        return true
    }
    
    /**
     * Handle /group members
     */
    private fun handleListMembers(
        parts: List<String>,
        myPeerID: String,
        sendMessageCallback: (String, List<String>?, String?) -> Unit
    ): Boolean {
        
        val currentGroup = getCurrentGroup()
        if (currentGroup == null) {
            showErrorMessage("❌ You must be in a group to list members", sendMessageCallback)
            return true
        }
        
        val members = currentGroup.members.values.filter { it.isActive }
            .sortedByDescending { it.role.value }
        
        if (members.isEmpty()) {
            showInfoMessage("👥 No active members in this group", sendMessageCallback)
            return true
        }
        
        var memberList = "👥 Group Members (${members.size}):\n\n"
        
        // Group by role
        val membersByRole = members.groupBy { it.role }
        
        GroupRole.values().reversed().forEach { role ->
            val roleMembers = membersByRole[role] ?: return@forEach
            if (roleMembers.isNotEmpty()) {
                memberList += "${role.displayName}s:\n"
                roleMembers.forEach { member ->
                    val isOnline = state.getConnectedPeersValue().contains(member.peerID)
                    val statusIcon = if (isOnline) "🟢" else "⚫"
                    memberList += "$statusIcon @${member.nickname}\n"
                }
                memberList += "\n"
            }
        }
        
        showInfoMessage(memberList.trim(), sendMessageCallback)
        return true
    }
    
    /**
     * Handle /group admins
     */
    private fun handleListAdmins(
        parts: List<String>,
        myPeerID: String,
        sendMessageCallback: (String, List<String>?, String?) -> Unit
    ): Boolean {
        
        val currentGroup = getCurrentGroup()
        if (currentGroup == null) {
            showErrorMessage("❌ You must be in a group to list admins", sendMessageCallback)
            return true
        }
        
        val admins = currentGroup.getAdmins()
        
        if (admins.isEmpty()) {
            showInfoMessage("👑 No admins in this group", sendMessageCallback)
            return true
        }
        
        var adminList = "👑 Group Administrators:\n\n"
        
        admins.sortedByDescending { it.role.value }.forEach { admin ->
            val isOnline = state.getConnectedPeersValue().contains(admin.peerID)
            val statusIcon = if (isOnline) "🟢" else "⚫"
            val roleIcon = if (admin.role == GroupRole.OWNER) "👑" else "⭐"
            adminList += "$statusIcon $roleIcon @${admin.nickname} (${admin.role.displayName})\n"
        }
        
        showInfoMessage(adminList.trim(), sendMessageCallback)
        return true
    }
    
    /**
     * Handle /group info
     */
    private fun handleGroupInfo(
        parts: List<String>,
        myPeerID: String,
        sendMessageCallback: (String, List<String>?, String?) -> Unit
    ): Boolean {
        
        val currentGroup = getCurrentGroup()
        if (currentGroup == null) {
            showErrorMessage("❌ You must be in a group to view info", sendMessageCallback)
            return true
        }
        
        val member = currentGroup.getMember(myPeerID)
        val memberCount = currentGroup.getMemberCount()
        val adminCount = currentGroup.getAdmins().size
        val owner = currentGroup.getOwner()
        
        var info = "ℹ️ Group Information\n\n"
        info += "📝 Name: ${currentGroup.name}\n"
        info += "🆔 ID: ${currentGroup.id}\n"
        
        if (currentGroup.description != null) {
            info += "📄 Description: ${currentGroup.description}\n"
        }
        
        info += "👥 Members: $memberCount/${currentGroup.settings.maxMembers}\n"
        info += "⭐ Admins: $adminCount\n"
        info += "👑 Owner: @${owner?.nickname ?: "Unknown"}\n"
        
        if (member != null) {
            info += "🏷️ Your Role: ${member.role.displayName}\n"
            info += "📅 Joined: ${formatDate(member.joinedAt)}\n"
        }
        
        info += "🕒 Created: ${formatDate(currentGroup.createdAt)}\n"
        info += "⚙️ Settings:\n"
        info += "  📨 Member invites: ${if (currentGroup.settings.allowMemberInvites) "✅" else "❌"}\n"
        info += "  💾 Message retention: ${if (currentGroup.settings.messageRetention) "✅" else "❌"}\n"
        info += "  🔒 Approval required: ${if (currentGroup.settings.requireApprovalToJoin) "✅" else "❌"}\n"
        
        showInfoMessage(info, sendMessageCallback)
        return true
    }
    
    /**
     * Handle /group description [new description]
     */
    private fun handleSetDescription(
        parts: List<String>,
        myPeerID: String,
        myNickname: String,
        sendMessageCallback: (String, List<String>?, String?) -> Unit
    ): Boolean {
        
        val currentGroup = getCurrentGroup()
        if (currentGroup == null) {
            showErrorMessage("❌ You must be in a group to set description", sendMessageCallback)
            return true
        }
        
        val newDescription = if (parts.size > 2) {
            parts.drop(2).joinToString(" ")
        } else {
            showErrorMessage("Usage: /group description <new description>", sendMessageCallback)
            return true
        }
        
        val success = groupManager.updateGroupDescription(
            groupId = currentGroup.id,
            newDescription = newDescription,
            updaterPeerID = myPeerID
        )
        
        if (success) {
            showSuccessMessage("✅ Group description updated", sendMessageCallback)
        } else {
            showErrorMessage("❌ Failed to update description. You may not have permission.", sendMessageCallback)
        }
        
        return true
    }
    
    /**
     * Handle /group settings
     */
    private fun handleGroupSettings(
        parts: List<String>,
        myPeerID: String,
        sendMessageCallback: (String, List<String>?, String?) -> Unit
    ): Boolean {
        
        val currentGroup = getCurrentGroup()
        if (currentGroup == null) {
            showErrorMessage("❌ You must be in a group to view settings", sendMessageCallback)
            return true
        }
        
        // For now, just show current settings. Could extend to allow changes
        var settings = "⚙️ **Group Settings**\n\n"
        settings += "📨 Member invites: ${if (currentGroup.settings.allowMemberInvites) "✅ Enabled" else "❌ Disabled"}\n"
        settings += "👥 Member promotion: ${if (currentGroup.settings.allowMemberPromote) "✅ Enabled" else "❌ Disabled"}\n"
        settings += "💾 Message retention: ${if (currentGroup.settings.messageRetention) "✅ Enabled" else "❌ Disabled"}\n"
        settings += "🔒 Approval required: ${if (currentGroup.settings.requireApprovalToJoin) "✅ Enabled" else "❌ Disabled"}\n"
        settings += "📁 Media sharing: ${if (currentGroup.settings.allowMediaSharing) "✅ Enabled" else "❌ Disabled"}\n"
        settings += "🎯 Max members: ${currentGroup.settings.maxMembers}\n"
        
        val member = currentGroup.getMember(myPeerID)
        if (member?.role?.canPerformAction(GroupAction.CHANGE_SETTINGS) == true) {
            settings += "\n💡 Use admin tools to modify these settings"
        }
        
        showInfoMessage(settings, sendMessageCallback)
        return true
    }
    
    /**
     * Handle /group leave
     */
    private fun handleLeaveGroup(
        parts: List<String>,
        myPeerID: String,
        myNickname: String,
        sendMessageCallback: (String, List<String>?, String?) -> Unit
    ): Boolean {
        
        val currentGroup = getCurrentGroup()
        if (currentGroup == null) {
            showErrorMessage("❌ You must be in a group to leave it", sendMessageCallback)
            return true
        }
        
        val success = groupManager.leaveGroup(
            groupId = currentGroup.id,
            leaverPeerID = myPeerID
        )
        
        if (success) {
            showSuccessMessage("✅ Left group \"${currentGroup.name}\"", sendMessageCallback)
            // Switch back to public chat
            state.setCurrentChannel(null)
        } else {
            showErrorMessage("❌ Failed to leave group. Owners must transfer ownership first.", sendMessageCallback)
        }
        
        return true
    }
    
    /**
     * Handle /group help
     */
    private fun handleGroupHelp(
        sendMessageCallback: (String, List<String>?, String?) -> Unit
    ): Boolean {
        
        val help = """
            🤖 Group Commands Help
            
            Basic Commands:
            `/group create <name> [description]` - Create new group
            `/group join <invite-code>` - Join group with invite
            `/group leave` - Leave current group
            `/group info` - Show group information
            
            Invites:
            `/group invite [hours] [max-uses]` - Create invite link
            
            Member Management:
            `/group members` - List all members
            `/group admins` - List administrators
            `/group kick @user [reason]` - Remove member
            `/group ban @user [reason]` - Ban member
            `/group unban @user` - Unban member
            
            Role Management:
            `/group promote @user [admin|moderator]` - Promote member
            `/group demote @user [member|moderator]` - Demote member
            
            Group Settings:
            `/group description <text>` - Set description
            `/group settings` - View current settings
            
           Role Permissions:
            👑 Owner - Full control, transfer ownership
            ⭐ Admin - Manage members, invites, settings
            🛡️ Moderator - Kick members, delete messages
            👤 Member - Send messages, view content
        """.trimIndent()
        
        showInfoMessage(help, sendMessageCallback)
        return true
    }
    
    // MARK: - Utility Functions
    
    /**
     * Get current group from channel
     */
    private fun getCurrentGroup(): Group? {
        val currentChannel = state.getCurrentChannelValue() ?: return null
        return groupManager.getGroupByChannel(currentChannel)
    }
    
    /**
     * Find peer ID by nickname
     */
    private fun findPeerByNickname(nickname: String, meshService: BluetoothMeshService): String? {
        val peerNicknames = meshService.getPeerNicknames()
        return peerNicknames.entries.find { (_, value) ->
            value.equals(nickname, ignoreCase = true) 
        }?.key
    }
    
    /**
     * Show error message to user
     */
    private fun showErrorMessage(
        message: String,
        sendMessageCallback: (String, List<String>?, String?) -> Unit
    ) {
        val errorMessage = RenChatMessage(
            sender = "System",
            content = message,
            timestamp = Date(),
            senderPeerID = "system"
        )
        messageManager.addMessage(errorMessage)
    }
    
    /**
     * Show success message to user
     */
    private fun showSuccessMessage(
        message: String,
        sendMessageCallback: (String, List<String>?, String?) -> Unit
    ) {
        val successMessage = RenChatMessage(
            sender = "System",
            content = message,
            timestamp = Date(),
            senderPeerID = "system"
        )
        messageManager.addMessage(successMessage)
    }
    
    /**
     * Show info message to user
     */
    private fun showInfoMessage(
        message: String,
        sendMessageCallback: (String, List<String>?, String?) -> Unit
    ) {
        val infoMessage = RenChatMessage(
            sender = "System",
            content = message,
            timestamp = Date(),
            senderPeerID = "system"
        )
        messageManager.addMessage(infoMessage)
    }
    
    /**
     * Format date for display
     */
    private fun formatDate(date: Date): String {
        val formatter = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        return formatter.format(date)
    }
}