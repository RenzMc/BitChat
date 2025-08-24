package com.renchat.android.ui    
    
import androidx.compose.foundation.layout.*    
import androidx.compose.foundation.lazy.LazyColumn    
import androidx.compose.foundation.lazy.items    
import androidx.compose.foundation.lazy.rememberLazyListState    
import androidx.compose.foundation.text.selection.SelectionContainer    
import androidx.compose.material.icons.Icons    
import androidx.compose.material.icons.filled.Lock    
import androidx.compose.material.icons.filled.Visibility    
import androidx.compose.material.icons.filled.PushPin    
import androidx.compose.material.icons.filled.KeyboardArrowRight    
import androidx.compose.material.icons.outlined.PushPin    
import androidx.compose.foundation.clickable    
import androidx.compose.foundation.background    
import androidx.compose.foundation.shape.CircleShape    
import androidx.compose.foundation.combinedClickable    
import androidx.compose.foundation.ExperimentalFoundationApi    
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.renchat.android.model.RenChatMessage    
import com.renchat.android.model.DeliveryStatus    
import com.renchat.android.mesh.BluetoothMeshService    
import java.text.SimpleDateFormat    
import java.util.*    
import kotlinx.coroutines.*    
    
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
    onViewOnceClick: (String) -> Unit = {},    
    scrollToMessageId: String? = null,    
    scrollTrigger: Int = 0,    
    onPinMessage: (RenChatMessage) -> Unit = {},    
    onUnpinMessage: () -> Unit = {},    
    canPinMessages: Boolean = false,    
    modifier: Modifier = Modifier    
) {    
    val listState = rememberLazyListState()    
        
    // Auto-scroll to bottom when new messages arrive    
    LaunchedEffect(messages.size) {    
        if (messages.isNotEmpty()) {    
            listState.animateScrollToItem(messages.size - 1)    
        }    
    }    
        
    // Scroll to pinned message when requested (triggered by scrollTrigger)    
    LaunchedEffect(scrollTrigger) {    
        if (scrollTrigger > 0) {    
            scrollToMessageId?.let { messageId ->    
                val messageIndex = messages.indexOfFirst { it.id == messageId }    
                if (messageIndex >= 0) {    
                    listState.animateScrollToItem(messageIndex)    
                }    
            }    
        }    
    }    
        
    SelectionContainer(modifier = modifier) {    
        LazyColumn(    
            state = listState,    
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),    
            verticalArrangement = Arrangement.spacedBy(2.dp)    
        ) {    
            items(messages, key = { it.id }) { message ->    
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
                        onMessageViewed = onMessageViewed,    
                        onViewOnceClick = onViewOnceClick,    
                        onPinMessage = onPinMessage,    
                        onUnpinMessage = onUnpinMessage,    
                        isPinned = message.isPinned,    
                        canPinMessages = canPinMessages    
                    )    
                }    
            }    
        }    
    }    
}    

@OptIn(ExperimentalFoundationApi::class)
@Composable    
fun MessageItem(    
    message: RenChatMessage,    
    currentUserNickname: String,    
    meshService: BluetoothMeshService,    
    onMessageViewed: (String) -> Unit = {},    
    onViewOnceClick: (String) -> Unit = {},    
    onPinMessage: (RenChatMessage) -> Unit = {},    
    onUnpinMessage: () -> Unit = {},    
    isPinned: Boolean = false,    
    canPinMessages: Boolean = false    
) {    
    val colorScheme = MaterialTheme.colorScheme    
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }    
    val hapticFeedback = LocalHapticFeedback.current
        
    // View-once state with proper key    
    var isViewOnceOpened by remember(message.id) { mutableStateOf(false) }    
        
    // Pin/Unpin menu state - simplified like settings dialog
    var showPinMenu by remember(message.id) { mutableStateOf(false) }
        
    Box(modifier = Modifier.fillMaxWidth()) {    
        Row(    
            modifier = Modifier    
                .fillMaxWidth()    
                .let { modifier ->    
                    if (canPinMessages && !message.isViewOnce) {    
                        modifier.combinedClickable(    
                            onLongClick = {     
                                // Simple haptic feedback like settings
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                // Simple state change like settings gear
                                showPinMenu = true
                            },    
                            onClick = { /* Do nothing on regular click */ }    
                        )    
                    } else {    
                        modifier    
                    }    
                },    
            horizontalArrangement = Arrangement.SpaceBetween,    
            verticalAlignment = Alignment.Top    
        ) {    
            // Message content with optional view-once indicator    
            Row(    
                modifier = Modifier.weight(1f),    
                verticalAlignment = Alignment.Top    
            ) {    
                // Enhanced View-once indicator with popup    
                if (message.isViewOnce && message.senderPeerID != meshService.myPeerID && !isViewOnceOpened) {    
                    // Show "View Once click to open" instead of content    
                    Row(    
                        modifier = Modifier    
                            .clickable {    
                                try {
                                    isViewOnceOpened = true    
                                    onViewOnceClick(message.id)    
                                    // Mark as viewed after opening - we'll handle the popup separately    
                                } catch (e: Exception) {
                                    android.util.Log.e("MessageComponents", "Error opening view-once message: ${e.message}", e)
                                }
                            }    
                            .background(    
                                colorScheme.primary.copy(alpha = 0.1f),    
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)    
                            )    
                            .padding(horizontal = 12.dp, vertical = 8.dp),    
                        verticalAlignment = Alignment.CenterVertically    
                    ) {    
                        // View once icon    
                        Icon(    
                            imageVector = Icons.Filled.Visibility,    
                            contentDescription = "View once message",    
                            modifier = Modifier.size(18.dp),    
                            tint = colorScheme.primary    
                        )    
                        Spacer(modifier = Modifier.width(8.dp))    
                        Text(    
                            text = "View Once - click to open",    
                            fontSize = 14.sp,    
                            color = colorScheme.primary,    
                            fontWeight = FontWeight.Medium    
                        )    
                        Spacer(modifier = Modifier.width(8.dp))    
                        // Small indicator arrow    
                        Icon(    
                            imageVector = Icons.Filled.KeyboardArrowRight,    
                            contentDescription = "Click to view",    
                            modifier = Modifier.size(16.dp),    
                            tint = colorScheme.primary.copy(alpha = 0.7f)    
                        )    
                    }    
                } else if (message.isViewOnce && message.senderPeerID == meshService.myPeerID) {    
                    // Show lock icon for sender's own view-once messages    
                    Icon(    
                        imageVector = Icons.Filled.Lock,    
                        contentDescription = "View once message (sent)",    
                        modifier = Modifier    
                            .size(12.dp)    
                            .padding(end = 4.dp),    
                        tint = Color(0xFF00C853).copy(alpha = 0.7f)    
                    )    
                }    
                    
                // Show pin icon for pinned messages    
                if (message.isPinned) {    
                    Icon(    
                        imageVector = Icons.Filled.PushPin,    
                        contentDescription = "Pinned message",    
                        modifier = Modifier    
                            .size(14.dp)    
                            .padding(end = 4.dp),    
                        tint = Color(0xFF007AFF) // Blue color for pin icon    
                    )    
                }    
                    
                // Show message content based on view-once status    
                if (message.isViewOnce && message.senderPeerID != meshService.myPeerID && !isViewOnceOpened) {    
                    // Don't show content for unopened view-once messages    
                } else {    
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
            }    
                
            // Delivery status for private messages    
            if (message.isPrivate && message.sender == currentUserNickname) {    
                message.deliveryStatus?.let { status ->    
                    DeliveryStatusIcon(status = status)    
                }    
            }    
        }    
            
        // Pin/Unpin dialog - same pattern as settings theme dialog
        if (showPinMenu && canPinMessages && !message.isViewOnce) {
            AlertDialog(
                onDismissRequest = { showPinMenu = false },
                title = {
                    Text(
                        text = if (isPinned) "Unpin Message" else "Pin Message",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                text = {
                    Text(
                        text = if (isPinned) {
                            "Remove this message from pinned?"
                        } else {
                            "Pin this message to the top of the chat?"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showPinMenu = false
                            if (isPinned) {
                                onUnpinMessage()
                            } else {
                                onPinMessage(message)
                            }
                        }
                    ) {
                        Text(if (isPinned) "Unpin" else "Pin")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPinMenu = false }) {
                        Text("Cancel")
                    }
                }
            )
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
