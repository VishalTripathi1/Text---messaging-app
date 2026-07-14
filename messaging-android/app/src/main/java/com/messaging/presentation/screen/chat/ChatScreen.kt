package com.messaging.presentation.screen.chat

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

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
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
                    viewModel.sendMediaMessage(fileBytes, fileName, mimeType, msgType)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Auto scroll to bottom when new messages arrive
    LaunchedEffect(filteredMessages.size) {
        if (filteredMessages.isNotEmpty()) {
            listState.animateScrollToItem(filteredMessages.size - 1)
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
                        onReact = { reaction ->
                            viewModel.addReaction(message.id, reaction)
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

            // Input Panel
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .imePadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { filePickerLauncher.launch("*/*") }) {
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
                        if (textState.isNotBlank()) {
                            viewModel.sendMessage(textState)
                            textState = ""
                        }
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = Color.White
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
    onReact: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit
) {
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
                    AttachmentCard(message = message, isOwnMessage = isOwnMessage)
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
                    val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
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
                                        tint = Color(0xFF0288D1),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Read",
                                        tint = Color(0xFF0288D1),
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
                            Text(
                                text = emoji,
                                fontSize = 28.sp,
                                modifier = Modifier
                                    .clickable {
                                        onReact(emoji)
                                        showOptions = false
                                    }
                                    .padding(4.dp)
                            )
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

@Composable
fun AttachmentCard(
    message: Message,
    isOwnMessage: Boolean
) {
    val context = LocalContext.current
    val cardColor = if (isOwnMessage) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
    val contentColor = if (isOwnMessage) Color.White else MaterialTheme.colorScheme.onSurface

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable {
                try {
                    val pathEncoded = java.net.URLEncoder.encode(message.attachmentPath ?: "", "UTF-8")
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("http://10.0.2.2:8080/v1/attachments/download?path=$pathEncoded")
                    )
                    context.startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            },
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
                Text(
                    text = message.attachmentFileName ?: "Attachment",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val sizeKb = (message.attachmentFileSize ?: 0L) / 1024L
                Text(
                    text = "${message.messageType} • ${sizeKb} KB",
                    fontSize = 11.sp,
                    color = contentColor.copy(alpha = 0.7f)
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
