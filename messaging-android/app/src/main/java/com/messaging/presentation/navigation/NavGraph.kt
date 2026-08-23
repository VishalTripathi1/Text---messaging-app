package com.messaging.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
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
 * A notification tap waiting to be turned into navigation.
 *
 * [nonce] distinguishes two taps that target the same chat. Without it the value handed to
 * [NavGraph] would be an unchanged [Long], Compose would see no state change, and the effect
 * below would never restart - so only the first of several notifications for a chat would work.
 */
data class PendingDeepLink(val chatId: Long, val nonce: Long)

/**
 * NavGraph - Navigation structure setup
 */
@Composable
fun NavGraph(
    navController: NavHostController,
    deepLink: PendingDeepLink? = null,
    onDeepLinkHandled: () -> Unit = {}
) {
    // Deep links are applied by watching the current destination rather than by guessing at
    // composition order. Two things make that necessary:
    //
    //  - On a cold start the graph composes while startup routing (splash -> login or chat list)
    //    is still in flight. Navigating immediately races the splash screen's own navigate() call
    //    and whichever lands second wins, so we wait for the splash destination to be left first.
    //  - On a warm tap the app is already sitting on some destination, and the deep link has to
    //    be applied right away.
    //
    // The previous version tried to tell those apart with a `lastHandledDeepLink != null` guard,
    // which could never be satisfied: a warm-started app begins with a null deep link, so the
    // first tap always found `lastHandledDeepLink == null` and was discarded, and the value it
    // then recorded meant a second tap on the same chat produced no state change at all.
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    LaunchedEffect(deepLink, currentRoute) {
        val target = deepLink ?: return@LaunchedEffect

        // Startup routing hasn't settled yet - retry when the destination changes.
        if (currentRoute == null || currentRoute == Screen.Splash.route) return@LaunchedEffect

        if (currentRoute == Screen.Login.route || currentRoute == Screen.Register.route) {
            // Signed out, so the chat can't be loaded. Drop the deep link instead of leaving it
            // pending, otherwise it would fire the moment the user logs in for an unrelated reason.
            onDeepLinkHandled()
            return@LaunchedEffect
        }

        // Opened on top of the current screen (normally the chat list), so Back returns there
        // instead of closing the app.
        navController.navigate(Screen.ChatDetail.withArgs(target.chatId.toString())) {
            launchSingleTop = true
        }
        onDeepLinkHandled()
    }

    NavHost(navController = navController, startDestination = Screen.Splash.route) {

        composable(Screen.Splash.route) {
            SplashScreen(navController = navController)
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
