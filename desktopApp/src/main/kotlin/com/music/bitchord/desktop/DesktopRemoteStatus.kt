package com.music.bitchord.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The remote kill-switch and announcement channel: a small JSON file on the ChordWave website
 * that Riki can flip without shipping a build.
 *
 * ```json
 * {"suspended": false, "suspendMessage": "", "announcement": null,
 *  "announcementId": "", "minVersion": null}
 * ```
 *
 * - `suspended: true` deactivates the app remotely (a serious security problem, a broken
 *   release, anything that must stop *now*). The app shows a blocking screen until the flag is
 *   lifted; the block is never sticky.
 * - `announcement` + `announcementId` show a one-time message (a security notice, a migration
 *   note) — once per id, dismissed ids are remembered.
 * - `minVersion` (e.g. "2.2.3") nudges older builds toward the in-app updater; it never
 *   hard-blocks on its own — only the suspend flag blocks.
 *
 * **Fail-open, by design.** A network error, a timeout, an HTTP error, or a malformed file all
 * return null from [fetch], and the app carries on as normal. Only a successfully parsed
 * `suspended: true` ever blocks the app — a server hiccup must never lock users out. A
 * fresh install with no cache and no network therefore works normally; a launch with no
 * network but a cached suspension still honours it (see [loadCached]).
 *
 * The same schema is meant to be shared by the phone, TV and Lite builds later: the file is
 * app-agnostic, and only the checking + UI code is per-platform.
 */
internal object DesktopRemoteStatus {

    /** The status file on the ChordWave website (TX3: /root/chordwave-web/status.json). */
    const val STATUS_URL = "https://chordwave.rikinurjaman.my.id/status.json"

    /** Re-checked this often while the app runs, so a suspension — or its lifting — lands without a restart. */
    const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

    /** Shown when the server suspends the app without giving its own message. */
    const val DEFAULT_SUSPEND_MESSAGE =
        "ChordWave is temporarily unavailable. Please check back later."

    /** The last successfully fetched status, as JSON, for offline launches. */
    const val KEY_CACHED_STATUS = "remote_status_cache"

    /** The id of the announcement the user already dismissed. */
    const val KEY_DISMISSED_ANNOUNCEMENT = "remote_announcement_dismissed"

    @Serializable
    data class Status(
        val suspended: Boolean = false,
        val suspendMessage: String = "",
        val announcement: String? = null,
        val announcementId: String = "",
        val minVersion: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 10_000
        }
    }

    /**
     * Parses the status file. Returns null for anything malformed — wrong types, broken JSON,
     * a top-level value that is not an object — so callers treat it as "no status" (fail-open).
     * Missing fields fall back to their defaults (`suspended` defaults to false: a partial file
     * never suspends the app).
     */
    internal fun parse(raw: String): Status? =
        runCatching { json.decodeFromString<Status>(raw) }.getOrNull()

    internal fun serialize(status: Status): String = json.encodeToString(status)

    /** The last status this machine fetched, or null when it never fetched one (or the cache is unreadable). */
    internal fun loadCached(persistence: DesktopPersistence): Status? {
        val raw = persistence.string(KEY_CACHED_STATUS)
        if (raw.isBlank()) return null
        return parse(raw)
    }

    internal fun saveCached(persistence: DesktopPersistence, status: Status) {
        persistence.saveString(KEY_CACHED_STATUS, serialize(status))
    }

    /** True when the running build is older than the minimum the server asks for. */
    internal fun isBelowMinVersion(minVersion: String?, currentVersion: String): Boolean {
        if (minVersion.isNullOrBlank()) return false
        return DesktopUpdateChecker.isNewer(minVersion, currentVersion)
    }

    /**
     * Fetches the live status. Returns null on ANY failure — network error, timeout, HTTP
     * error, malformed JSON — and the app carries on as normal (fail-open). Every outcome is
     * logged, so a flip can be confirmed from desktop.log without watching the screen.
     */
    suspend fun fetch(): Status? {
        return try {
            val response = http.get(STATUS_URL)
            if (!response.status.isSuccess()) {
                DesktopTrackLog.log("remote status: HTTP ${response.status.value} (fail-open)")
                return null
            }
            val status = parse(response.bodyAsText())
            if (status == null) {
                DesktopTrackLog.log("remote status: malformed response (fail-open)")
                return null
            }
            DesktopTrackLog.log(
                "remote status: suspended=${status.suspended}" +
                    (status.announcementId.takeIf { it.isNotBlank() }?.let { " announcement=$it" } ?: "") +
                    (status.minVersion?.let { " minVersion=$it" } ?: ""),
            )
            status
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DesktopTrackLog.log("remote status: fetch failed (${e.message}) (fail-open)")
            null
        }
    }
}
