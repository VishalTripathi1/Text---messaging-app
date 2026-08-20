package com.messaging.presentation.screen.chat

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.messaging.domain.entity.Message
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

/**
 * Formats a byte count as a human-readable size (B / KB / MB / GB), matching how every other
 * chat app displays attachment sizes. Previously this was hardcoded to always divide by 1024
 * and label the result "KB" - so a 23 MB video showed as "23552 KB" instead of "23 MB".
 */
fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var size = bytes.toDouble() / 1024.0
    var unitIndex = 0
    while (size >= 1024.0 && unitIndex < units.size - 1) {
        size /= 1024.0
        unitIndex++
    }
    return if (size < 10) {
        String.format(Locale.getDefault(), "%.1f %s", size, units[unitIndex])
    } else {
        String.format(Locale.getDefault(), "%.0f %s", size, units[unitIndex])
    }
}

/**
 * A file the user has picked but not yet sent - lets them review/remove it before it goes out.
 */
data class PendingAttachment(
    val fileBytes: ByteArray,
    val fileName: String,
    val mimeType: String,
    val messageType: String
)

/**
 * ChatScreen - Renders a conversation list of bubbles with double ticks, attachments, typing state, reactions, and search
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    navController: NavController,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val otherUserDisplayName by viewModel.otherUserDisplayName.collectAsState()
    val otherUserStatus by viewModel.otherUserStatus.collectAsState()
    val isPeerTyping by viewModel.isPeerTyping.collectAsState()

    var textState by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredMessages = remember(messages, searchQuery) {
        if (searchQuery.isBlank()) {
            messages
        } else {
            messages.filter { it.messageText?.contains(searchQuery, ignoreCase = true) == true }
        }
    }

    // Staged attachment: picking a file no longer sends it immediately. Instead it's held here
    // so the user can review it - and remove it via the "x" button - before it's actually sent.
    var pendingAttachment by remember { mutableStateOf<PendingAttachment?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val contentResolver = context.contentResolver
            try {
                var fileName = "attachment"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIndex)
                    }
                }
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                val fileBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }

                if (fileBytes != null) {
                    val msgType = when {
                        mimeType.startsWith("image/") -> "IMAGE"
                        mimeType.startsWith("video/") -> "VIDEO"
                        mimeType.startsWith("audio/") -> "VOICE"
                        else -> "DOCUMENT"
                    }
                    pendingAttachment = PendingAttachment(fileBytes, fileName, mimeType, msgType)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Auto scroll to bottom when new messages arrive.
    // The very first time messages load for this chat (opening it, or the initial sync/DB read
    // completing) we jump straight to the bottom instantly. Using animateScrollToItem here was
    // the bug: if the message count changed again shortly after (e.g. syncMessages() finishing,
    // or a new message arriving over the WebSocket) while the first smooth-scroll animation was
    // still in flight, this LaunchedEffect re-triggered and restarted the animation from
    // wherever it currently was - so the view could visually settle partway through a long
    // history instead of at the bottom. Only animate for messages that arrive after the chat is
    // already open and settled.
    var hasScrolledToInitialPosition by remember(viewModel.chatId) { mutableStateOf(false) }
    LaunchedEffect(filteredMessages.size) {
        if (filteredMessages.isNotEmpty()) {
            if (!hasScrolledToInitialPosition) {
                listState.scrollToItem(filteredMessages.size - 1)
                hasScrolledToInitialPosition = true
            } else {
                listState.animateScrollToItem(filteredMessages.size - 1)
            }
        }
    }

    // Monitor typing state
    LaunchedEffect(textState) {
        if (textState.isNotEmpty()) {
            viewModel.sendTypingState(true)
            delay(2000)
            viewModel.sendTypingState(false)
        } else {
            viewModel.sendTypingState(false)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search messages...") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Column {
                            Text(
                                text = otherUserDisplayName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            if (isPeerTyping) {
                                Text(
                                    text = "typing...",
                                    fontStyle = FontStyle.Italic,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Text(
                                    text = otherUserStatus.lowercase(Locale.ROOT),
                                    fontSize = 12.sp,
                                    color = if (otherUserStatus == "ONLINE") Color(0xFF4CAF50) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isSearchActive) {
                        IconButton(onClick = {
                            isSearchActive = false
                            searchQuery = ""
                        }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Close Search")
                        }
                    } else {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(imageVector = Icons.Default.Search, contentDescription = "Search Messages")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Messages Panel
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredMessages) { message ->
                    val isOwnMessage = message.senderId == viewModel.currentUserId
                    MessageBubble(
                        message = message,
                        isOwnMessage = isOwnMessage,
                        currentUserId = viewModel.currentUserId,
                        onReact = { reaction ->
                            viewModel.toggleReaction(message.id, reaction)
                        },
                        onEdit = { newText ->
                            viewModel.editMessage(message.id, newText)
                        },
                        onDelete = {
                            viewModel.deleteMessage(message.id)
                        }
                    )
                }
            }

            // Pending attachment preview - lets the user remove a wrongly-picked file before sending
            pendingAttachment?.let { attachment ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = attachment.fileName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${attachment.messageType} • ${formatFileSize(attachment.fileBytes.size.toLong())}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { pendingAttachment = null }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove Attachment",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Input Panel
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .imePadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = "Attach File",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextField(
                    value = textState,
                    onValueChange = { textState = it },
                    placeholder = { Text("Type a message...") },
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp)),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    maxLines = 4
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val attachment = pendingAttachment
                        if (attachment != null) {
                            viewModel.sendMediaMessage(
                                attachment.fileBytes,
                                attachment.fileName,
                                attachment.mimeType,
                                attachment.messageType
                            )
                            pendingAttachment = null
                            textState = ""
                        } else if (textState.isNotBlank()) {
                            viewModel.sendMessage(textState)
                            textState = ""
                        }
                    },
                    enabled = textState.isNotBlank() || pendingAttachment != null,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = Color.White,
                        disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    )
                ) {
                    Icon(imageVector = Icons.Default.Send, contentDescription = "Send Message")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    isOwnMessage: Boolean,
    currentUserId: Long,
    onReact: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit
) {
    val myCurrentReaction = message.reactions?.firstOrNull { it.userId == currentUserId }?.reaction
    val align = if (isOwnMessage) Alignment.End else Alignment.Start
    val bubbleColor = if (isOwnMessage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isOwnMessage) Color.White else MaterialTheme.colorScheme.onSurface

    var showOptions by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editVal by remember { mutableStateOf(message.messageText ?: "") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = align
    ) {
        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isOwnMessage) 16.dp else 4.dp,
                        bottomEnd = if (isOwnMessage) 4.dp else 16.dp
                    )
                )
                .background(bubbleColor)
                .combinedClickable(
                    onClick = {},
                    onLongClick = { showOptions = true }
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .widthIn(max = 280.dp)
        ) {
            Column {
                if (message.attachmentPath != null) {
                    AttachmentCard(
                        message = message,
                        isOwnMessage = isOwnMessage,
                        onLongClick = { showOptions = true }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (!message.messageText.isNullOrEmpty() && message.messageText != "[Attachment: ${message.attachmentFileName}]") {
                    Text(
                        text = message.messageText,
                        color = textColor,
                        fontSize = 15.sp,
                        fontStyle = if (message.isDeleted) FontStyle.Italic else FontStyle.Normal
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }

                if (!message.reactions.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        message.reactions.groupBy { it.reaction }.forEach { (emoji, list) ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(textColor.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "$emoji ${list.size}",
                                    fontSize = 11.sp,
                                    color = textColor.copy(alpha = 0.9f)
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (message.isEdited && !message.isDeleted) {
                        Text(
                            text = "edited  ",
                            fontSize = 9.sp,
                            fontStyle = FontStyle.Italic,
                            color = textColor.copy(alpha = 0.5f)
                        )
                    }
                    val sdf = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }
                    val timeStr = sdf.format(Date(message.createdAt))
                    Text(
                        text = timeStr,
                        fontSize = 10.sp,
                        color = textColor.copy(alpha = 0.6f)
                    )
                    if (isOwnMessage) {
                        Spacer(modifier = Modifier.width(4.dp))
                        when (message.deliveryStatus) {
                            "PENDING" -> {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = "Pending",
                                    tint = textColor.copy(alpha = 0.6f),
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                            "SENT" -> {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Sent",
                                    tint = textColor.copy(alpha = 0.6f),
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                            "DELIVERED" -> {
                                Row {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Delivered",
                                        tint = textColor.copy(alpha = 0.6f),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Delivered",
                                        tint = textColor.copy(alpha = 0.6f),
                                        modifier = Modifier.size(12.dp).offset(x = (-4).dp)
                                    )
                                }
                            }
                            "READ" -> {
                                Row {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Read",
                                        tint = Color(0xFF06B6D4),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Read",
                                        tint = Color(0xFF06B6D4),
                                        modifier = Modifier.size(12.dp).offset(x = (-4).dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showOptions) {
        AlertDialog(
            onDismissRequest = { showOptions = false },
            title = { Text("Message Options") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("React:", fontWeight = FontWeight.Bold)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val emojis = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
                        emojis.forEach { emoji ->
                            val isSelected = myCurrentReaction == emoji
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                        else Color.Transparent
                                    )
                                    .clickable {
                                        onReact(emoji)
                                        showOptions = false
                                    }
                                    .padding(6.dp)
                            ) {
                                Text(
                                    text = emoji,
                                    fontSize = 28.sp
                                )
                            }
                        }
                    }

                    if (isOwnMessage && !message.isDeleted) {
                        Divider()
                        Button(
                            onClick = {
                                showEditDialog = true
                                showOptions = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Edit Message")
                        }
                        Button(
                            onClick = {
                                onDelete()
                                showOptions = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Delete Message")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showOptions = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit Message") },
            text = {
                TextField(
                    value = editVal,
                    onValueChange = { editVal = it },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onEdit(editVal)
                        showEditDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AttachmentCard(
    message: Message,
    isOwnMessage: Boolean,
    onLongClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val cardColor = if (isOwnMessage) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
    val contentColor = if (isOwnMessage) Color.White else MaterialTheme.colorScheme.onSurface

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .combinedClickable(
                onClick = {
                    try {
                        val rawPath = message.attachmentPath ?: ""
                        val fullUrl = if (rawPath.startsWith("http")) {
                            rawPath
                        } else {
                            val path = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
                            "${com.messaging.util.Constants.ATTACHMENT_BASE_URL}$path"
                        }
                        val mimeType = message.attachmentFileType ?: "application/octet-stream"
                        val uri = android.net.Uri.parse(fullUrl)
                        
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mimeType)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            val browserIntent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                uri
                            )
                            context.startActivity(browserIntent)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                },
                onLongClick = { onLongClick?.invoke() }
            ),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.AttachFile,
                contentDescription = "File Icon",
                tint = contentColor,
                modifier = Modifier.size(32.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                // Show a friendly label for media types instead of the raw device filename
                // (e.g. "VID_20260817_020225_187.mp4") - matches how most chat apps present
                // attachments; the actual filename is rarely useful to the reader.
                val primaryLabel = when (message.messageType) {
                    "IMAGE" -> "Photo"
                    "VIDEO" -> "Video"
                    "VOICE" -> "Voice message"
                    else -> message.attachmentFileName ?: "Document"
                }
                Text(
                    text = primaryLabel,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val sizeText = formatFileSize(message.attachmentFileSize ?: 0L)
                val secondaryText = if (message.messageType == "DOCUMENT") {
                    sizeText
                } else {
                    "${message.attachmentFileName ?: ""} • $sizeText"
                }
                Text(
                    text = secondaryText,
                    fontSize = 11.sp,
                    color = contentColor.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = "Download File",
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
