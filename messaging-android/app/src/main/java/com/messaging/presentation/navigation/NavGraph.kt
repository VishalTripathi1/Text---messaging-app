package com.messaging.presentation.navigation

import androidx.compose.runtime.Composable
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

/**
 * NavGraph - Navigation structure setup
 */
@Composable
fun NavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Screen.Login.route) {
        
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
