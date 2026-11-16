package com.music.bitchord.desktop

import com.music.bitchord.desktop.DesktopUpdateChecker.InstallKind
import com.music.bitchord.desktop.DesktopUpdateChecker.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopUpdateCheckerTest {

    @Test
    fun `isNewer compares numerically across segments`() {
        assertTrue(DesktopUpdateChecker.isNewer("2.2.1", "2.2.0"))
        assertFalse(DesktopUpdateChecker.isNewer("2.2.0", "2.2.1"))
        assertFalse(DesktopUpdateChecker.isNewer("2.2.1", "2.2.1"))
        assertTrue(DesktopUpdateChecker.isNewer("2.10.0", "2.9.9"))
        assertTrue(DesktopUpdateChecker.isNewer("3.0.0", "2.9.9"))
    }

    @Test
    fun `isNewer counts a beta as older than its own release`() {
        assertTrue(DesktopUpdateChecker.isNewer("2.2.1", "2.2.1-beta1"))
        assertFalse(DesktopUpdateChecker.isNewer("2.2.1-beta1", "2.2.1"))
    }

    private val releaseAssets = listOf(
        "ChordWave-2.2.1-windows-x64-setup.exe",
        "ChordWave-2.2.1-windows-x64.msi",
        "ChordWave-2.2.1-windows-x64-portable.zip",
        "ChordWave-2.2.1-macos-arm64.dmg",
        "ChordWave-2.2.1-macos-x64.dmg",
        "ChordWave-2.2.1-macos-arm64.pkg",
        "ChordWave-2.2.1-linux-x86_64.AppImage",
        "ChordWave-2.2.1-linux-amd64.deb",
        "ChordWave-2.2.1-linux-x86_64.rpm",
    )

    @Test
    fun `windows picks the setup exe`() {
        val picked = DesktopUpdateChecker.pickInstallerAsset(
            releaseAssets,
            DesktopUpdateChecker.installerCandidates(Platform.WINDOWS),
        )
        assertEquals("ChordWave-2.2.1-windows-x64-setup.exe" to InstallKind.WINDOWS_SETUP, picked)
    }

    @Test
    fun `mac picks the dmg for its own chip`() {
        val arm = DesktopUpdateChecker.pickInstallerAsset(
            releaseAssets,
            DesktopUpdateChecker.installerCandidates(Platform.MAC, macArch = "aarch64"),
        )
        assertEquals("ChordWave-2.2.1-macos-arm64.dmg" to InstallKind.MAC_DMG, arm)
        val intel = DesktopUpdateChecker.pickInstallerAsset(
            releaseAssets,
            DesktopUpdateChecker.installerCandidates(Platform.MAC, macArch = "x86_64"),
        )
        assertEquals("ChordWave-2.2.1-macos-x64.dmg" to InstallKind.MAC_DMG, intel)
    }

    @Test
    fun `linux running as an appimage picks the appimage, otherwise the deb`() {
        val asAppImage = DesktopUpdateChecker.pickInstallerAsset(
            releaseAssets,
            DesktopUpdateChecker.installerCandidates(Platform.LINUX, runningAsAppImage = true),
        )
        assertEquals("ChordWave-2.2.1-linux-x86_64.AppImage" to InstallKind.LINUX_APPIMAGE, asAppImage)
        val installed = DesktopUpdateChecker.pickInstallerAsset(
            releaseAssets,
            DesktopUpdateChecker.installerCandidates(Platform.LINUX, runningAsAppImage = false),
        )
        assertEquals("ChordWave-2.2.1-linux-amd64.deb" to InstallKind.LINUX_DEB, installed)
    }

    @Test
    fun `no installer for the platform means no pick`() {
        assertNull(
            DesktopUpdateChecker.pickInstallerAsset(
                listOf("ChordWave-2.2.1-linux-x86_64.AppImage"),
                DesktopUpdateChecker.installerCandidates(Platform.WINDOWS),
            ),
        )
        assertNull(
            DesktopUpdateChecker.pickInstallerAsset(
                releaseAssets,
                DesktopUpdateChecker.installerCandidates(Platform.OTHER),
            ),
        )
    }

    @Test
    fun `mac dmg suffix follows the chip`() {
        assertEquals("-macos-arm64.dmg", DesktopUpdateChecker.macDmgSuffix("aarch64"))
        assertEquals("-macos-arm64.dmg", DesktopUpdateChecker.macDmgSuffix("arm64"))
        assertEquals("-macos-x64.dmg", DesktopUpdateChecker.macDmgSuffix("x86_64"))
        assertEquals("-macos-x64.dmg", DesktopUpdateChecker.macDmgSuffix("amd64"))
    }
}
