package com.renchat.android.model

import java.util.Date

/**
 * Represents a person in geohash location channels
 */
data class GeoPerson(
    val id: String,          // pubkey hex
    val displayName: String, // nickname with collision suffix
    val lastSeen: Date,      // when last seen in this geohash
    val isOnline: Boolean = false,
    val isTeleported: Boolean = false
) {
    companion object {
        fun create(
            pubkeyHex: String,
            nickname: String,
            lastSeen: Date = Date(),
            isOnline: Boolean = false,
            isTeleported: Boolean = false
        ): GeoPerson {
            return GeoPerson(
                id = pubkeyHex,
                displayName = nickname,
                lastSeen = lastSeen,
                isOnline = isOnline,
                isTeleported = isTeleported
            )
        }
    }
}