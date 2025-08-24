package com.renchat.android.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.renchat.android.R

/**
 * UI components for moderation and community reporting
 */

/**
 * Report user dialog
 */
@Composable
fun ReportUserDialog(
    targetNickname: String,
    onReportSubmit: (ReportReason, String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedReason by remember { mutableStateOf(ReportReason.SPAM) }
    var description by remember { mutableStateOf("") }
    var showConfirmation by remember { mutableStateOf(false) }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Report User",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                    
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Target user info
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Reporting: $targetNickname",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                // Reason selection
                Text(
                    text = "Reason for report:",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ReportReason.values().forEach { reason ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedReason = reason }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedReason == reason,
                                onClick = { selectedReason = reason }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = reason.getDisplayName(),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                                Text(
                                    text = reason.getDescription(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                // Description field
                Text(
                    text = "Additional details (optional):",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    placeholder = { Text("Provide more context about the issue...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    maxLines = 4
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    
                    Button(
                        onClick = {
                            showConfirmation = true
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            Icons.Default.Flag,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Report")
                    }
                }
                
                // Confirmation dialog
                if (showConfirmation) {
                    AlertDialog(
                        onDismissRequest = { showConfirmation = false },
                        title = { Text("Confirm Report") },
                        text = { 
                            Text("Are you sure you want to report $targetNickname for ${selectedReason.getDisplayName().lowercase()}?")
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    onReportSubmit(selectedReason, description)
                                    showConfirmation = false
                                }
                            ) {
                                Text("Yes, Report", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showConfirmation = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * Moderation status indicator
 */
@Composable
fun ModerationStatusBadge(
    userProfile: UserModerationProfile,
    modifier: Modifier = Modifier
) {
    if (userProfile.riskLevel == RiskLevel.LOW && userProfile.warningCount == 0) {
        return // No badge needed for clean users
    }
    
    val (color, icon, text) = when (userProfile.riskLevel) {
        RiskLevel.LOW -> Triple(
            Color(0xFFFFA726), // Orange
            Icons.Default.Warning,
            "Low Risk"
        )
        RiskLevel.MEDIUM -> Triple(
            Color(0xFFFF7043), // Deep Orange
            Icons.Default.Flag,
            "Medium Risk"
        )
        RiskLevel.HIGH -> Triple(
            Color(0xFFE53935), // Red
            Icons.Default.Error,
            "High Risk"
        )
        RiskLevel.CRITICAL -> Triple(
            Color(0xFFB71C1C), // Dark Red
            Icons.Default.Block,
            "Critical Risk"
        )
    }
    
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.1f)
        ),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = color
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontSize = 10.sp
            )
        }
    }
}

/**
 * Quick report button
 */
@Composable
fun QuickReportButton(
    onReportClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onReportClick,
        modifier = modifier
    ) {
        Icon(
            Icons.Default.Flag,
            contentDescription = "Report User",
            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
        )
    }
}

/**
 * Spam warning indicator
 */
@Composable
fun SpamWarningIndicator(
    warningCount: Int,
    modifier: Modifier = Modifier
) {
    if (warningCount == 0) return
    
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(10.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = warningCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                fontSize = 9.sp
            )
        }
    }
}

/**
 * User moderation menu
 */
@Composable
fun UserModerationMenu(
    targetPeerID: String,
    targetNickname: String,
    userProfile: UserModerationProfile,
    onReportUser: () -> Unit,
    onBlockUser: () -> Unit,
    onViewProfile: () -> Unit,
    expanded: Boolean,
    onDismiss: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        // Report option
        DropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Flag,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Report User")
                }
            },
            onClick = {
                onReportUser()
                onDismiss()
            }
        )
        
        // Block option
        DropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Block,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Block User")
                }
            },
            onClick = {
                onBlockUser()
                onDismiss()
            }
        )
        
        // View profile option
        DropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("View Profile")
                }
            },
            onClick = {
                onViewProfile()
                onDismiss()
            }
        )
    }
}

// Extension functions for ReportReason
fun ReportReason.getDisplayName(): String {
    return when (this) {
        ReportReason.SPAM -> "Spam"
        ReportReason.HARASSMENT -> "Harassment"
        ReportReason.INAPPROPRIATE_CONTENT -> "Inappropriate Content"
        ReportReason.SCAM -> "Scam/Fraud"
        ReportReason.HATE_SPEECH -> "Hate Speech"
        ReportReason.IMPERSONATION -> "Impersonation"
        ReportReason.OTHER -> "Other"
    }
}

fun ReportReason.getDescription(): String {
    return when (this) {
        ReportReason.SPAM -> "Repeated unwanted messages or promotional content"
        ReportReason.HARASSMENT -> "Targeting, bullying, or threatening behavior"
        ReportReason.INAPPROPRIATE_CONTENT -> "Offensive, explicit, or unsuitable content"
        ReportReason.SCAM -> "Attempting to defraud or steal from users"
        ReportReason.HATE_SPEECH -> "Content promoting hatred or discrimination"
        ReportReason.IMPERSONATION -> "Pretending to be someone else"
        ReportReason.OTHER -> "Other violations not listed above"
    }
}