package com.campusmesh.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.campusmesh.ui.chat.ChatRoute
import com.campusmesh.ui.debug.DebugStatusRoute
import com.campusmesh.ui.home.HomeRoute
import com.campusmesh.ui.peers.PeersRoute
import com.campusmesh.ui.profile.PeerProfileRoute
import com.campusmesh.ui.profile.ProfileSetupRoute
import com.campusmesh.ui.splash.SplashScreen
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object Routes {
    const val Splash = "splash"
    const val Home = "home"
    const val Peers = "peers"
    const val Debug = "debug"
    const val Profile = "profile"
    const val Chat = "chat/{peerNodeId}/{peerLabel}"
    const val PeerProfile = "peer_profile/{peerNodeId}/{peerLabel}"
    const val Call = "call/{peerNodeId}/{peerLabel}/{isIncoming}"

    fun chat(peerNodeId: String, peerLabel: String): String {
        val encodedNodeId = URLEncoder.encode(peerNodeId, StandardCharsets.UTF_8.name())
        val encodedLabel = URLEncoder.encode(peerLabel, StandardCharsets.UTF_8.name())
        return "chat/$encodedNodeId/$encodedLabel"
    }

    fun call(peerNodeId: String, peerLabel: String, isIncoming: Boolean = false): String {
        val encodedNodeId = URLEncoder.encode(peerNodeId, StandardCharsets.UTF_8.name())
        val encodedLabel = URLEncoder.encode(peerLabel, StandardCharsets.UTF_8.name())
        return "call/$encodedNodeId/$encodedLabel/$isIncoming"
    }

    fun peerProfile(peerNodeId: String, peerLabel: String): String {
        val encodedNodeId = URLEncoder.encode(peerNodeId, StandardCharsets.UTF_8.name())
        val encodedLabel = URLEncoder.encode(peerLabel, StandardCharsets.UTF_8.name())
        return "peer_profile/$encodedNodeId/$encodedLabel"
    }

    fun decodeArg(encoded: String): String =
        URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
}

@Composable
fun CampusMeshNavHost(
    pendingRoute: String? = null,
    onRouteConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()

    LaunchedEffect(pendingRoute) {
        if (!pendingRoute.isNullOrBlank()) {
            navController.navigate(pendingRoute)
            onRouteConsumed()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        NavHost(
            navController = navController,
            startDestination = Routes.Splash,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(350),
                ) + fadeIn(animationSpec = tween(350))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { fullWidth -> -fullWidth / 4 },
                    animationSpec = tween(350),
                ) + fadeOut(animationSpec = tween(350))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { fullWidth -> -fullWidth / 4 },
                    animationSpec = tween(350),
                ) + fadeIn(animationSpec = tween(350))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(350),
                ) + fadeOut(animationSpec = tween(350))
            },
        ) {
            composable(Routes.Splash) {
                SplashScreen(
                    onSplashFinished = {
                        val destination = pendingRoute ?: Routes.Home
                        onRouteConsumed()
                        navController.navigate(destination) {
                            popUpTo(Routes.Splash) { inclusive = true }
                        }
                    },
                )
            }

            composable(Routes.Home) {
                HomeRoute(
                    onNavigateToChat = { nodeId, label ->
                        navController.navigate(Routes.chat(nodeId, label))
                    },
                    onNavigateToPeers = {
                        navController.navigate(Routes.Peers)
                    },
                    onNavigateToDebug = {
                        navController.navigate(Routes.Debug)
                    },
                    onNavigateToProfile = {
                        navController.navigate(Routes.Profile)
                    },
                )
            }

            composable(Routes.Peers) {
                PeersRoute(
                    onNavigateToChat = { nodeId, label ->
                        navController.navigate(Routes.chat(nodeId, label))
                    },
                    onBackClick = { navController.popBackStack() },
                )
            }

            composable(Routes.Debug) {
                DebugStatusRoute(
                    onNavigateToChat = { address, label ->
                        navController.navigate(Routes.chat(address, label))
                    },
                )
            }

            composable(Routes.Profile) {
                ProfileSetupRoute(
                    onBackClick = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.Chat,
                arguments = listOf(
                    navArgument("peerNodeId") { type = NavType.StringType },
                    navArgument("peerLabel") { type = NavType.StringType },
                ),
            ) {
                ChatRoute(
                    onBackClick = { navController.popBackStack() },
                    onNavigateToPeerProfile = { nodeId, label ->
                        navController.navigate(Routes.peerProfile(nodeId, label))
                    },
                    onNavigateToCall = { nodeId, label ->
                        navController.navigate(Routes.call(nodeId, label, isIncoming = false))
                    },
                )
            }

            composable(
                route = Routes.PeerProfile,
                arguments = listOf(
                    navArgument("peerNodeId") { type = NavType.StringType },
                    navArgument("peerLabel") { type = NavType.StringType },
                ),
            ) {
                PeerProfileRoute(
                    onBackClick = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.Call,
                arguments = listOf(
                    navArgument("peerNodeId") { type = NavType.StringType },
                    navArgument("peerLabel") { type = NavType.StringType },
                    navArgument("isIncoming") { type = NavType.BoolType; defaultValue = false },
                ),
            ) {
                com.campusmesh.ui.call.CallRoute(
                    onBackClick = { navController.popBackStack() },
                )
            }
        }
    }
}
