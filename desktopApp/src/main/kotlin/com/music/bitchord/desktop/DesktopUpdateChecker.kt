package com.music.bitchord.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Looks at the repo's "latest release" on GitHub once per launch and says whether it is newer
 * than the running build. The installer itself is picked here too — the release names its files
 * `ChordWave-<version>-windows-x64-setup.exe`, `ChordWave-<version>-macos-arm64.dmg` and so on
 * (see .github/workflows/release.yml) — so the dialog can download and install it in-app rather
 * than sending the user to the browser; see [DesktopUpdater].
 *
 * GitHub's "latest" skips pre-releases and drafts, so a `-beta` desktop build is only ever
 * nudged toward a published release.
 */
internal object DesktopUpdateChecker {

    /** How the picked installer is installed; see [DesktopUpdater.install]. */
    internal enum class InstallKind {
        /** Windows setup.exe: launched, then the app quits so the installer can replace it. */
        WINDOWS_SETUP,
        /** macOS .dmg: opened in Finder; the user drags ChordWave into Applications. */
        MAC_DMG,
        /** Linux .AppImage: swapped over the running one, then the app quits. */
        LINUX_APPIMAGE,
        /** Linux .deb: opened in the system package installer. */
        LINUX_DEB,
        /** No installer for this platform in the release: the browser is the only way. */
        BROWSER_ONLY,
    }

    data class UpdateInfo(
        val version: String,
        val releaseUrl: String,
        /** The installer for this platform, or null when the release has none. */
        val downloadUrl: String?,
        /** The installer's file name, or null when the release has none. */
        val fileName: String?,
        val installKind: InstallKind,
        val notes: String?,
    )

    sealed interface CheckResult {
        data object UpToDate : CheckResult
        data class Available(val info: UpdateInfo) : CheckResult
        data object Failed : CheckResult
    }

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/Rikki53/ChordWave-Multiplatform/releases/latest"

    val currentVersion: String = System.getProperty("chordwave.version") ?: "2.2.3"

    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
    }

    suspend fun check(): CheckResult {
        return try {
            val response = http.get(LATEST_RELEASE_URL) {
                header("Accept", "application/vnd.github+json")
            }
            if (!response.status.isSuccess()) return CheckResult.Failed
            val release = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
                ?: return CheckResult.Failed
            val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return CheckResult.Failed
            val url = release["html_url"]?.jsonPrimitive?.contentOrNull ?: return CheckResult.Failed
            val latest = tag.removePrefix("v")
            if (!isNewer(latest, currentVersion)) return CheckResult.UpToDate
            val assets = release["assets"]?.jsonArray?.mapNotNull { element ->
                val asset = element as? JsonObject ?: return@mapNotNull null
                if (asset["state"]?.jsonPrimitive?.contentOrNull != "uploaded") return@mapNotNull null
                val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val download = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                name to download
            }.orEmpty()
            val picked = pickInstallerAsset(assets.map { it.first })
            CheckResult.Available(
                UpdateInfo(
                    version = latest,
                    releaseUrl = url,
                    downloadUrl = picked?.let { (name, _) ->
                        assets.firstOrNull { it.first == name }?.second
                    },
                    fileName = picked?.first,
                    installKind = picked?.second ?: InstallKind.BROWSER_ONLY,
                    notes = release["body"]?.jsonPrimitive?.contentOrNull,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            CheckResult.Failed
        }
    }

    /** Which desktop this is running on, for installer matching. */
    internal enum class Platform { WINDOWS, MAC, LINUX, OTHER }

    internal fun currentPlatform(): Platform = when {
        DesktopPlatform.isWindows -> Platform.WINDOWS
        DesktopPlatform.isMac -> Platform.MAC
        DesktopPlatform.isLinux -> Platform.LINUX
        else -> Platform.OTHER
    }

    /** The macOS .dmg suffix for this machine's chip: arm64 for Apple Silicon, x64 for Intel. */
    internal fun macDmgSuffix(arch: String = System.getProperty("os.arch").orEmpty()): String =
        if (arch.equals("aarch64", ignoreCase = true) || arch.equals("arm64", ignoreCase = true)) {
            "-macos-arm64.dmg"
        } else {
            "-macos-x64.dmg"
        }

    /**
     * The installer suffixes this platform wants, best first, with how each one installs.
     *
     * Linux takes the AppImage when it *is* one — the running file can be swapped — and the .deb
     * otherwise, since a .deb install has no AppImage to swap.
     */
    internal fun installerCandidates(
        platform: Platform = currentPlatform(),
        macArch: String = System.getProperty("os.arch").orEmpty(),
        runningAsAppImage: Boolean = System.getenv("APPIMAGE")?.isNotBlank() == true,
    ): List<Pair<String, InstallKind>> = when (platform) {
        Platform.WINDOWS -> listOf("-windows-x64-setup.exe" to InstallKind.WINDOWS_SETUP)
        Platform.MAC -> listOf(macDmgSuffix(macArch) to InstallKind.MAC_DMG)
        Platform.LINUX -> if (runningAsAppImage) {
            listOf(
                ".AppImage" to InstallKind.LINUX_APPIMAGE,
                "-linux-amd64.deb" to InstallKind.LINUX_DEB,
            )
        } else {
            listOf(
                "-linux-amd64.deb" to InstallKind.LINUX_DEB,
                ".AppImage" to InstallKind.LINUX_APPIMAGE,
            )
        }
        Platform.OTHER -> emptyList()
    }

    /**
     * The release asset this platform installs, best candidate first. Matches on the platform
     * part of the file name so a version change does not matter.
     */
    internal fun pickInstallerAsset(
        assetNames: List<String>,
        candidates: List<Pair<String, InstallKind>> = installerCandidates(),
    ): Pair<String, InstallKind>? {
        for ((suffix, kind) in candidates) {
            assetNames.firstOrNull { it.endsWith(suffix, ignoreCase = true) }
                ?.let { return it to kind }
        }
        return null
    }

    private class Parsed(val parts: List<Int>, val preRelease: Boolean)

    private fun parse(raw: String): Parsed {
        val dash = raw.indexOf('-')
        val base = if (dash >= 0) raw.substring(0, dash) else raw
        return Parsed(base.split('.').map { it.toIntOrNull() ?: 0 }, dash >= 0)
    }

    /** Numeric comparison, with a `-betaN` build counted as older than the plain release it leads up to. */
    internal fun isNewer(latest: String, current: String): Boolean {
        val l = parse(latest)
        val c = parse(current)
        for (i in 0 until maxOf(l.parts.size, c.parts.size)) {
            val a = l.parts.getOrElse(i) { 0 }
            val b = c.parts.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return c.preRelease && !l.preRelease
    }
}
