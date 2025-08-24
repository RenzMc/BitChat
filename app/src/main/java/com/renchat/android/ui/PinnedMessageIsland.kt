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
 * PinnedMessageIsland - iPhone-style dynamic island for pinned messages
 * Shows pinned message in a compact, modern style below the header
 */
@Composable
fun PinnedMessageIsland(
    pinnedMessage: RenChatMessage?,
    onPinnedMessageClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    
    // Safe animation for appearing/disappearing
    AnimatedVisibility(
        visible = pinnedMessage != null,
        enter = try {
            slideInVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium // Changed from Low to Medium for stability
                ),
                initialOffsetY = { -it }
            ) + fadeIn(
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
            )
        } catch (e: Exception) {
            android.util.Log.e("PinnedMessageIsland", "Enter animation error", e)
            fadeIn(tween(200)) // Fallback to simple fade
        },
        exit = try {
            slideOutVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium // Changed from Low to Medium for stability
                ),
                targetOffsetY = { -it }
            ) + fadeOut(
                animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
            )
        } catch (e: Exception) {
            android.util.Log.e("PinnedMessageIsland", "Exit animation error", e)
            fadeOut(tween(150)) // Fallback to simple fade
        },
        modifier = modifier
    ) {
        pinnedMessage?.let { message ->
            PinnedMessageContent(
                message = message,
                onClick = onPinnedMessageClick,
                colorScheme = colorScheme
            )
        }
    }
}

@Composable
private fun PinnedMessageContent(
    message: RenChatMessage,
    onClick: () -> Unit,
    colorScheme: ColorScheme
) {
    // Dynamic island background with blur effect
    Box(
        modifier = Modifier
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
            // Pin icon with safe animation
            var iconScale by remember(message.id) { mutableStateOf(1f) }
            
            LaunchedEffect(message.id) {
                try {
                    iconScale = 1.2f
                    kotlinx.coroutines.delay(150)
                    iconScale = 1f
                } catch (e: Exception) {
                    // Reset to default if animation fails
                    iconScale = 1f
                    android.util.Log.e("PinnedMessageIsland", "Animation error", e)
                }
            }
            
            Icon(
                imageVector = Icons.Filled.PushPin,
                contentDescription = "Pinned message",
                tint = colorScheme.primary.copy(alpha = 0.8f),
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer(
                        scaleX = if (iconScale.isFinite()) iconScale else 1f,
                        scaleY = if (iconScale.isFinite()) iconScale else 1f
                    )
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