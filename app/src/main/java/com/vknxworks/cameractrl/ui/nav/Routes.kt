package com.vknxworks.cameractrl.ui.nav

import kotlinx.serialization.Serializable

/** The viewfinder: CameraCtrl opens straight into it. */
@Serializable data object CameraRoute

/** Every camera mode this phone offers, lens by lens. */
@Serializable data object ModesRoute

// Opened from the profile picture: the You page, and Settings from there
@Serializable data object YouRoute
@Serializable data object SettingsRoute