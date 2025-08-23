package com.renchat.android.nostr

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.Socket
import java.security.MessageDigest
import java.util.*
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Nostr relay client for location-based messaging
 * Direct port from BitChat iOS implementation for 100% compatibility
 */
class NostrRelay(
    private val relayUrl: String,
    private val onMessage: (NostrEventRelay) -> Unit,
    private val onConnectionChange: (Boolean) -> Unit
) {
    companion object {
        private const val TAG = "NostrRelay"
        private const val CONNECTION_TIMEOUT = 10000
        private const val READ_TIMEOUT = 30000
    }

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var connectionJob: Job? = null
    private var readerJob: Job? = null
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected
    
    private val subscriptions = mutableMapOf<String, NostrSubscription>()
    private val seenEvents = mutableSetOf<String>()

    fun connect() {
        Log.d(TAG, "Connecting to relay: $relayUrl")
        
        connectionJob?.cancel()
        connectionJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val (host, port, useSSL) = parseRelayUrl(relayUrl)
                
                socket = if (useSSL) {
                    val sslSocketFactory = SSLSocketFactory.getDefault()
                    val sslSocket = sslSocketFactory.createSocket(host, port) as SSLSocket
                    sslSocket.soTimeout = READ_TIMEOUT
                    sslSocket
                } else {
                    val plainSocket = Socket(host, port)
                    plainSocket.soTimeout = READ_TIMEOUT
                    plainSocket
                }
                
                writer = PrintWriter(OutputStreamWriter(socket!!.getOutputStream()), true)
                reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
                
                _isConnected.value = true
                onConnectionChange(true)
                
                Log.d(TAG, "Connected to relay: $relayUrl")
                
                // Start reading messages
                startReading()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to relay: ${e.message}")
                _isConnected.value = false
                onConnectionChange(false)
            }
        }
    }
    
    fun disconnect() {
        Log.d(TAG, "Disconnecting from relay: $relayUrl")
        
        connectionJob?.cancel()
        readerJob?.cancel()
        
        try {
            writer?.close()
            reader?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing connection: ${e.message}")
        }
        
        _isConnected.value = false
        onConnectionChange(false)
    }
    
    fun subscribe(subscription: NostrSubscription) {
        subscriptions[subscription.id] = subscription
        
        if (_isConnected.value) {
            sendSubscription(subscription)
        }
    }
    
    fun unsubscribe(subscriptionId: String) {
        subscriptions.remove(subscriptionId)
        
        if (_isConnected.value) {
            val closeMessage = JSONArray().apply {
                put("CLOSE")
                put(subscriptionId)
            }
            sendMessage(closeMessage.toString())
        }
    }
    
    fun publishEvent(event: NostrEventRelay) {
        if (_isConnected.value) {
            val eventMessage = JSONArray().apply {
                put("EVENT")
                put(event.toJson())
            }
            sendMessage(eventMessage.toString())
            Log.d(TAG, "Published event: ${event.id}")
        } else {
            Log.w(TAG, "Cannot publish event - not connected")
        }
    }
    
    fun publishEvent(event: NostrEvent) {
        if (_isConnected.value) {
            val eventMessage = JSONArray().apply {
                put("EVENT")
                put(event.toJson())
            }
            sendMessage(eventMessage.toString())
            Log.d(TAG, "Published event: ${event.id}")
        } else {
            Log.w(TAG, "Cannot publish event - not connected")
        }
    }
    
    private fun parseRelayUrl(url: String): Triple<String, Int, Boolean> {
        val cleanUrl = url.removePrefix("ws://").removePrefix("wss://")
        val useSSL = url.startsWith("wss://")
        val defaultPort = if (useSSL) 443 else 80
        
        val parts = cleanUrl.split(":")
        val host = parts[0]
        val port = if (parts.size > 1) {
            parts[1].split("/")[0].toIntOrNull() ?: defaultPort
        } else {
            defaultPort
        }
        
        return Triple(host, port, useSSL)
    }
    
    private fun startReading() {
        readerJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                while (isActive && _isConnected.value) {
                    val line = reader?.readLine()
                    if (line != null) {
                        handleMessage(line)
                    } else {
                        Log.w(TAG, "Received null line, connection may be closed")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading from relay: ${e.message}")
            } finally {
                _isConnected.value = false
                onConnectionChange(false)
            }
        }
    }
    
    private fun sendSubscription(subscription: NostrSubscription) {
        val reqMessage = JSONArray().apply {
            put("REQ")
            put(subscription.id)
            put(subscription.filter.toJson())
        }
        sendMessage(reqMessage.toString())
        Log.d(TAG, "Sent subscription: ${subscription.id}")
    }
    
    private fun sendMessage(message: String) {
        try {
            writer?.println(message)
            Log.v(TAG, "Sent: $message")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message: ${e.message}")
        }
    }
    
    private fun handleMessage(message: String) {
        try {
            Log.v(TAG, "Received: $message")
            
            val jsonArray = JSONArray(message)
            if (jsonArray.length() < 2) return
            
            when (val messageType = jsonArray.getString(0)) {
                "EVENT" -> {
                    if (jsonArray.length() >= 3) {
                        val subscriptionId = jsonArray.getString(1)
                        val eventJson = jsonArray.getJSONObject(2)
                        val event = NostrEventRelay.fromJson(eventJson)
                        
                        // Deduplicate events
                        if (!seenEvents.contains(event.id)) {
                            seenEvents.add(event.id)
                            onMessage(event)
                            Log.d(TAG, "Received event: ${event.id} for subscription: $subscriptionId")
                        }
                    }
                }
                "NOTICE" -> {
                    if (jsonArray.length() >= 2) {
                        val notice = jsonArray.getString(1)
                        Log.i(TAG, "Relay notice: $notice")
                    }
                }
                "EOSE" -> {
                    if (jsonArray.length() >= 2) {
                        val subscriptionId = jsonArray.getString(1)
                        Log.d(TAG, "End of stored events for subscription: $subscriptionId")
                    }
                }
                "OK" -> {
                    if (jsonArray.length() >= 3) {
                        val eventId = jsonArray.getString(1)
                        val accepted = jsonArray.getBoolean(2)
                        val message = if (jsonArray.length() >= 4) jsonArray.getString(3) else ""
                        Log.d(TAG, "Event $eventId ${if (accepted) "accepted" else "rejected"}: $message")
                    }
                }
                else -> {
                    Log.w(TAG, "Unknown message type: $messageType")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: ${e.message}")
        }
    }
}

/**
 * Nostr event representation
 */
data class NostrEventRelay(
    val id: String,
    val pubkey: String,
    val created_at: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String
) {
    companion object {
        fun fromJson(json: JSONObject): NostrEventRelay {
            val tags = mutableListOf<List<String>>()
            val tagsArray = json.getJSONArray("tags")
            for (i in 0 until tagsArray.length()) {
                val tagArray = tagsArray.getJSONArray(i)
                val tag = mutableListOf<String>()
                for (j in 0 until tagArray.length()) {
                    tag.add(tagArray.getString(j))
                }
                tags.add(tag)
            }
            
            return NostrEventRelay(
                id = json.getString("id"),
                pubkey = json.getString("pubkey"),
                created_at = json.getLong("created_at"),
                kind = json.getInt("kind"),
                tags = tags,
                content = json.getString("content"),
                sig = json.getString("sig")
            )
        }
    }
    
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("pubkey", pubkey)
        json.put("created_at", created_at)
        json.put("kind", kind)
        json.put("content", content)
        json.put("sig", sig)
        
        val tagsArray = JSONArray()
        tags.forEach { tag ->
            val tagArray = JSONArray()
            tag.forEach { tagArray.put(it) }
            tagsArray.put(tagArray)
        }
        json.put("tags", tagsArray)
        
        return json
    }
    
    fun getEventHash(): String {
        val serialized = JSONArray().apply {
            put(0)
            put(pubkey)
            put(created_at)
            put(kind)
            put(JSONArray().apply { tags.forEach { tag -> put(JSONArray().apply { tag.forEach { put(it) } }) } })
            put(content)
        }.toString()
        
        return sha256(serialized)
    }
    
    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}

