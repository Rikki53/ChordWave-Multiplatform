package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopUpdaterTest {

    @Test
    fun `formatBytes renders human sizes`() {
        assertEquals("512 B", DesktopUpdater.formatBytes(512))
        assertEquals("2 KB", DesktopUpdater.formatBytes(2_048))
        assertEquals("339 MB", DesktopUpdater.formatBytes(339_381_760))
        assertEquals("1.5 GB", DesktopUpdater.formatBytes(1_500_000_000))
    }
}
