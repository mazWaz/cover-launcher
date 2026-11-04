package com.mazwas.coverlauncher.core.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mazwas.coverlauncher.feature.launcher.domain.OpenTarget
import com.mazwas.coverlauncher.feature.launcher.presentation.LauncherRoot
import com.mazwas.coverlauncher.feature.settings.presentation.SettingsRoot
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable data object LauncherRoute

@Serializable data object SettingsRoute

/** Fades only (Nothing: no slides between screens). [home] pops back to the launcher. */
@Composable
fun AppNavHost(home: Flow<Unit>, onOpen: (OpenTarget) -> Unit) {
  val nav = rememberNavController()
  LaunchedEffect(nav) { home.collect { nav.popBackStack(LauncherRoute, inclusive = false) } }
  NavHost(
    navController = nav,
    startDestination = LauncherRoute,
    enterTransition = { fadeIn(tween(200)) },
    exitTransition = { fadeOut(tween(150)) },
    popEnterTransition = { fadeIn(tween(200)) },
    popExitTransition = { fadeOut(tween(150)) },
  ) {
    composable<LauncherRoute> {
      LauncherRoot(onOpen = onOpen, onSettings = { nav.navigate(SettingsRoute) { launchSingleTop = true } })
    }
    composable<SettingsRoute> {
      SettingsRoot(onBack = { nav.popBackStack() })
    }
  }
}
