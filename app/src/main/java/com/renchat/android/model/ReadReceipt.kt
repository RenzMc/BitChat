package com.renchat.android.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.*

/**
 * Read receipt for messages
 * Compatible with iOS implementation
 */
@Parcelize
data class ReadReceipt(
    val originalMessageID: String,
    val readBy: String,
    val readAt: Date = Date()
) : Parcelable