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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.renchat.android.model.RenChatMessage
import com.renchat.android.model.DeliveryStatus
import com.renchat.android.mesh.BluetoothMeshService
import java.text.SimpleDateFormat
import java.util.*

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

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
    ) {
        items(messages, key = { it.id }) { message ->
            val shouldShowMessage = when {
                !message.isViewOnce -> true
                message.senderPeerID == meshService.myPeerID -> true
                !viewedMessages.contains(message.id) -> true
                else -> false
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

    var isViewOnceOpened by remember(message.id) { mutableStateOf(false) }
    var showPinMenu by remember(message.id) { mutableStateOf(false) }

    // Base modifier for row
    val rowBaseModifier = Modifier.fillMaxWidth()

    // Attach pointerInput only when pinning is allowed and message is not view-once
    val rowModifier = if (canPinMessages && !message.isViewOnce) {
        rowBaseModifier.pointerInput(message.id) {
            detectTapGestures(
                onLongPress = {
                    try {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    } catch (_: Exception) {
                        // some devices may not support haptic -> ignore
                    }
                    showPinMenu = true
                },
                onTap = {
                    // optionally handle tap
                }
            )
        }
    } else {
        rowBaseModifier
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = rowModifier,
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.Top
            ) {
                if (message.isViewOnce && message.senderPeerID != meshService.myPeerID && !isViewOnceOpened) {
                    Row(
                        modifier = Modifier
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        isViewOnceOpened = true
                                        onViewOnceClick(message.id)
                                    }
                                )
                            }
                            .background(
                                colorScheme.primary.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowRight,
                            contentDescription = "Click to view",
                            modifier = Modifier.size(16.dp),
                            tint = colorScheme.primary.copy(alpha = 0.7f)
                        )
                    }
                } else if (message.isViewOnce && message.senderPeerID == meshService.myPeerID) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "View once message (sent)",
                        modifier = Modifier
                            .size(12.dp)
                            .padding(end = 4.dp),
                        tint = Color(0xFF00C853).copy(alpha = 0.7f)
                    )
                }

                if (message.isPinned) {
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        contentDescription = "Pinned message",
                        modifier = Modifier
                            .size(14.dp)
                            .padding(end = 4.dp),
                        tint = Color(0xFF007AFF)
                    )
                }

                // Only wrap text with SelectionContainer (so it doesn't conflict with long-press gestures)
                if (message.isViewOnce && message.senderPeerID != meshService.myPeerID && !isViewOnceOpened) {
                    // do nothing - unopened view-once hides content
                } else {
                    SelectionContainer {
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
            }

            if (message.isPrivate && message.sender == currentUserNickname) {
                message.deliveryStatus?.let { status ->
                    DeliveryStatusIcon(status = status)
                }
            }
        }

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
                color = Color(0xFF007AFF),
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
