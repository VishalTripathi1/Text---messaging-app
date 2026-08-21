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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.messaging.domain.entity.Chat
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
    val currentUserId by viewModel.currentUserId.collectAsState()

    val sortedChats = remember(chats) {
        chats.sortedWith(
            compareByDescending<Chat> { it.isPinned }
                .thenByDescending { it.lastMessageAt ?: 0L }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Chats",
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isConnected) Color(0xFF4CAF50) else Color(0xFFFF9800))
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.Search.route) }) {
                        Icon(imageVector = Icons.Default.Search, contentDescription = "Search Users")
                    }
                    IconButton(onClick = { navController.navigate(Screen.Settings.route) }) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
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
            if (sortedChats.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No active chats yet. Start one by clicking '+'!",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        fontSize = 16.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(sortedChats) { chat ->
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
                            modifier = Modifier.padding(horizontal = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        )
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
                if (!chat.isGroup && chat.otherUser?.status == "ONLINE") {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF4CAF50))
                            .align(Alignment.BottomEnd)
                            .background(MaterialTheme.colorScheme.background)
                            .padding(2.dp)
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
                        if (isOwnLastMessage && !chat.lastMessageText.isNullOrBlank()) {
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
