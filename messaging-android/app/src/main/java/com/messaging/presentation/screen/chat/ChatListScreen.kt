package com.messaging.presentation.screen.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.messaging.domain.entity.Chat
import coil.compose.AsyncImage
import com.messaging.util.Constants
import com.messaging.presentation.navigation.Screen
import java.text.SimpleDateFormat
import java.util.*

/**
 * ChatListScreen - Displays active private and group chats with WebSocket status and unread count badges.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    navController: NavController,
    viewModel: ChatListViewModel = hiltViewModel()
) {
    val chats by viewModel.chats.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()

    // Previously the chat list only ever synced once, in the ViewModel's init{} block. Since
    // the ViewModel survives a simple lock-screen-then-unlock or app-backgrounded-then-resumed
    // cycle (the process isn't killed, so init{} never runs again), nothing re-fetched the chat
    // list or re-verified the WebSocket connection on resume. Messages sent while the app was
    // backgrounded/the screen was locked would sit unseen until something else happened to
    // trigger a refresh (like sending a new message yourself). This restores both on every
    // resume, and also catches the WebSocket having silently died in the background - reconnect
    // here is safe/idempotent (see WebSocketClient's isConnecting guard).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.connectWebSocket()
                viewModel.syncChats()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    val currentUserId by viewModel.currentUserId.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var isSearchVisible by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf("All") }

    val sortedChats = remember(chats) {
        chats.sortedWith(
            compareByDescending<Chat> { it.isPinned }
                .thenByDescending { it.lastMessageAt ?: 0L }
        )
    }
    val visibleChats = remember(sortedChats, searchQuery, selectedFilter) {
        sortedChats.filter { chat ->
            val name = if (chat.isGroup) chat.groupName.orEmpty() else chat.otherUser?.displayName.orEmpty()
            val matchesSearch = searchQuery.isBlank() || name.contains(searchQuery, ignoreCase = true) ||
                chat.lastMessageText.orEmpty().contains(searchQuery, ignoreCase = true)
            val matchesFilter = when (selectedFilter) {
                "Unread" -> chat.unreadCount > 0
                "Groups" -> chat.isGroup
                else -> true
            }
            matchesSearch && matchesFilter
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Chats",
                            style = MaterialTheme.typography.headlineLarge
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isConnected) "Live" else "Offline", style = MaterialTheme.typography.labelMedium, color = if (isConnected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = { isSearchVisible = !isSearchVisible }) {
                        Icon(imageVector = if (isSearchVisible) Icons.Default.Close else Icons.Default.Search, contentDescription = "Search conversations")
                    }
                    IconButton(onClick = { navController.navigate(Screen.Settings.route) }) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { navController.navigate(Screen.Search.route) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                shape = CircleShape
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "New Chat")
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (isSearchVisible) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("Search conversations") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = if (searchQuery.isNotEmpty()) ({ IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear search") } }) else null,
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                    )
                }
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Unread", "Groups").forEach { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text(filter) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
                if (visibleChats.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isBlank()) "Your inbox is quiet" else "No conversations found",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp)
                ) {
                    items(visibleChats, key = { it.id }) { chat ->
                        ChatItem(
                            chat = chat,
                            currentUserId = currentUserId,
                            onClick = {
                                navController.navigate(Screen.ChatDetail.route.replace("{chatId}", chat.id.toString()))
                            },
                            onPinToggle = {
                                viewModel.pinChat(chat.id, !chat.isPinned)
                            },
                            onArchiveToggle = {
                                viewModel.archiveChat(chat.id, !chat.isArchived)
                            }
                        )
                        Divider(
                            modifier = Modifier.padding(start = 84.dp, end = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        )
                    }
                }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatItem(
    chat: Chat,
    currentUserId: Long,
    onClick: () -> Unit,
    onPinToggle: () -> Unit,
    onArchiveToggle: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showMenu = true }
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Colored initials avatar
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(
                        if (chat.isGroup) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.primaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                val imagePath = chat.otherUser?.profilePictureUrl
                if (!chat.isGroup) {
                    ProfileAvatar(imagePath, Modifier.fillMaxSize())
                } else {
                    val initials = if (chat.isGroup) {
                        chat.groupName?.take(2)?.uppercase() ?: "GP"
                    } else {
                        chat.otherUser?.displayName?.take(2)?.uppercase() ?: "??"
                    }
                    Text(
                        text = initials,
                        color = if (chat.isGroup) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val displayName = if (chat.isGroup) {
                        chat.groupName ?: "Group Chat"
                    } else {
                        chat.otherUser?.displayName ?: "Unknown"
                    }
                    Text(
                        text = displayName,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (chat.isPinned) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Pinned",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        val timeStr = chat.lastMessageAt?.let {
                            val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
                            sdf.format(Date(it))
                        } ?: ""
                        Text(
                            text = timeStr,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isOwnLastMessage = chat.lastMessageSenderId == currentUserId && currentUserId > 0
                        if (isOwnLastMessage && !chat.lastMessageText.isNullOrBlank() && chat.lastMessageStatus != null) {
                            val status = chat.lastMessageStatus ?: "SENT"
                            val tintColor = if (status == "READ") Color(0xFF06B6D4) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                            when (status) {
                                "PENDING" -> {
                                    Icon(
                                        imageVector = Icons.Default.Schedule,
                                        contentDescription = "Pending",
                                        tint = tintColor,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                "SENT" -> {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Sent",
                                        tint = tintColor,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                "DELIVERED", "READ" -> {
                                    Row {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = status,
                                            tint = tintColor,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = status,
                                            tint = tintColor,
                                            modifier = Modifier.size(14.dp).offset(x = (-5).dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(2.dp))
                                }
                            }
                        }

                        Text(
                            text = chat.lastMessageText ?: "No messages yet",
                            fontSize = 14.sp,
                            color = if (chat.unreadCount > 0) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (chat.unreadCount > 0) FontWeight.Medium else FontWeight.Normal
                        )
                    }

                    if (chat.unreadCount > 0) {
                        Box(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = chat.unreadCount.toString(),
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(if (chat.isPinned) "Unpin Chat" else "Pin Chat") },
                onClick = {
                    onPinToggle()
                    showMenu = false
                }
            )
            DropdownMenuItem(
                text = { Text(if (chat.isArchived) "Unarchive Chat" else "Archive Chat") },
                onClick = {
                    onArchiveToggle()
                    showMenu = false
                }
            )
        }
    }
}
