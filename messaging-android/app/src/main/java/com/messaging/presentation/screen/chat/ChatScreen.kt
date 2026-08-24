package com.messaging.presentation.screen.chat

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaRecorder
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.messaging.domain.entity.Message
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.debounce
import java.text.SimpleDateFormat
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.messaging.util.Constants

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

private suspend fun saveAttachmentToDevice(
    context: android.content.Context,
    url: String,
    fileName: String,
    mimeType: String,
    accessToken: String?
): Result<Uri> = withContext(Dispatchers.IO) {
    runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            accessToken?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        connection.connect()
        if (connection.responseCode !in 200..299) throw IllegalStateException("Download failed (${connection.responseCode})")
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName.ifBlank { "attachment" })
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val target = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Unable to create download")
        } else {
            throw IllegalStateException("Downloads require Android 10 or newer")
        }
        try {
            resolver.openOutputStream(target)?.use { output -> connection.inputStream.use { it.copyTo(output) } }
                ?: throw IllegalStateException("Unable to open download")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(target, values, null, null)
            target
        } catch (t: Throwable) {
            resolver.delete(target, null, null)
            throw t
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * A file the user has picked but not yet sent - lets them review/remove it before it goes out.
 */
data class PendingAttachment(
    val uri: android.net.Uri,
    val fileName: String,
    val mimeType: String,
    val messageType: String
)

@Composable
fun DefaultAvatar(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = "Default profile picture",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize(0.62f)
        )
    }
}

@Composable
fun ProfileAvatar(imagePath: String?, modifier: Modifier) {
    var imageFailed by remember(imagePath) { mutableStateOf(false) }
    if (!imagePath.isNullOrBlank() && !imageFailed) {
        AsyncImage(
            model = if (imagePath.startsWith("http")) imagePath else Constants.ATTACHMENT_BASE_URL + imagePath,
            contentDescription = "Profile picture",
            modifier = modifier.clip(CircleShape),
            contentScale = ContentScale.Crop,
            onError = { imageFailed = true }
        )
    } else {
        DefaultAvatar(modifier)
    }
}

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
    val otherUserProfilePictureUrl by viewModel.otherUserProfilePictureUrl.collectAsState()
    val otherUserUsername by viewModel.otherUserUsername.collectAsState()
    val otherUserBio by viewModel.otherUserBio.collectAsState()
    val accessToken by viewModel.accessToken.collectAsState()
    val isPeerTyping by viewModel.isPeerTyping.collectAsState()

    var textState by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val searchQuery = ""

    // See the identical fix + rationale in ChatListScreen.kt - without this, reopening an
    // already-composed chat screen (app backgrounded/resumed, or screen unlocked) never
    // refetched missed messages or re-verified the WebSocket connection.
    //
    // ON_PAUSE is tracked as well so the ViewModel knows whether the conversation is actually
    // being looked at: messages arriving over the still-connected WebSocket while the app is
    // backgrounded must not be reported to the sender as read.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.syncMessages()
                    viewModel.markAsRead()
                    viewModel.onScreenVisible()
                }
                Lifecycle.Event.ON_PAUSE -> viewModel.onScreenHidden()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            viewModel.onScreenHidden()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

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
    var showChatDetails by remember { mutableStateOf(false) }
    var showLargeProfilePhoto by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var recordingSeconds by remember { mutableStateOf(0) }

    fun stageCameraPhoto(file: File) {
        val source = BitmapFactory.decodeFile(file.absolutePath) ?: return
        val matrix = Matrix()
        when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        }
        val oriented = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        val scale = minOf(1f, 1920f / maxOf(oriented.width, oriented.height).toFloat())
        val resized = if (scale < 1f) Bitmap.createScaledBitmap(oriented, (oriented.width * scale).toInt(), (oriented.height * scale).toInt(), true) else oriented
        FileOutputStream(file).use { output -> resized.compress(Bitmap.CompressFormat.JPEG, 90, output) }
        if (resized !== oriented) resized.recycle()
        if (oriented !== source) oriented.recycle()
        source.recycle()
        pendingAttachment = PendingAttachment(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file), file.name, "image/jpeg", "IMAGE")
    }

    fun stopRecording() {
        recorder?.runCatching { stop() }
        recorder?.release()
        recorder = null
        isRecording = false
        recordingFile?.let { file ->
            if (file.exists()) pendingAttachment = PendingAttachment(
                Uri.fromFile(file), file.name, "audio/mp4", "VOICE"
            )
        }
    }

    fun startRecording() {
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        newRecorder.setAudioEncodingBitRate(48_000)
        newRecorder.setAudioSamplingRate(22_050)
        newRecorder.setOutputFile(file.absolutePath)
        newRecorder.prepare()
        newRecorder.start()
        recordingFile = file
        recorder = newRecorder
        isRecording = true
        recordingSeconds = 0
    }

    var cameraFile by remember { mutableStateOf<File?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val file = cameraFile
        if (captured && file != null) stageCameraPhoto(file) else file?.delete()
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val file = File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
            cameraFile = file
            cameraLauncher.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
        }
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording()
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            while (isRecording && recordingSeconds < 60) {
                kotlinx.coroutines.delay(1_000)
                recordingSeconds++
            }
            if (isRecording) stopRecording()
        }
    }
    DisposableEffect(Unit) { onDispose { recorder?.release() } }

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
                val msgType = when {
                    mimeType.startsWith("image/") -> "IMAGE"
                    mimeType.startsWith("video/") -> "VIDEO"
                    mimeType.startsWith("audio/") -> "VOICE"
                    else -> "DOCUMENT"
                }
                // Keep only a content URI in Compose state. Reading a 100 MB video into a
                // ByteArray here held it for the entire time the attachment preview was open.
                pendingAttachment = PendingAttachment(uri, fileName, mimeType, msgType)
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

    // Send a single start event when typing begins, then a single stop event only after two
    // seconds of inactivity. This avoids every keystroke racing a delayed stop event.
    LaunchedEffect(textState.isNotBlank()) {
        viewModel.sendTypingState(textState.isNotBlank())
    }
    LaunchedEffect(Unit) {
        snapshotFlow { textState }
            .debounce(2_000)
            .collect { if (it.isNotBlank()) viewModel.sendTypingState(false) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    run {
                        Column {
                            Row(
                                modifier = Modifier.clickable { showChatDetails = true },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.clickable { showLargeProfilePhoto = true }) {
                                    ProfileAvatar(otherUserProfilePictureUrl, Modifier.size(36.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(text = otherUserDisplayName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
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
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {},
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
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
                        accessToken = accessToken,
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
                            text = attachment.messageType,
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
            if (isRecording) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                    Spacer(Modifier.width(8.dp))
                    Text("Recording voice message 00:${recordingSeconds.toString().padStart(2, '0')}", color = MaterialTheme.colorScheme.error)
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
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
                IconButton(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            val file = File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                            cameraFile = file
                            cameraLauncher.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    enabled = !isRecording
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "Take photo", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(
                    onClick = {
                        if (isRecording) {
                            stopRecording()
                        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            startRecording()
                        } else {
                            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    enabled = pendingAttachment == null
                ) {
                    Icon(
                        if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isRecording) "Stop recording" else "Record voice message",
                        tint = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextField(
                    value = textState,
                    onValueChange = { textState = it },
                    placeholder = { Text("Type a message...") },
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp)),
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
                                context,
                                attachment.uri,
                                attachment.fileName,
                                attachment.mimeType,
                                attachment.messageType,
                                textState
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

    if (showChatDetails) {
        AlertDialog(
            onDismissRequest = { showChatDetails = false },
            title = { Text(otherUserDisplayName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Status: ${otherUserStatus.lowercase(Locale.ROOT)}")
                    if (otherUserUsername.isNotBlank()) Text("Username: @$otherUserUsername")
                    if (!otherUserBio.isNullOrBlank()) Text(otherUserBio!!)
                }
            },
            confirmButton = { TextButton(onClick = { showChatDetails = false }) { Text("Close") } }
        )
    }

    if (showLargeProfilePhoto && !otherUserProfilePictureUrl.isNullOrBlank()) {
        Dialog(onDismissRequest = { showLargeProfilePhoto = false }) {
            Surface(
                color = Color.Black,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.75f)
            ) {
                AsyncImage(
                    model = if (otherUserProfilePictureUrl!!.startsWith("http")) otherUserProfilePictureUrl else Constants.ATTACHMENT_BASE_URL + otherUserProfilePictureUrl,
                    contentDescription = "Profile picture",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
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
    accessToken: String?,
    onReact: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit
) {
    val myCurrentReaction = message.reactions?.firstOrNull { it.userId == currentUserId }?.reaction
    val align = if (isOwnMessage) Alignment.End else Alignment.Start
    val bubbleColor = if (isOwnMessage) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isOwnMessage) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    var showOptions by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editVal by remember(message.id, message.messageText) { mutableStateOf(message.messageText ?: "") }

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
            .widthIn(max = 320.dp)
        ) {
            Column {
                if (message.attachmentPath != null) {
                    AttachmentCard(
                        message = message,
                        isOwnMessage = isOwnMessage,
                        accessToken = accessToken,
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
                    if (isOwnMessage && !message.isDeleted) {
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

                    if (isOwnMessage && !message.isDeleted && message.messageType == "TEXT" && message.attachmentPath == null) {
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
                    }
                    if (isOwnMessage && !message.isDeleted) {
                        Button(
                            onClick = {
                                onDelete()
                                showOptions = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Delete Message") }
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
    accessToken: String?,
    onLongClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showPhotoPreview by remember(message.id) { mutableStateOf(false) }
    var isDownloading by remember(message.id) { mutableStateOf(false) }
    val cardColor = if (isOwnMessage) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
    val contentColor = if (isOwnMessage) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val photoRequest = remember(message.id, accessToken) {
        ImageRequest.Builder(context)
            .data(fullAttachmentUrl(message))
            .apply { accessToken?.let { addHeader("Authorization", "Bearer $it") } }
            .crossfade(true)
            .build()
    }
    var isPlaying by remember(message.id) { mutableStateOf(false) }
    var playerReady by remember(message.id) { mutableStateOf(false) }
    var playbackPosition by remember(message.id) { mutableStateOf(0L) }
    var playbackDuration by remember(message.id) { mutableStateOf(0L) }
    val player = remember(message.id) { MediaPlayer() }
    DisposableEffect(message.id) {
        onDispose { player.release() }
    }
    LaunchedEffect(isPlaying, playerReady) {
        while (isPlaying && playerReady) {
            playbackPosition = player.currentPosition.toLong()
            playbackDuration = player.duration.toLong().coerceAtLeast(1L)
            delay(100)
        }
    }

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
                        if (message.messageType == "IMAGE") {
                            showPhotoPreview = true
                        } else if (message.messageType == "VOICE") {
                            if (isPlaying) {
                                player.pause()
                                isPlaying = false
                            } else if (playerReady) {
                                player.start()
                                isPlaying = true
                            } else {
                                player.reset()
                                val headers = accessToken?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
                                player.setDataSource(context, uri, headers)
                                player.setOnPreparedListener {
                                    playerReady = true
                                    playbackDuration = it.duration.toLong().coerceAtLeast(1L)
                                    player.start()
                                    isPlaying = true
                                }
                                player.setOnCompletionListener { isPlaying = false }
                                player.prepareAsync()
                            }
                        } else {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, mimeType)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(intent)
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
            if (message.messageType == "IMAGE") {
                AsyncImage(
                    model = photoRequest,
                    contentDescription = "Open photo",
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop
                )
            }
            Icon(
                imageVector = if (message.messageType == "VOICE" && isPlaying) Icons.Default.Pause else if (message.messageType == "VOICE") Icons.Default.PlayArrow else Icons.Default.AttachFile,
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
                if (message.messageType == "VOICE" && isPlaying) {
                    val safeDuration = playbackDuration.toFloat().coerceAtLeast(1f)
                    Slider(
                        value = playbackPosition.toFloat().coerceIn(0f, safeDuration),
                        onValueChange = { playbackPosition = it.toLong() },
                        onValueChangeFinished = {
                            if (playerReady) player.seekTo(playbackPosition.toInt())
                        },
                        valueRange = 0f..safeDuration,
                        enabled = playerReady,
                        modifier = Modifier.fillMaxWidth().height(24.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = contentColor,
                            activeTrackColor = contentColor,
                            inactiveTrackColor = contentColor.copy(alpha = 0.25f)
                        )
                    )
                }
                val sizeText = formatFileSize(message.attachmentFileSize ?: 0L)
                val secondaryText = if (message.messageType == "DOCUMENT") {
                    sizeText
                } else {
                    "${message.attachmentFileName ?: ""} • $sizeText"
                }
                Text(
                    text = if (message.messageType == "VOICE" && isPlaying) "Playing voice message" else if (message.messageType == "VOICE") "Tap to play" else secondaryText,
                    fontSize = 11.sp,
                    color = contentColor.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = {
                    if (!isDownloading) {
                        isDownloading = true
                        scope.launch {
                            val result = saveAttachmentToDevice(context, fullAttachmentUrl(message), message.attachmentFileName ?: "attachment", message.attachmentFileType ?: "application/octet-stream", accessToken)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    context,
                                    if (result.isSuccess) "Saved to Downloads" else "Download failed",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            isDownloading = false
                        }
                    }
                },
                enabled = !isDownloading
            ) {
                Icon(
                    imageVector = if (isDownloading) Icons.Default.HourglassTop else Icons.Default.Download,
                    contentDescription = "Download File",
                    tint = contentColor
                )
            }
        }
    }

    if (showPhotoPreview) {
        AlertDialog(
            onDismissRequest = { showPhotoPreview = false },
            text = {
                AsyncImage(
                    model = photoRequest,
                    contentDescription = "Photo",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit
                )
            },
            confirmButton = { TextButton(onClick = { showPhotoPreview = false }) { Text("Close") } }
        )
    }
}

private fun fullAttachmentUrl(message: Message): String {
    val rawPath = message.attachmentPath ?: ""
    if (rawPath.startsWith("http")) return rawPath
    val path = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
    return "${Constants.ATTACHMENT_BASE_URL}$path"
}
