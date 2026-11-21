package com.vknxworks.cameractrl.ui.camera

import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The volume keys as a shutter. The viewfinder on screen claims them (only while it is resumed and the setting
 * is on); the activity hands every key event here first, and a claimed volume key takes the photo instead of
 * changing the volume. Without a claim the keys work as usual.
 */
object ShutterKeys {
    private val pressed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val presses: SharedFlow<Unit> = pressed

    /** The viewfinder that has the keys right now (null: nobody, the keys change the volume). */
    @Volatile var owner: Any? = null

    /** Called by the activity for every key event; true when the event was used as the shutter. */
    fun handle(event: KeyEvent): Boolean {
        if (owner == null) return false
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        // One photo per press: holding the key down doesn't repeat, and the key going up is swallowed too.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) pressed.tryEmit(Unit)
        return true
    }
}
