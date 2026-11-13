package com.vknxworks.cameractrl.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanContentTest {
    @Test
    fun httpAndHttpsAreLinks() {
        val l = ScanContent.parse("https://example.org/path?q=1#x") as ScanContent.Link
        assertEquals("https://example.org/path?q=1#x", l.url)
        assertTrue(ScanContent.parse("HTTP://EXAMPLE.ORG") is ScanContent.Link)
        assertTrue(ScanContent.parse("  https://example.org  ") is ScanContent.Link)
    }

    @Test
    fun otherSchemesAreOnlyText() {
        for (s in listOf("javascript:alert(1)", "intent://x#Intent;end", "file:///sdcard/a", "content://x/y", "tel:123", "mailto:a@b.c", "market://details?id=x")) {
            assertTrue(s, ScanContent.parse(s) is ScanContent.Text)
        }
    }

    @Test
    fun brokenLinksAreText() {
        assertNull(ScanContent.link("https://"))
        assertNull(ScanContent.link("https:/example.org"))
        assertNull(ScanContent.link("https://exa mple.org"))
        assertNull(ScanContent.link("https://example.org/\nhttps://evil.example"))
        assertNull(ScanContent.link("http//example.org"))
    }

    @Test
    fun wifiWpa() {
        val w = ScanContent.parse("WIFI:T:WPA;S:Home Net;P:secret pass;;") as ScanContent.Wifi
        assertEquals("Home Net", w.ssid)
        assertEquals("secret pass", w.password)
        assertEquals(ScanContent.Security.WPA, w.security)
        assertEquals(false, w.hidden)
    }

    @Test
    fun wifiEscapesAndFieldOrder() {
        val w = ScanContent.parse("WIFI:S:My\\;Net\\:5G;P:pa\\\\ss\\,word;T:SAE;H:true;;") as ScanContent.Wifi
        assertEquals("My;Net:5G", w.ssid)
        assertEquals("pa\\ss,word", w.password)
        assertEquals(ScanContent.Security.SAE, w.security)
        assertEquals(true, w.hidden)
    }

    @Test
    fun wifiOpenAndQuoted() {
        val open = ScanContent.parse("WIFI:T:nopass;S:Cafe;;") as ScanContent.Wifi
        assertEquals(ScanContent.Security.OPEN, open.security)
        assertNull(open.password)
        val quoted = ScanContent.parse("wifi:T:WEP;S:\"Quoted\";P:\"abcde\";;") as ScanContent.Wifi
        assertEquals("Quoted", quoted.ssid)
        assertEquals("abcde", quoted.password)
        assertEquals(ScanContent.Security.WEP, quoted.security)
    }

    @Test
    fun wifiWithoutTypeGuessesFromThePassword() {
        assertEquals(ScanContent.Security.WPA, (ScanContent.parse("WIFI:S:x;P:12345678;;") as ScanContent.Wifi).security)
        assertEquals(ScanContent.Security.OPEN, (ScanContent.parse("WIFI:S:x;;") as ScanContent.Wifi).security)
    }

    @Test
    fun wifiWithoutANameIsText() {
        assertTrue(ScanContent.parse("WIFI:T:WPA;P:abc;;") is ScanContent.Text)
    }

    @Test
    fun hugeCodesAreText() {
        val big = "https://example.org/" + "a".repeat(ScanContent.MAX_LENGTH)
        assertTrue(ScanContent.parse(big) is ScanContent.Text)
    }
}
