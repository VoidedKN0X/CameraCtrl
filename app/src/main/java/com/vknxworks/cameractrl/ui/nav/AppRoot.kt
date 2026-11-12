package com.vknxworks.cameractrl.ui.nav

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vknxworks.cameractrl.ui.camera.CameraScreen
import com.vknxworks.cameractrl.ui.camera.ModesScreen
import com.vknxworks.cameractrl.ui.settings.SettingsScreen
import com.vknxworks.cameractrl.ui.settings.YouScreen

/** No tabs: the camera fills the screen; modes, You and Settings open on top of it. */
@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val navigator = remember(navController) { Navigator(navController) }
    CompositionLocalProvider(LocalNavigator provides navigator) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            NavHost(navController, startDestination = CameraRoute) {
                composable<CameraRoute> { CameraScreen() }
                composable<ModesRoute> { ModesScreen() }
                composable<YouRoute> { YouScreen() }
                composable<SettingsRoute> { SettingsScreen() }
            }
        }
    }
}