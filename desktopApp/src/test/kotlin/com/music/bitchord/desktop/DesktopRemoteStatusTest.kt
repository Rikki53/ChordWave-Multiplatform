package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopRemoteStatusTest {

    @Test
    fun `parse reads a full status file`() {
        val status = DesktopRemoteStatus.parse(
            """{"suspended":true,"suspendMessage":"Down for maintenance.",""" +
                """"announcement":"Please update.","announcementId":"sec-1","minVersion":"2.2.3"}""",
        )!!
        assertTrue(status.suspended)
        assertEquals("Down for maintenance.", status.suspendMessage)
        assertEquals("Please update.", status.announcement)
        assertEquals("sec-1", status.announcementId)
        assertEquals("2.2.3", status.minVersion)
    }

    @Test
    fun `parse defaults an empty object to not suspended`() {
        val status = DesktopRemoteStatus.parse("{}")!!
        assertFalse(status.suspended)
        assertEquals("", status.suspendMessage)
        assertNull(status.announcement)
        assertEquals("", status.announcementId)
        assertNull(status.minVersion)
    }

    @Test
    fun `parse ignores unknown keys`() {
        val status = DesktopRemoteStatus.parse("""{"suspended":false,"futureField":123}""")!!
        assertFalse(status.suspended)
    }

    @Test
    fun `parse returns null for malformed JSON`() {
        assertNull(DesktopRemoteStatus.parse("not json"))
        assertNull(DesktopRemoteStatus.parse("""{"suspended":true"""))
        assertNull(DesktopRemoteStatus.parse(""))
    }

    @Test
    fun `parse returns null for wrong types (fail-open)`() {
        assertNull(DesktopRemoteStatus.parse("""{"suspended":1}"""))
        assertNull(DesktopRemoteStatus.parse("""[true]"""))
    }

    @Test
    fun `a quoted true still suspends (kotlinx reads quoted booleans leniently)`() {
        // kotlinx.serialization-json accepts "true"/"false" in quotes; only that literal
        // content suspends, anything else fails open — so a hand-edited file stays safe.
        assertEquals(true, DesktopRemoteStatus.parse("""{"suspended":"true"}""")?.suspended)
        assertEquals(false, DesktopRemoteStatus.parse("""{"suspended":"false"}""")?.suspended)
        assertNull(DesktopRemoteStatus.parse("""{"suspended":"yes"}"""))
    }

    @Test
    fun `parse keeps a missing suspended flag false`() {
        val status = DesktopRemoteStatus.parse("""{"announcement":"Hi","announcementId":"a1"}""")!!
        assertFalse(status.suspended)
        assertEquals("Hi", status.announcement)
    }

    @Test
    fun `serialize round-trips a status`() {
        val original = DesktopRemoteStatus.Status(
            suspended = true,
            suspendMessage = "Paused.",
            announcement = "Read this.",
            announcementId = "n-7",
            minVersion = "2.2.3",
        )
        assertEquals(original, DesktopRemoteStatus.parse(DesktopRemoteStatus.serialize(original)))
        // The all-off status survives too.
        val off = DesktopRemoteStatus.Status()
        assertEquals(off, DesktopRemoteStatus.parse(DesktopRemoteStatus.serialize(off)))
    }

    @Test
    fun `isBelowMinVersion compares numerically`() {
        assertTrue(DesktopRemoteStatus.isBelowMinVersion("2.2.3", "2.2.2"))
        assertFalse(DesktopRemoteStatus.isBelowMinVersion("2.2.2", "2.2.2"))
        assertFalse(DesktopRemoteStatus.isBelowMinVersion("2.2.2", "2.2.3"))
        assertTrue(DesktopRemoteStatus.isBelowMinVersion("2.10.0", "2.9.9"))
        assertTrue(DesktopRemoteStatus.isBelowMinVersion("3.0.0", "2.9.9"))
    }

    @Test
    fun `isBelowMinVersion is false without a minimum`() {
        assertFalse(DesktopRemoteStatus.isBelowMinVersion(null, "2.2.2"))
        assertFalse(DesktopRemoteStatus.isBelowMinVersion("", "2.2.2"))
        assertFalse(DesktopRemoteStatus.isBelowMinVersion("  ", "2.2.2"))
    }
}
