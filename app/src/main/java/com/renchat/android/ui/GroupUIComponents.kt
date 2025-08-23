package com.renchat.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.renchat.android.model.Group
import com.renchat.android.model.GroupRole

/**
 * WhatsApp-style Group UI Components
 * 
 * Provides modern UI components for group management and display
 */

/**
 * Group section in sidebar - shows user's groups separately from channels
 */
@Composable
fun GroupsSection(
    groups: List<Group>,
    currentChannel: String?,
    colorScheme: ColorScheme,
    onGroupClick: (String) -> Unit,
    onLeaveGroup: (String) -> Unit,
    unreadGroupMessages: Map<String, Int> = emptyMap()
) {
    if (groups.isEmpty()) return
    
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Group,
                contentDescription = null,
                modifier = Modifier.size(10.dp),
                tint = colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "GROUPS",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurface.copy(alpha = 0.6f),
                fontWeight = FontWeight.Bold
            )
        }
        
        groups.forEach { group ->
            val channelName = group.getChannelName()
            val isSelected = channelName == currentChannel
            val unreadCount = unreadGroupMessages[channelName] ?: 0
            
            GroupItemRow(
                group = group,
                isSelected = isSelected,
                unreadCount = unreadCount,
                colorScheme = colorScheme,
                onClick = { onGroupClick(channelName) },
                onLeave = { onLeaveGroup(channelName) }
            )
        }
    }
}

/**
 * Individual group item in the sidebar
 */
@Composable
private fun GroupItemRow(
    group: Group,
    isSelected: Boolean,
    unreadCount: Int,
    colorScheme: ColorScheme,
    onClick: () -> Unit,
    onLeave: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(
                if (isSelected) colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Unread badge
        if (unreadCount > 0) {
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(16.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(colorScheme.error),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onError,
                    fontSize = 8.sp
                )
            }
        }
        
        // Group icon
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colorScheme.primary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Group,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = colorScheme.primary
            )
        }
        
        Spacer(modifier = Modifier.width(8.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) colorScheme.primary else colorScheme.onSurface,
                fontWeight = if (isSelected || unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            if (group.description != null) {
                Text(
                    text = group.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        
        // Member count
        Text(
            text = "${group.getMemberCount()}",
            style = MaterialTheme.typography.labelSmall,
            color = colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

/**
 * Group info panel - shows detailed group information
 */
@Composable
fun GroupInfoPanel(
    group: Group,
    currentUserPeerID: String,
    colorScheme: ColorScheme,
    onDismiss: () -> Unit
) {
    val currentMember = group.getMember(currentUserPeerID)
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = colorScheme.onSurface
                    )
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = "Group Info",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        
        // Group details
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Group,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = colorScheme.primary
                        )
                        
                        Spacer(modifier = Modifier.width(12.dp))
                        
                        Column {
                            Text(
                                text = group.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )
                            
                            if (group.description != null) {
                                Text(
                                    text = group.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    // Group stats
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        GroupStatItem("Members", "${group.getMemberCount()}", colorScheme)
                        GroupStatItem("Admins", "${group.getAdmins().size}", colorScheme)
                        GroupStatItem("Created", formatGroupDate(group.createdAt), colorScheme)
                    }
                }
            }
        }
        
        // Current user role
        if (currentMember != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = getRoleColor(currentMember.role, colorScheme).copy(alpha = 0.1f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = getRoleIcon(currentMember.role),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = getRoleColor(currentMember.role, colorScheme)
                        )
                        
                        Spacer(modifier = Modifier.width(12.dp))
                        
                        Column {
                            Text(
                                text = "Your Role",
                                style = MaterialTheme.typography.labelMedium,
                                color = colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            Text(
                                text = currentMember.role.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                color = getRoleColor(currentMember.role, colorScheme),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
        
        // Members list
        item {
            Text(
                text = "Members (${group.getMemberCount()})",
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
        }
        
        // Show members by role
        GroupRole.values().reversed().forEach { role ->
            val roleMembers = group.getMembersByRole(role)
            if (roleMembers.isNotEmpty()) {
                item {
                    Text(
                        text = "${role.displayName}s",
                        style = MaterialTheme.typography.labelLarge,
                        color = colorScheme.onSurface.copy(alpha = 0.7f),
                        fontWeight = FontWeight.Bold
                    )
                }
                
                roleMembers.forEach { member ->
                    item {
                        MemberListItem(
                            member = member,
                            colorScheme = colorScheme,
                            isOnline = false // TODO: Add online status check
                        )
                    }
                }
            }
        }
    }
}

/**
 * Individual member item in the list
 */
@Composable
private fun MemberListItem(
    member: com.renchat.android.model.GroupMember,
    colorScheme: ColorScheme,
    isOnline: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Online status
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (isOnline) Color.Green else Color.Gray)
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = member.nickname,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface
            )
            
            Text(
                text = "Joined ${formatGroupDate(member.joinedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        
        // Role icon
        Icon(
            imageVector = getRoleIcon(member.role),
            contentDescription = member.role.displayName,
            modifier = Modifier.size(16.dp),
            tint = getRoleColor(member.role, colorScheme)
        )
    }
}

/**
 * Group statistic item
 */
@Composable
private fun GroupStatItem(
    label: String,
    value: String,
    colorScheme: ColorScheme
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

/**
 * Get role-specific icon
 */
private fun getRoleIcon(role: GroupRole): androidx.compose.ui.graphics.vector.ImageVector {
    return when (role) {
        GroupRole.OWNER -> IconsCrown
        GroupRole.ADMIN -> Icons.Default.Star
        GroupRole.MODERATOR -> IconsShield
        GroupRole.MEMBER -> Icons.Default.Person
    }
}

/**
 * Get role-specific color
 */
private fun getRoleColor(role: GroupRole, colorScheme: ColorScheme): Color {
    return when (role) {
        GroupRole.OWNER -> Color(0xFFFFD700) // Gold
        GroupRole.ADMIN -> colorScheme.primary
        GroupRole.MODERATOR -> Color(0xFF4CAF50) // Green
        GroupRole.MEMBER -> colorScheme.onSurface.copy(alpha = 0.7f)
    }
}

/**
 * Format date for group display
 */
private fun formatGroupDate(date: java.util.Date): String {
    val formatter = java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault())
    return formatter.format(date)
}

// Extension properties for missing icons
private val IconsCrown: androidx.compose.ui.graphics.vector.ImageVector
    get() = Icons.Default.Star

private val IconsShield: androidx.compose.ui.graphics.vector.ImageVector
    get() = Icons.Default.Security