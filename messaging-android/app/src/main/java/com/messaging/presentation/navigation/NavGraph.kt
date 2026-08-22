package com.messaging.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.messaging.presentation.screen.auth.LoginScreen
import com.messaging.presentation.screen.auth.RegisterScreen
import com.messaging.presentation.screen.chat.ChatListScreen
import com.messaging.presentation.screen.chat.ChatScreen
import com.messaging.presentation.screen.profile.ProfileScreen
import com.messaging.presentation.screen.settings.SettingsScreen
import com.messaging.presentation.screen.search.SearchScreen
import com.messaging.presentation.screen.group.CreateGroupScreen

import com.messaging.presentation.screen.splash.SplashScreen

/**
 * NavGraph - Navigation structure setup
 */
@Composable
fun NavGraph(navController: NavHostController, deepLinkChatId: Long? = null) {
    // The very first navigation decision (Login vs ChatList, optionally straight to a specific
    // chat) is handled inside SplashScreen below, since it has to wait for the auth check.
    // This effect only handles a *later* change to deepLinkChatId - i.e. the app was already
    // running (past the splash screen) and the user tapped another notification, which updates
    // MainActivity's deepLinkChatId state and recomposes this graph with a new value. Without
    // this, notifications tapped while the app is already open would do nothing at all.
    var lastHandledDeepLink by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(deepLinkChatId) {
        if (deepLinkChatId != null && deepLinkChatId != lastHandledDeepLink && lastHandledDeepLink != null) {
            navController.navigate(Screen.ChatDetail.withArgs(deepLinkChatId.toString()))
        }
        lastHandledDeepLink = deepLinkChatId
    }

    NavHost(navController = navController, startDestination = Screen.Splash.route) {
        
        composable(Screen.Splash.route) {
            SplashScreen(navController = navController, deepLinkChatId = deepLinkChatId)
        }

        composable(Screen.Login.route) {
            LoginScreen(navController = navController)
        }
        
        composable(Screen.Register.route) {
            RegisterScreen(navController = navController)
        }
        
        composable(Screen.ChatList.route) {
            ChatListScreen(navController = navController)
        }

        composable(
            route = Screen.ChatDetail.route,
            arguments = listOf(navArgument("chatId") { type = NavType.LongType })
        ) {
            ChatScreen(navController = navController)
        }

        // Groups use the same conversation UI and local storage model as chats; their ids are
        // represented as negative chat ids throughout the Android layer.
        composable(
            route = Screen.GroupDetail.route,
            arguments = listOf(navArgument("groupId") { type = NavType.LongType })
        ) { backStackEntry ->
            val groupId = checkNotNull(backStackEntry.arguments?.getLong("groupId"))
            LaunchedEffect(groupId) {
                navController.navigate(Screen.ChatDetail.withArgs((-groupId).toString())) {
                    popUpTo(Screen.GroupDetail.withArgs(groupId.toString())) { inclusive = true }
                }
            }
        }
        
        composable(Screen.Profile.route) {
            ProfileScreen(navController = navController)
        }
        
        composable(Screen.Settings.route) {
            SettingsScreen(navController = navController)
        }

        composable(Screen.Search.route) {
            SearchScreen(navController = navController)
        }

        composable(Screen.CreateGroup.route) {
            CreateGroupScreen(navController = navController)
        }
    }
}
