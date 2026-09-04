package com.messaging.presentation.screen.contact

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.messaging.presentation.screen.chat.ProfileAvatar
import java.util.Locale

/**
 * ContactInfoScreen - full-page "who is this" view for a private chat, opened by tapping the
 * chat header - the same pattern WhatsApp/Telegram use. Previously this was a small AlertDialog
 * with a subset of this information and no room to grow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactInfoScreen(
    navController: NavController,
    viewModel: ContactInfoViewModel = hiltViewModel()
) {
    val chat by viewModel.chat.collectAsState()
    val isBlocked by viewModel.isBlocked.collectAsState()
    val error by viewModel.error.collectAsState()
    var showBlockConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contact Info") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        val user = chat?.otherUser
        if (user == null) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ProfileAvatar(user.profilePictureUrl, Modifier.size(96.dp).clip(CircleShape))
                Spacer(Modifier.height(12.dp))
                Text(text = user.displayName.ifBlank { user.username }, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = user.status.lowercase(Locale.ROOT),
                    fontSize = 13.sp,
                    color = if (user.status == "ONLINE")
                        androidx.compose.ui.graphics.Color(0xFF4CAF50)
                    else
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }

            Divider()

            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                if (user.username.isNotBlank()) {
                    Text("Username", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                    Text("@${user.username}", fontSize = 16.sp)
                    Spacer(Modifier.height(16.dp))
                }
                if (!user.bio.isNullOrBlank()) {
                    Text("About", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                    Text(user.bio, fontSize = 16.sp)
                    Spacer(Modifier.height(16.dp))
                }
            }

            Divider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = { showBlockConfirm = true })
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = if (isBlocked) "Unblock ${user.displayName.ifBlank { user.username }}" else "Block ${user.displayName.ifBlank { user.username }}",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (showBlockConfirm) {
        val name = chat?.otherUser?.let { it.displayName.ifBlank { it.username } } ?: "this user"
        AlertDialog(
            onDismissRequest = { showBlockConfirm = false },
            title = { Text(if (isBlocked) "Unblock $name?" else "Block $name?") },
            text = {
                Text(
                    if (isBlocked) "They will be able to message you again."
                    else "They won't be able to call you or send you messages."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBlockConfirm = false
                    viewModel.toggleBlock()
                }) { Text(if (isBlocked) "Unblock" else "Block", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showBlockConfirm = false }) { Text("Cancel") }
            }
        )
    }

    error?.let { message ->
        LaunchedEffect(message) {
            kotlinx.coroutines.delay(3000)
            viewModel.clearError()
        }
    }
}