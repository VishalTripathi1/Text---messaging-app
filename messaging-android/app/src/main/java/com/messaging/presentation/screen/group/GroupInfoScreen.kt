package com.messaging.presentation.screen.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController

/**
 * GroupInfoScreen - member list with roles, rename (admin-only), add/remove members
 * (admin-only for removing others), and leave group. None of this existed before - the group
 * chat screen had no way to see who was in a group, how many members it had, who (if anyone)
 * was an admin, or to change anything about the group after creation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    navController: NavController,
    viewModel: GroupInfoViewModel = hiltViewModel()
) {
    val group by viewModel.group.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val leftGroup by viewModel.leftGroup.collectAsState()
    val addableContacts by viewModel.addableContacts.collectAsState()
    val selectedNewMemberIds by viewModel.selectedNewMemberIds.collectAsState()

    var showRenameDialog by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var showAddMembersSheet by remember { mutableStateOf(false) }

    LaunchedEffect(leftGroup) {
        if (leftGroup) {
            navController.popBackStack(com.messaging.presentation.navigation.Screen.ChatList.route, inclusive = false)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Group Info") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        val currentGroup = group
        if (isLoading && currentGroup == null) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (currentGroup == null) {
            // Previously this branch didn't exist at all - if getGroupDetails() failed for any
            // reason (network hiccup, backend not yet redeployed with the new response shape,
            // permission issue), the screen just stayed permanently blank with isLoading=false
            // and group=null, no error text, no retry - nothing to tell the person anything went
            // wrong or let them try again without leaving the screen.
            Column(
                modifier = Modifier.fillMaxSize().padding(paddingValues).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = error ?: "Couldn't load this group.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { viewModel.loadGroup() }) {
                    Text("Retry")
                }
            }
        } else if (currentGroup != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Group name + rename
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = currentGroup.groupName.take(1).uppercase(),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = currentGroup.groupName, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "${currentGroup.memberCount} member${if (currentGroup.memberCount == 1L) "" else "s"}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }
                    if (currentGroup.isAdmin) {
                        IconButton(onClick = { showRenameDialog = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Rename group")
                        }
                    }
                }

                Divider()

                // Members header + add button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Members", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    if (currentGroup.isAdmin) {
                        TextButton(onClick = {
                            viewModel.openAddMembers()
                            showAddMembersSheet = true
                        }) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add")
                        }
                    }
                }

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(currentGroup.members) { member ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.secondaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = (member.user.username ?: "?").take(1).uppercase(),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = if (member.user.id == viewModel.currentUserId) "You" else (member.user.username ?: "Unknown"),
                                        fontSize = 15.sp
                                    )
                                    if (member.isAdmin) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                                .padding(horizontal = 6.dp, vertical = 1.dp)
                                        ) {
                                            Text(
                                                text = "Admin",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                            // Admins can remove anyone but themselves (leaving is a separate,
                            // explicit action below so it can't be triggered by accident).
                            if (currentGroup.isAdmin && member.user.id != viewModel.currentUserId) {
                                IconButton(onClick = { viewModel.removeMember(member.user.id) }) {
                                    Icon(
                                        Icons.Default.PersonRemove,
                                        contentDescription = "Remove member",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }

                Divider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLeaveConfirm = true }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ExitToApp, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Leave group", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showRenameDialog && group != null) {
        var nameInput by remember(group) { mutableStateOf(group!!.groupName) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename group") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                    label = { Text("Group name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameGroup(nameInput)
                    showRenameDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text("Leave group?") },
            text = { Text("You will no longer receive messages from this group.") },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveConfirm = false
                    viewModel.leaveGroup()
                }) { Text("Leave", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showAddMembersSheet) {
        ModalBottomSheet(onDismissRequest = {
            showAddMembersSheet = false
            viewModel.cancelAddMembers()
        }) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Add members", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                if (addableContacts.isEmpty()) {
                    Text(
                        "No more contacts to add.",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(addableContacts) { contact ->
                            val isSelected = selectedNewMemberIds.contains(contact.id)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.toggleNewMemberSelection(contact.id) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = isSelected, onCheckedChange = { viewModel.toggleNewMemberSelection(contact.id) })
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(contact.username)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            viewModel.confirmAddMembers()
                            showAddMembersSheet = false
                        },
                        enabled = selectedNewMemberIds.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Add ${selectedNewMemberIds.size} member${if (selectedNewMemberIds.size == 1) "" else "s"}")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    error?.let { message ->
        LaunchedEffect(message) {
            // Snackbar host isn't wired on this screen yet; clear after a beat so it doesn't
            // silently block future error messages from showing once one is added.
            kotlinx.coroutines.delay(3000)
            viewModel.clearError()
        }
    }
}