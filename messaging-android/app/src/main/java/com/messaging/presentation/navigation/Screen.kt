package com.messaging.presentation.navigation

/**
 * Screen - Navigation route definitions
 */
sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Login : Screen("login")
    object Register : Screen("register")
    object ChatList : Screen("chat_list")
    object ChatDetail : Screen("chat_detail/{chatId}")
    object GroupDetail : Screen("group_detail/{groupId}")
    object Profile : Screen("profile")
    object Settings : Screen("settings")
    object Search : Screen("search")
    object CreateGroup : Screen("create_group")

    fun withArgs(vararg args: String): String {
        var resolvedRoute = route
        args.forEach { arg ->
            val placeholder = Regex("\\{[^}]+}").find(resolvedRoute) ?: return@forEach
            resolvedRoute = resolvedRoute.replaceRange(placeholder.range, arg)
        }
        return resolvedRoute
    }
}
