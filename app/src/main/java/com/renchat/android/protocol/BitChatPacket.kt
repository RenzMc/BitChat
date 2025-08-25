package com.renchat.android.protocol

/**
 * BitChatPacket compatibility - alias for RenChatPacket to ensure cross-compatibility
 * This allows RenChat to work with BitChat protocol seamlessly
 */
typealias BitchatPacket = RenChatPacket

/**
 * Protocol compatibility layer for seamless communication between BitChat and RenChat
 */
object ProtocolCompatibility {
    
    /**
     * Create a BitChatPacket (alias for RenChatPacket) for sending to BitChat devices
     */
    fun createBitChatPacket(
        type: UByte,
        ttl: UByte,
        senderID: String,
        payload: ByteArray
    ): BitchatPacket {
        return RenChatPacket(
            type = type,
            ttl = ttl,
            senderID = senderID,
            payload = payload
        )
    }
    
    /**
     * Convert RenChatPacket to BitchatPacket (they're the same, but this makes intent clear)
     */
    fun toBitChatPacket(renChatPacket: RenChatPacket): BitchatPacket {
        return renChatPacket
    }
    
    /**
     * Convert BitchatPacket to RenChatPacket (they're the same, but this makes intent clear)  
     */
    fun toRenChatPacket(bitChatPacket: BitchatPacket): RenChatPacket {
        return bitChatPacket
    }
}