package com.renchat.android

import android.app.Application

/**
 * Main application class for RenChat Android
 */
class RenChatApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize relay directory (loads assets/nostr_relays.csv)
        com.renchat.android.nostr.RelayDirectory.initialize(this)
    }
}
