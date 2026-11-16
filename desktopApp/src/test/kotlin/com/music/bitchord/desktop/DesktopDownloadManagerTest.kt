package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopDownloadManagerTest {

    private val googlevideo = "https://rr1---sn-abcd.googlevideo.com/videoplayback?c=ANDROID"

    @Test
    fun `isGoogleVideo matches only googlevideo hosts`() {
        assertTrue(DesktopDownloadManager.isGoogleVideo(googlevideo))
        assertTrue(DesktopDownloadManager.isGoogleVideo("https://lh3.googlevideo.com/v?x=1"))
        assertFalse(DesktopDownloadManager.isGoogleVideo("https://www.jiosaavn.com/api.php"))
        assertFalse(DesktopDownloadManager.isGoogleVideo("https://cdn.example.com/track.flac"))
        assertFalse(DesktopDownloadManager.isGoogleVideo("not a url"))
    }

    @Test
    fun `chunkLength is capped by the client's range limit`() {
        assertEquals(1024L * 1024, DesktopDownloadManager.chunkLength(0, 5L * 1024 * 1024, googlevideo))
    }

    @Test
    fun `chunkLength narrows for narrow clients`() {
        val narrow = "https://rr1---sn-abcd.googlevideo.com/videoplayback?c=TVHTML5_SIMPLY_EMBEDDED_PLAYER"
        assertEquals(512L * 1024, DesktopDownloadManager.chunkLength(0, 5L * 1024 * 1024, narrow))
    }

    @Test
    fun `chunkLength never overruns the end of the track`() {
        val total = 4_862_028L
        assertEquals(100L, DesktopDownloadManager.chunkLength(total - 100, total, googlevideo))
    }

    @Test
    fun `chunkLength caps at two megabytes off googlevideo`() {
        assertEquals(
            2L * 1024 * 1024,
            DesktopDownloadManager.chunkLength(0, 5L * 1024 * 1024, "https://cdn.example.com/track.flac"),
        )
    }

    @Test
    fun `totalFromContentRange reads the total after the slash`() {
        assertEquals(4_862_028L, DesktopDownloadManager.totalFromContentRange("bytes 0-0/4862028"))
        assertNull(DesktopDownloadManager.totalFromContentRange("bytes 0-0/*"))
        assertNull(DesktopDownloadManager.totalFromContentRange("bytes 0-0/0"))
        assertNull(DesktopDownloadManager.totalFromContentRange(null))
    }

    @Test
    fun `totalFromUrl reads clen when present`() {
        assertEquals(
            4_862_028L,
            DesktopDownloadManager.totalFromUrl("$googlevideo&clen=4862028"),
        )
        assertNull(DesktopDownloadManager.totalFromUrl(googlevideo))
        assertNull(DesktopDownloadManager.totalFromUrl("$googlevideo&clen=0"))
    }
}
