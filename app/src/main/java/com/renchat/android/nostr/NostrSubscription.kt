package com.renchat.android.nostr

/**
 * Nostr subscription data class
 * Simple wrapper for subscription ID and filter
 */
data class NostrSubscription(
    val id: String,
    val filter: NostrFilter
) {
    override fun toString(): String {
        return "NostrSubscription(id='$id', filter=${filter.getDebugDescription()})"
    }
}