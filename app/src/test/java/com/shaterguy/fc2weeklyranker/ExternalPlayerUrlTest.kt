package com.shaterguy.fc2weeklyranker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalPlayerUrlTest {
    @Test
    fun acceptsHttpAndHttpsMediaUrls() {
        assertTrue(isExternalPlayerUrl("https://example.test/video.mp4"))
        assertTrue(isExternalPlayerUrl("http://media.example.test/live.m3u8?token=abc"))
    }

    @Test
    fun rejectsNonHttpOrMalformedUrls() {
        assertFalse(isExternalPlayerUrl("file:///sdcard/video.mp4"))
        assertFalse(isExternalPlayerUrl("content://media/external/video/1"))
        assertFalse(isExternalPlayerUrl("javascript:alert(1)"))
        assertFalse(isExternalPlayerUrl("https://"))
        assertFalse(isExternalPlayerUrl("not a url"))
    }
}
