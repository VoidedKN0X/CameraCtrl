package com.vknxworks.cameractrl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureOutputTest {
    private val me = "com.vknxworks.cameractrl"

    @Test fun anotherAppsFileProviderIsAllowed() {
        assertTrue(CaptureOutput.allowed("content", "org.example.chat.fileprovider", me))
    }

    @Test fun mediaLibraryIsRefused() {
        assertFalse(CaptureOutput.allowed("content", "media", me))
        assertFalse(CaptureOutput.allowed("content", "10@media", me))
        assertFalse(CaptureOutput.allowed("content", "MEDIA", me))
    }

    @Test fun ownProviderAndNonContentAreRefused() {
        assertFalse(CaptureOutput.allowed("content", "$me.files", me))
        assertFalse(CaptureOutput.allowed("content", "0@$me.files", me))
        assertFalse(CaptureOutput.allowed("file", "", me))
        assertFalse(CaptureOutput.allowed(null, null, me))
        assertFalse(CaptureOutput.allowed("content", null, me))
    }
}
