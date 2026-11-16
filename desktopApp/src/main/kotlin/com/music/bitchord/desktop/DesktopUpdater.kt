package com.music.bitchord.desktop

import com.music.bitchord.desktop.DesktopUpdateChecker.InstallKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * The in-app half of updating: downloads the installer the checker picked, then installs it
 * without ever opening the browser — the way ChordWave Lite does.
 *
 * What "install" means depends on the platform, because each one replaces a running app
 * differently:
 * - Windows: the setup.exe is launched a couple of seconds after the app quits, so the
 *   installer never finds its own files locked.
 * - macOS: the .dmg is opened in Finder; the user drags ChordWave into Applications. macOS
 *   cannot silently replace a running .app, so this step stays manual by design.
 * - Linux AppImage: the downloaded file is swapped over the running AppImage and the app
 *   quits; the next launch is the new version.
 * - Linux .deb: opened in the system package installer, which asks for the admin password
 *   itself.
 */
internal object DesktopUpdater {

    /** What installing ended in, so the dialog knows whether to quit or just say what happened. */
    sealed interface InstallOutcome {
        /** The installer is running (or the AppImage is swapped): the app should quit now. */
        data object QuitNow : InstallOutcome
        /** The installer was opened for the user; the app keeps running. */
        data class Opened(val kind: InstallKind) : InstallOutcome
        /** The file was saved somewhere the user can reach it, and its folder opened. */
        data class Saved(val file: File) : InstallOutcome
    }

    private val downloadHttp = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 20_000
            // A socket that goes quiet for a minute is a dead download; the request as a whole
            // has no timeout, because a ~350 MB installer legitimately takes a while.
            socketTimeoutMillis = 60_000
        }
    }

    private fun updateDir(): File =
        File(System.getProperty("java.io.tmpdir"), "chordwave-update").apply { mkdirs() }

    /**
     * Downloads [url] to the update cache as [fileName], reporting progress as it goes.
     * A `.part` file is written first and renamed only once complete, so a half-download is
     * never mistaken for a finished installer; the expected size is checked when the server
     * sent one. Cancelling the caller's coroutine cancels the download.
     */
    suspend fun download(
        url: String,
        fileName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val part = File(updateDir(), "$fileName.part")
            val target = File(updateDir(), fileName)
            if (part.exists()) part.delete()
            downloadHttp.prepareGet(url) {
                header("User-Agent", "ChordWave/v${DesktopUpdateChecker.currentVersion}")
                header("Accept", "application/octet-stream")
            }.execute { response ->
                check(response.status.isSuccess()) { "Download failed: HTTP ${response.status.value}" }
                val total = response.contentLength()?.takeIf { it > 0 }
                val channel = response.bodyAsChannel()
                var downloaded = 0L
                FileOutputStream(part).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = channel.readAvailable(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
                if (total != null) {
                    check(downloaded == total) {
                        "Download incomplete: got $downloaded of $total bytes"
                    }
                }
                check(downloaded > 0) { "Downloaded file is empty" }
            }
            if (target.exists()) target.delete()
            check(part.renameTo(target)) { "Could not finish the download" }
            Result.success(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Installs a downloaded installer. Never blocks on the installer itself: it is launched
     * and left to run, and the result says what the app should do next.
     */
    fun install(file: File, kind: InstallKind): Result<InstallOutcome> = runCatching {
        when (kind) {
            InstallKind.WINDOWS_SETUP -> {
                launchWindowsInstaller(file)
                InstallOutcome.QuitNow
            }
            InstallKind.MAC_DMG -> {
                ProcessBuilder("open", file.absolutePath).start()
                InstallOutcome.Opened(kind)
            }
            InstallKind.LINUX_DEB -> {
                openWithSystemInstaller(file)
                InstallOutcome.Opened(kind)
            }
            InstallKind.LINUX_APPIMAGE -> swapAppImage(file)
            InstallKind.BROWSER_ONLY -> error("No installer to install")
        }
    }

    /**
     * The setup.exe starts two seconds after the app quits: launched straight away, the
     * installer can still find the app's own files locked. PowerShell hides the helper
     * window; if it is unavailable, opening the installer directly still works — the app
     * quits right after, and the installer's file checks happen later.
     */
    private fun launchWindowsInstaller(file: File) {
        val escaped = file.absolutePath.replace("'", "''")
        runCatching {
            ProcessBuilder(
                "powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command",
                "Start-Sleep -Seconds 2; Start-Process '$escaped'",
            ).start()
        }.getOrElse {
            java.awt.Desktop.getDesktop().open(file)
        }
    }

    private fun openWithSystemInstaller(file: File) {
        runCatching {
            ProcessBuilder("xdg-open", file.absolutePath).start()
        }.getOrElse {
            java.awt.Desktop.getDesktop().open(file)
        }
    }

    /**
     * The AppImage the app is running from, swapped for the downloaded one. Replacing the
     * path does not disturb the running process — it keeps the old file open — so the next
     * launch is the new version. When the app is not running as an AppImage there is
     * nothing to swap, so the file is left in Downloads instead.
     */
    private fun swapAppImage(file: File): InstallOutcome {
        val current = System.getenv("APPIMAGE")?.takeIf { it.isNotBlank() }?.let(::File)
            ?.takeIf { it.isFile }
            ?: return saveToDownloads(file)
        val staged = File(current.parentFile, ".${current.name}.chordwave-new")
        file.copyTo(staged, overwrite = true)
        staged.setExecutable(true)
        try {
            Files.move(
                staged.toPath(),
                current.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            Files.move(staged.toPath(), current.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return InstallOutcome.QuitNow
    }

    private fun saveToDownloads(file: File): InstallOutcome {
        val downloads = File(System.getProperty("user.home"), "Downloads")
            .takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))
        val saved = File(downloads, file.name)
        if (saved.absolutePath != file.absolutePath) file.copyTo(saved, overwrite = true)
        saved.setExecutable(true)
        runCatching { ProcessBuilder("xdg-open", downloads.absolutePath).start() }
        return InstallOutcome.Saved(saved)
    }

    /** "340 MB", "1.5 GB" — for the download progress line. */
    internal fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}
