package com.renchat.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.renchat.android.model.RenChatMessage
import com.renchat.android.model.DeliveryStatus
import com.renchat.android.mesh.BluetoothMeshService
import java.text.SimpleDateFormat
import java.util.*

/**
 * Message display components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */

@Composable
fun MessagesList(
    messages: List<RenChatMessage>,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    viewedMessages: Set<String> = emptySet(),
    onMessageViewed: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    
    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    
    SelectionContainer(modifier = modifier) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(messages) { message ->
                // Filter out view-once messages that should be hidden
                val shouldShowMessage = when {
                    !message.isViewOnce -> true // Regular messages always show
                    message.senderPeerID == meshService.myPeerID -> true // User's own view-once messages always show
                    !viewedMessages.contains(message.id) -> true // View-once messages not yet viewed show
                    else -> false // View-once messages already viewed are hidden
                }
                
                if (shouldShowMessage) {
                    MessageItem(
                        message = message,
                        currentUserNickname = currentUserNickname,
                        meshService = meshService,
                        onMessageViewed = { messageId ->
                            // Mark view-once messages as viewed when displayed
                            if (message.isViewOnce && message.senderPeerID != meshService.myPeerID) {
                                onMessageViewed(messageId)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MessageItem(
    message: RenChatMessage,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    onMessageViewed: (String) -> Unit = {}
) {
    val colorScheme = MaterialTheme.colorScheme
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    
    // Mark view-once messages as viewed when they are displayed
    LaunchedEffect(message.id) {
        if (message.isViewOnce && message.senderPeerID != meshService.myPeerID) {
            onMessageViewed(message.id)
        }
    }
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        // Message content with optional view-once indicator
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.Top
        ) {
            // View-once indicator
            if (message.isViewOnce) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = "View once message",
                    modifier = Modifier
                        .size(12.dp)
                        .padding(end = 4.dp),
                    tint = Color(0xFF00C853).copy(alpha = 0.7f)
                )
            }
            
            // Single text view for natural wrapping (like iOS)
            Text(
                text = formatMessageAsAnnotatedString(
                    message = message,
                    currentUserNickname = currentUserNickname,
                    meshService = meshService,
                    colorScheme = colorScheme,
                    timeFormatter = timeFormatter
                ),
                style = MaterialTheme.typography.bodyMedium,
                softWrap = true,
                overflow = TextOverflow.Visible
            )
        }
        
        // Delivery status for private messages
        if (message.isPrivate && message.sender == currentUserNickname) {
            message.deliveryStatus?.let { status ->
                DeliveryStatusIcon(status = status)
            }
        }
    }
}

@Composable
fun DeliveryStatusIcon(status: DeliveryStatus) {
    val colorScheme = MaterialTheme.colorScheme
    
    when (status) {
        is DeliveryStatus.Sending -> {
            Text(
                text = "○",
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
        is DeliveryStatus.Sent -> {
            Text(
                text = "✓",
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
        is DeliveryStatus.Delivered -> {
            Text(
                text = "✓✓",
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.8f)
            )
        }
        is DeliveryStatus.Read -> {
            Text(
                text = "✓✓",
                fontSize = 10.sp,
                color = Color(0xFF007AFF), // Blue
                fontWeight = FontWeight.Bold
            )
        }
        is DeliveryStatus.Failed -> {
            Text(
                text = "⚠",
                fontSize = 10.sp,
                color = Color.Red.copy(alpha = 0.8f)
            )
        }
        is DeliveryStatus.PartiallyDelivered -> {
            Text(
                text = "✓${status.reached}/${status.total}",
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
    }
}
