package com.vknxworks.cameractrl

/** Where "take a photo for another app" may write the photo (EXTRA_OUTPUT). Plain Kotlin, unit-tested. */
object CaptureOutput {
    /** MediaStore.AUTHORITY. */
    private const val MEDIA_AUTHORITY = "media"

    /**
     * Only a content address, and never CameraCtrl's own or the phone's media library: CameraCtrl owns every photo
     * it saved there and could write to it without any grant from the app that asked.
     */
    fun allowed(scheme: String?, authority: String?, ownPackage: String): Boolean {
        if (scheme != "content" || authority.isNullOrEmpty()) return false
        // "10@media": an address with a user prefix names the same provider.
        val host = authority.substringAfterLast('@')
        if (host.startsWith(ownPackage)) return false
        if (host.equals(MEDIA_AUTHORITY, ignoreCase = true)) return false
        return true
    }
}
