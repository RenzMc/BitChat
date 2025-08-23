package com.renchat.android.ui

import com.renchat.android.mesh.BluetoothMeshService
import com.renchat.android.model.RenChatMessage
import java.util.*

/**
 * Handles processing of IRC-style commands
 */
class CommandProcessor(
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val channelManager: ChannelManager,
    private val privateChatManager: PrivateChatManager,
    private val groupManager: GroupManager? = null
) {
    
    // Group command processor
    private val groupCommandProcessor by lazy {
        groupManager?.let { GroupCommandProcessor(state, messageManager, it) }
    }
    
    // Available commands list
    private val baseCommands = listOf(
        CommandSuggestion("/block", emptyList(), "[nickname]", "block or list blocked peers"),
        CommandSuggestion("/channels", emptyList(), null, "show all discovered channels"),
        CommandSuggestion("/clear", emptyList(), null, "clear chat messages"),
        CommandSuggestion("/group", emptyList(), "<command>", "WhatsApp-style group management"),
        CommandSuggestion("/help", emptyList(), null, "show available commands"),
        CommandSuggestion("/hug", emptyList(), "<nickname>", "send someone a warm hug"),
        CommandSuggestion("/j", listOf("/join"), "<channel>", "join or create a channel"),
        CommandSuggestion("/l", listOf("/leave", "/part"), "[channel]", "leave current or specified channel"),
        CommandSuggestion("/list", emptyList(), null, "list all available channels"),
        CommandSuggestion("/m", listOf("/msg"), "<nickname> [message]", "send private message"),
        CommandSuggestion("/me", emptyList(), "<action>", "perform an action"),
        CommandSuggestion("/nick", emptyList(), "<nickname>", "change your nickname"),
        CommandSuggestion("/slap", emptyList(), "<nickname>", "slap someone with a trout"),
        CommandSuggestion("/topic", emptyList(), "[text]", "view or set channel topic"),
        CommandSuggestion("/unblock", emptyList(), "<nickname>", "unblock a peer"),
        CommandSuggestion("/w", emptyList(), null, "see who's online")
    )
    
    // MARK: - Command Processing
    
    fun processCommand(command: String, meshService: BluetoothMeshService, myPeerID: String, onSendMessage: (String, List<String>, String?) -> Unit, viewModel: ChatViewModel? = null): Boolean {
        if (!command.startsWith("/")) return false
        
        // Validate command
        if (!validateCommand(command)) return true
        
        val parts = command.split(" ")
        val cmd = parts.first()
        
        // Check for group commands first
        if (cmd == "/group") {
            val processor = groupCommandProcessor
            if (processor != null) {
                return processor.processGroupCommand(command, meshService, myPeerID) { content, recipients, channel ->
                    onSendMessage(content, recipients ?: emptyList(), channel)
                }
            }
        }
        
        when (cmd) {
            "/j", "/join" -> handleJoinCommand(parts, myPeerID)
            "/l", "/leave", "/part" -> handleLeaveCommand(parts)
            "/m", "/msg" -> handleMessageCommand(parts, meshService)
            "/w" -> handleWhoCommand(meshService, viewModel)
            "/clear" -> handleClearCommand()
            "/pass" -> handlePassCommand(parts, myPeerID)
            "/block" -> handleBlockCommand(parts, meshService)
            "/unblock" -> handleUnblockCommand(parts, meshService)
            "/hug" -> handleActionCommand(parts, "gives", "a warm hug 🫂", meshService, myPeerID, onSendMessage)
            "/slap" -> handleActionCommand(parts, "slaps", "around a bit with a large trout 🐟", meshService, myPeerID, onSendMessage)
            "/channels" -> handleChannelsCommand()
            "/list" -> handleListCommand()
            "/help" -> handleHelpCommand()
            "/nick" -> handleNickCommand(parts, meshService)
            "/me" -> handleMeCommand(parts, meshService, myPeerID, onSendMessage)
            "/topic" -> handleTopicCommand(parts)
            else -> handleUnknownCommand(cmd)
        }
        
        return true
    }
    
    private fun handleJoinCommand(parts: List<String>, myPeerID: String) {
        if (parts.size > 1) {
            val channelName = parts[1]
            val channel = if (channelName.startsWith("#")) channelName else "#$channelName"
            val password = if (parts.size > 2) parts[2] else null
            val success = channelManager.joinChannel(channel, password, myPeerID)
            if (success) {
                val systemMessage = RenChatMessage(
                    sender = "system",
                    content = "joined channel $channel",
                    timestamp = Date(),
                    isRelay = false
                )
                messageManager.addMessage(systemMessage)
            }
        } else {
            val systemMessage = RenChatMessage(
                sender = "system",
                content = "usage: /join <channel>",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
        }
    }
    
    private fun handleMessageCommand(parts: List<String>, meshService: BluetoothMeshService) {
        if (parts.size > 1) {
            val targetName = parts[1].removePrefix("@")
            val peerID = getPeerIDForNickname(targetName, meshService)
            
            if (peerID != null) {
                val success = privateChatManager.startPrivateChat(peerID, meshService)
                
                if (success) {
                    if (parts.size > 2) {
                        val messageContent = parts.drop(2).joinToString(" ")
                        val recipientNickname = getPeerNickname(peerID, meshService)
                        privateChatManager.sendPrivateMessage(
                            messageContent, 
                            peerID, 
                            recipientNickname,
                            state.getNicknameValue(),
                            getMyPeerID(meshService)
                        ) { content, peerIdParam, recipientNicknameParam, messageId ->
                            // This would trigger the actual mesh service send
                            sendPrivateMessageVia(meshService, content, peerIdParam, recipientNicknameParam, messageId)
                        }
                    } else {
                        val systemMessage = RenChatMessage(
                            sender = "system",
                            content = "started private chat with $targetName",
                            timestamp = Date(),
                            isRelay = false
                        )
                        messageManager.addMessage(systemMessage)
                    }
                }
            } else {
                val systemMessage = RenChatMessage(
                    sender = "system",
                    content = "user '$targetName' not found. they may be offline or using a different nickname.",
                    timestamp = Date(),
                    isRelay = false
                )
                messageManager.addMessage(systemMessage)
            }
        } else {
            val systemMessage = RenChatMessage(
                sender = "system",
                content = "usage: /msg <nickname> [message]",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
        }
    }
    
    private fun handleWhoCommand(meshService: BluetoothMeshService, viewModel: ChatViewModel? = null) {
        // Channel-aware who command (matches iOS behavior)
        val (peerList, contextDescription) = if (viewModel != null) {
            when (val selectedChannel = viewModel.selectedLocationChannel.value) {
                is com.renchat.android.geohash.ChannelID.Mesh,
                null -> {
                    // Mesh channel: show Bluetooth-connected peers
                    val connectedPeers = state.getConnectedPeersValue()
                    val peerList = connectedPeers.joinToString(", ") { peerID ->
                        getPeerNickname(peerID, meshService)
                    }
                    Pair(peerList, "online users")
                }
                
                is com.renchat.android.geohash.ChannelID.Location -> {
                    // Location channel: show geohash participants
                    val geohashPeople = viewModel.geohashPeople.value ?: emptyList()
                    val currentNickname = state.getNicknameValue()
                    
                    val participantList = geohashPeople.mapNotNull { person ->
                        val displayName = person.displayName
                        // Exclude self from list
                        if (displayName.startsWith("${currentNickname}#")) {
                            null
                        } else {
                            displayName
                        }
                    }.joinToString(", ")
                    
                    Pair(participantList, "participants in ${selectedChannel.channel.geohash}")
                }
            }
        } else {
            // Fallback to mesh behavior
            val connectedPeers = state.getConnectedPeersValue()
            val peerList = connectedPeers.joinToString(", ") { peerID ->
                getPeerNickname(peerID, meshService)
            }
            Pair(peerList, "online users")
        }
        
        val systemMessage = RenChatMessage(
            sender = "system",
            content = if (peerList.isEmpty()) {
                "no one else is around right now."
            } else {
                "$contextDescription: $peerList"
            },
            timestamp = Date(),
            isRelay = false
        )
        messageManager.addMessage(systemMessage)
    }
    
    private fun handleClearCommand() {
        when {
            state.getSelectedPrivateChatPeerValue() != null -> {
                // Clear private chat
                val peerID = state.getSelectedPrivateChatPeerValue()!!
                messageManager.clearPrivateMessages(peerID)
            }
            state.getCurrentChannelValue() != null -> {
                // Clear channel messages
                val channel = state.getCurrentChannelValue()!!
                messageManager.clearChannelMessages(channel)
            }
            else -> {
                // Clear main messages
                messageManager.clearMessages()
            }
        }
    }

    private fun handlePassCommand(parts: List<String>, peerID: String) {
        val currentChannel = state.getCurrentChannelValue()

        if (currentChannel == null) {
            val systemMessage = RenChatMessage(
                sender = "system",
                content = "you must be in a channel to set a password.",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
            return
        }

        if (parts.size == 2){
            if(!channelManager.isChannelCreator(channel = currentChannel, peerID = peerID)){
                val systemMessage = RenChatMessage(
                    sender = "system",
                    content = "you must be the channel creator to set a password.",
                    timestamp = Date(),
                    isRelay = false
                )
                channelManager.addChannelMessage(currentChannel,systemMessage,null)
                return
            }
            val newPassword = parts[1]
            channelManager.setChannelPassword(currentChannel, newPassword)
            val systemMessage = RenChatMessage(
                sender = "system",
                content = "password changed for channel $currentChannel",
                timestamp = Date(),
                isRelay = false
            )
            channelManager.addChannelMessage(currentChannel,systemMessage,null)
        }
        else{
            val systemMessage = RenChatMessage(
                sender = "system",
                content = "usage: /pass <password>",
                timestamp = Date(),
                isRelay = false
            )
            channelManager.addChannelMessage(currentChannel,systemMessage,null)
        }
    }
    
    private fun handleBlockCommand(parts: List<String>, meshService: BluetoothMeshService) {
        if (parts.size > 1) {
            val targetName = parts[1].removePrefix("@")
            privateChatManager.blockPeerByNickname(targetName, meshService)
        } else {
            // List blocked users
            val blockedInfo = privateChatManager.listBlockedUsers()
            val systemMessage = RenChatMessage(
                sender = "system",
                content = blockedInfo,
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
        }
    }
    
    private fun handleUnblockCommand(parts: List<String>, meshService: BluetoothMeshService) {
        if (parts.size > 1) {
            val targetName = parts[1].removePrefix("@")
            privateChatManager.unblockPeerByNickname(targetName, meshService)
        } else {
            val systemMessage = RenChatMessage(
                sender = "system",
                content = "usage: /unblock <nickname>",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
        }
    }
    
    private fun handleActionCommand(
        parts: List<String>, 
        verb: String, 
        object_: String, 
        meshService: BluetoothMeshService,
        myPeerID: String,
        onSendMessage: (String, List<String>, String?) -> Unit
    ) {
        if (parts.size > 1) {
            val targetName = parts[1].removePrefix("@")
            val actionMessage = "* ${state.getNicknameValue() ?: "someone"} $verb $targetName $object_ *"
            
            // Send as regular message
            if (state.getSelectedPrivateChatPeerValue() != null) {
                val peerID = state.getSelectedPrivateChatPeerValue()!!
                privateChatManager.sendPrivateMessage(
                    actionMessage,
                    peerID,
                    getPeerNickname(peerID, meshService),
                    state.getNicknameValue(),
                    myPeerID
                ) { content, peerIdParam, recipientNicknameParam, messageId ->
                    sendPrivateMessageVia(meshService, content, peerIdParam, recipientNicknameParam, messageId)
                }
            } else {
                val message = RenChatMessage(
                    sender = state.getNicknameValue() ?: myPeerID,
                    content = actionMessage,
                    timestamp = Date(),
                    isRelay = false,
                    senderPeerID = myPeerID,
                    channel = state.getCurrentChannelValue()
                )
                
                if (state.getCurrentChannelValue() != null) {
                    channelManager.addChannelMessage(state.getCurrentChannelValue()!!, message, myPeerID)
                    onSendMessage(actionMessage, emptyList(), state.getCurrentChannelValue())
                } else {
                    messageManager.addMessage(message)
                    onSendMessage(actionMessage, emptyList(), null)
                }
            }
        } else {
            val systemMessage = RenChatMessage(
                sender = "system",
                content = "usage: /${parts[0].removePrefix("/")} <nickname>",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
        }
    }
    
    private fun handleChannelsCommand() {
        val allChannels = channelManager.getJoinedChannelsList()
        val channelList = if (allChannels.isEmpty()) {
            "no channels joined"
        } else {
            "joined channels: ${allChannels.joinToString(", ")}"
        }
        
        val systemMessage = RenChatMessage(
            sender = "system",
            content = channelList,
            timestamp = Date(),
            isRelay = false
        )
        messageManager.addMessage(systemMessage)
    }
    
    private fun handleLeaveCommand(parts: List<String>) {
        val channelToLeave = if (parts.size > 1) {
            val channelName = parts[1]
            if (channelName.startsWith("#")) channelName else "#$channelName"
        } else {
            state.getCurrentChannelValue()
        }
        
        if (channelToLeave != null) {
            channelManager.leaveChannel(channelToLeave)
            val systemMessage = RenChatMessage(
                sender = "system",
                content = "left channel $channelToLeave",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
        } else {
            val systemMessage = RenChatMessage(
                sender = "system",
                content = "no channel to leave. usage: /leave [channel]",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
        }
    }
    
    private fun handleListCommand() {
        val allChannels = channelManager.getJoinedChannelsList()
        val discoveredChannels = state.getConnectedPeersValue().map { "#general" } // Simplified
        val channelList = if (allChannels.isEmpty() && discoveredChannels.isEmpty()) {
            "no channels available"
        } else {
            val joinedText = if (allChannels.isNotEmpty()) "joined: ${allChannels.joinToString(", ")}" else ""
            val discoveredText = if (discoveredChannels.isNotEmpty()) "available: ${discoveredChannels.joinToString(", ")}" else ""
            listOf(joinedText, discoveredText).filter { it.isNotEmpty() }.joinToString(" | ")
        }
        
        val systemMessage = RenChatMessage(
            sender = "system",
            content = channelList,
            timestamp = Date(),
            isRelay = false
        )
        messageManager.addMessage(systemMessage)
    }
    
    private fun handleHelpCommand() {
        val helpText = baseCommands.joinToString("\n") { 
            val syntax = if (it.syntax != null) " ${it.syntax}" else ""
            "${it.command}$syntax - ${it.description}"
        }
        
        val systemMessage = RenChatMessage(
            sender = "system",
            content = "available commands:\n$helpText",
            timestamp = Date(),
            isRelay = false
        )
        messageManager.addMessage(systemMessage)
    }
    
    private fun handleNickCommand(parts: List<String>, meshService: BluetoothMeshService) {
        if (parts.size > 1) {
            val newNickname = parts.drop(1).joinToString(" ").trim()
            val oldNickname = state.getNicknameValue() ?: "unknown"
            
            // Validate nickname (no special characters, reasonable length)
            if (newNickname.length > 20) {
                val systemMessage = RenChatMessage(
                    sender = "system",
                    content = "nickname too long (max 20 characters)",
                    timestamp = Date(),
                    isRelay = false
                )
                messageManager.addMessage(systemMessage)
                return
            }
            
            if (newNickname.contains(Regex("[^a-zA-Z0-9_-]"))) {
                val systemMessage = RenChatMessage(
                    sender = "system",
                    content = "nickname can only contain letters, numbers, underscore and dash",
                    timestamp = Date(),
                    isRelay = false
                )
                messageManager.addMessage(systemMessage)
                return
            }
            
            // Update the nickname through the state manager
            state.setNickname(newNickname)
            
            val systemMessage = RenChatMessage(
                sender = "system",
                content = "nickname changed from '$oldNickname' to '$newNickname'",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
            
            // Broadcast the name change
            meshService.sendBroadcastAnnounce()
        } else {
            val systemMessage = RenChatMessage(
                sender = "system",
                content = "usage: /nick <new_nickname>",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
        }
    }
    
    private fun handleMeCommand(
        parts: List<String>, 
        meshService: BluetoothMeshService,
        myPeerID: String,
        onSendMessage: (String, List<String>, String?) -> Unit
    ) {
        if (parts.size > 1) {
            val actionText = parts.drop(1).joinToString(" ")
            val actionMessage = "* ${state.getNicknameValue() ?: "someone"} $actionText *"
            
            // Send as regular message
            if (state.getSelectedPrivateChatPeerValue() != null) {
                val peerID = state.getSelectedPrivateChatPeerValue()!!
                privateChatManager.sendPrivateMessage(
                    actionMessage,
                    peerID,
                    getPeerNickname(peerID, meshService),
                    state.getNicknameValue(),
                    myPeerID
                ) { content, peerIdParam, recipientNicknameParam, messageId ->
                    sendPrivateMessageVia(meshService, content, peerIdParam, recipientNicknameParam, messageId)
                }
            } else {
                val message = RenChatMessage(
                    sender = state.getNicknameValue() ?: myPeerID,
                    content = actionMessage,
                    timestamp = Date(),
                    isRelay = false,
                    senderPeerID = myPeerID,
                    channel = state.getCurrentChannelValue()
                )
                
                if (state.getCurrentChannelValue() != null) {
                    channelManager.addChannelMessage(state.getCurrentChannelValue()!!, message, myPeerID)
                    onSendMessage(actionMessage, emptyList(), state.getCurrentChannelValue())
                } else {
                    messageManager.addMessage(message)
                    onSendMessage(actionMessage, emptyList(), null)
                }
            }
        } else {
            val systemMessage = RenChatMessage(
                sender = "system",
                content = "usage: /me <action>",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
        }
    }
    
    private fun handleTopicCommand(parts: List<String>) {
        val currentChannel = state.getCurrentChannelValue()
        
        if (currentChannel == null) {
            val systemMessage = RenChatMessage(
                sender = "system",
                content = "you must be in a channel to view or set the topic.",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
            return
        }
        
        if (parts.size > 1) {
            val newTopic = parts.drop(1).joinToString(" ")
            // For now, just show the topic since we don't have topic storage implemented
            val systemMessage = RenChatMessage(
                sender = "system",
                content = "topic for $currentChannel: $newTopic",
                timestamp = Date(),
                isRelay = false
            )
            channelManager.addChannelMessage(currentChannel, systemMessage, null)
        } else {
            val systemMessage = RenChatMessage(
                sender = "system",
                content = "no topic set for $currentChannel. usage: /topic <text>",
                timestamp = Date(),
                isRelay = false
            )
            channelManager.addChannelMessage(currentChannel, systemMessage, null)
        }
    }
    
    private fun handleUnknownCommand(cmd: String) {
        val systemMessage = RenChatMessage(
            sender = "system",
            content = "unknown command: $cmd. type /help to see available commands.",
            timestamp = Date(),
            isRelay = false
        )
        messageManager.addMessage(systemMessage)
    }
    
    // MARK: - Command Validation
    
    private fun validateCommand(command: String): Boolean {
        // Prevent command injection and validate format
        if (command.length > 500) {
            val systemMessage = RenChatMessage(
                sender = "system",
                content = "command too long (max 500 characters)",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
            return false
        }
        
        return true
    }
    
    // MARK: - Command Autocomplete

    fun updateCommandSuggestions(input: String) {
        if (!input.startsWith("/")) {
            state.setShowCommandSuggestions(false)
            state.setCommandSuggestions(emptyList())
            return
        }
        
        // Get all available commands based on context
        val allCommands = getAllAvailableCommands()
        
        // Filter commands based on input
        val filteredCommands = filterCommands(allCommands, input.lowercase())
        
        if (filteredCommands.isNotEmpty()) {
            state.setCommandSuggestions(filteredCommands)
            state.setShowCommandSuggestions(true)
        } else {
            state.setShowCommandSuggestions(false)
            state.setCommandSuggestions(emptyList())
        }
    }
    
    private fun getAllAvailableCommands(): List<CommandSuggestion> {
        // Add channel-specific commands if in a channel
        val channelCommands = if (state.getCurrentChannelValue() != null) {
            listOf(
                CommandSuggestion("/pass", emptyList(), "[password]", "change channel password"),
                CommandSuggestion("/save", emptyList(), null, "save channel messages locally"),
                CommandSuggestion("/transfer", emptyList(), "<nickname>", "transfer channel ownership")
            )
        } else {
            emptyList()
        }
        
        return baseCommands + channelCommands
    }
    
    private fun filterCommands(commands: List<CommandSuggestion>, input: String): List<CommandSuggestion> {
        return commands.filter { command ->
            // Check primary command
            command.command.startsWith(input) ||
            // Check aliases
            command.aliases.any { it.startsWith(input) }
        }.sortedBy { it.command }
    }
    
    fun selectCommandSuggestion(suggestion: CommandSuggestion): String {
        state.setShowCommandSuggestions(false)
        state.setCommandSuggestions(emptyList())
        return "${suggestion.command} "
    }
    
    // MARK: - Mention Autocomplete
    
    fun updateMentionSuggestions(input: String, meshService: BluetoothMeshService) {
        // Check if input contains @ and we're at the end of a word or at the end of input
        val atIndex = input.lastIndexOf('@')
        if (atIndex == -1) {
            state.setShowMentionSuggestions(false)
            state.setMentionSuggestions(emptyList())
            return
        }
        
        // Get the text after the @ symbol
        val textAfterAt = input.substring(atIndex + 1)
        
        // If there's a space after @, don't show suggestions
        if (textAfterAt.contains(' ')) {
            state.setShowMentionSuggestions(false)
            state.setMentionSuggestions(emptyList())
            return
        }
        
        // Get all connected peer nicknames - now using direct access instead of reflection
        val peerNicknames = meshService.getPeerNicknames().values.toList()
        
        // Filter nicknames based on the text after @
        val filteredNicknames = peerNicknames.filter { nickname ->
            nickname.startsWith(textAfterAt, ignoreCase = true)
        }.sorted()
        
        if (filteredNicknames.isNotEmpty()) {
            state.setMentionSuggestions(filteredNicknames)
            state.setShowMentionSuggestions(true)
        } else {
            state.setShowMentionSuggestions(false)
            state.setMentionSuggestions(emptyList())
        }
    }
    
    fun selectMentionSuggestion(nickname: String, currentText: String): String {
        state.setShowMentionSuggestions(false)
        state.setMentionSuggestions(emptyList())
        
        // Find the last @ symbol position
        val atIndex = currentText.lastIndexOf('@')
        if (atIndex == -1) {
            return "$currentText@$nickname "
        }
        
        // Replace the text from the @ symbol to the end with the mention
        val textBeforeAt = currentText.substring(0, atIndex)
        return "$textBeforeAt@$nickname "
    }
    
    // MARK: - Utility Functions
    
    private fun getPeerIDForNickname(nickname: String, meshService: BluetoothMeshService): String? {
        return meshService.getPeerNicknames().entries.find { it.value == nickname }?.key
    }
    
    private fun getPeerNickname(peerID: String, meshService: BluetoothMeshService): String {
        return meshService.getPeerNicknames()[peerID] ?: peerID
    }
    
    private fun getMyPeerID(meshService: BluetoothMeshService): String {
        return meshService.myPeerID
    }
    
    private fun sendPrivateMessageVia(meshService: BluetoothMeshService, content: String, peerID: String, recipientNickname: String, messageId: String) {
        meshService.sendPrivateMessage(content, peerID, recipientNickname, messageId)
    }
}
