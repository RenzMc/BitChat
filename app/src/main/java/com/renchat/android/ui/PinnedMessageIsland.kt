package com.renchat.android.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.renchat.android.model.RenChatMessage

/**
 * PinnedMessageIsland - Simple, stable pinned message display
 * Shows pinned message without animations to prevent crashes
 */
@Composable
fun PinnedMessageIsland(
    pinnedMessage: RenChatMessage?,
    onPinnedMessageClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    
    // Simple visibility check without animations
    if (pinnedMessage != null) {
        PinnedMessageContent(
            message = pinnedMessage,
            onClick = onPinnedMessageClick,
            colorScheme = colorScheme,
            modifier = modifier
        )
    }
}

@Composable
private fun PinnedMessageContent(
    message: RenChatMessage,
    onClick: () -> Unit,
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier
) {
    // Simple background without animations
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(
                    color = colorScheme.surface.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(24.dp)
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Simple pin icon without animations
            Icon(
                imageVector = Icons.Filled.PushPin,
                contentDescription = "Pinned message",
                tint = colorScheme.primary.copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp)
            )
            
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Sender name with subtle styling
                Text(
                    text = message.sender,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    ),
                    color = colorScheme.primary.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                // Message content with proper truncation
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 18.sp
                    ),
                    color = colorScheme.onSurface.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            // Subtle arrow or indicator
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = "Scroll to message",
                tint = colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Extension function to integrate into the main Chat UI
 * This can be called from ChatScreen with proper state management
 */
@Composable
fun ChatScreenWithPinnedMessage(
    pinnedMessage: RenChatMessage?,
    onScrollToPinned: () -> Unit,
    content: @Composable () -> Unit
) {
    Column {
        // Pinned message island below header
        PinnedMessageIsland(
            pinnedMessage = pinnedMessage,
            onPinnedMessageClick = onScrollToPinned
        )
        
        // Main chat content
        content()
    }
}