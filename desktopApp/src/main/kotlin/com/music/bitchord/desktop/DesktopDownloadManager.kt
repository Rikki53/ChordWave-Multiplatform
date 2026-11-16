package com.music.bitchord.desktop

import com.music.bitchord.data.innertube.PlayerClient
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Downloads resolved audio into the user's own ChordWave folder. */
object DesktopDownloadManager {
    private val activeJobs = ConcurrentHashMap<String, Job>()

    /** Keeps an interrupted transfer in place and resumes it when the user tries again. */
    suspend fun download(
        song: Song,
        quality: String = DesktopPersistence().string("download_quality", "LOSSLESS"),
        onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
    ): Result<Song> {
        val job = currentCoroutineContext()[Job]
        if (job != null) activeJobs[song.videoId] = job
        return try {
            require(song.localPath == null) { "This track is already local" }
            // The owned-source path logs its resolve; the normal walk did not, so a slow or hung
            // resolve was invisible in the log while the row showed nothing but a spinner.
            DesktopTrackLog.log("resolving '${song.title}' for download")
            val stream = DesktopMusicSources.resolve(song, quality, forDownload = true).getOrThrow()
            DesktopTrackLog.log(
                "  ${DesktopMusicSources.sourceNameFor(stream)} served it at " +
                    stream.format.summary.ifBlank { "an unstated format" },
            )
            val directory = Path.of(System.getProperty("user.home"), "Music", "ChordWave")
            Files.createDirectories(directory)
            val safeName = buildString {
                append(song.artist)
                append(" - ")
                append(song.title)
            }.replace(ILLEGAL_FILENAME, "_").trim().take(180).ifBlank { song.videoId }
            // A manifest is an index of the audio, not the audio: fetched as a file it saved a few
            // KB of XML as `.flac`. Its segments are fMP4, so it can only become FLAC or M4A.
            val manifest = DesktopManifestDownload.isManifest(stream)
            val extension = stream.format.fileExtension().let { if (manifest && it != "flac") "m4a" else it }
            val target = directory.resolve("$safeName.$extension")
            val temporary = directory.resolve(".$safeName.$extension.part")
            if (Files.isRegularFile(target) && Files.size(target) > 0L) {
                return Result.success(song.copy(localUri = target.toUri().toString(), localPath = target.toString()))
            }

            if (manifest) {
                // Segments are fetched and joined whole, so there is no partial file to resume.
                Files.deleteIfExists(temporary)
                Files.newOutputStream(temporary).buffered().use { output ->
                    DesktopManifestDownload.save(
                        stream,
                        extension,
                        output,
                        tempDir = java.io.File(System.getProperty("java.io.tmpdir")),
                        userAgent = DOWNLOAD_USER_AGENT,
                    ) { done, total -> onProgress(done, total) }
                }
                check(Files.size(temporary) > 0L) { "Download failed: nothing was sent" }
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                return Result.success(song.copy(localUri = target.toUri().toString(), localPath = target.toString()))
            }

            // googlevideo paces one long GET down to playback speed and serves it without a length,
            // which is why a track took minutes with no percentage: see Android's Downloader,
            // which fetches in bounded ranges for exactly this reason. A total the probe cannot
            // measure falls back to the single GET rather than failing a download that used to
            // work, slowly.
            val total = if (isGoogleVideo(stream.url)) contentLength(stream.url, stream.headers) else null
            if (total != null) {
                fetchRanged(song, quality, stream, temporary, total, onProgress)
            } else {
                fetchDirect(stream, temporary, onProgress)
            }
            check(Files.size(temporary) > 0L) { "Download failed: nothing was sent" }
            runCatching {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
            Result.success(song.copy(localUri = target.toUri().toString(), localPath = target.toString()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(failure)
        } finally {
            if (job != null) activeJobs.remove(song.videoId, job)
        }
    }

    fun cancel(videoId: String) {
        activeJobs[videoId]?.cancel(CancellationException("Download cancelled"))
    }

    /**
     * Fetches a googlevideo stream in sequential bounded ranges, Android's `Downloader.fetch`
     * ported to desktop.
     *
     * Ranges are served at line rate where one long GET is paced to playback speed, and the
     * probed [total] makes the progress determinate from the first byte. Sequential rather than
     * parallel, for Android's reasons: a typical track is a few megabytes, and splitting it buys
     * nothing a listener could perceive while costing the temp files and reassembly a cancelled
     * download would then have to clean up.
     *
     * A partial file is resumed from where it stopped, and a URL that refuses mid-download earns
     * one re-resolve — the identity behind it may have been stood down, not the track gone — with
     * the length checked before continuing, so one stream is never resumed into the middle of
     * another.
     */
    private suspend fun fetchRanged(
        song: Song,
        quality: String,
        stream: DesktopStream,
        temporary: Path,
        total: Long,
        onProgress: (downloaded: Long, total: Long?) -> Unit,
    ) {
        var url = stream.url
        var headers = stream.headers
        val existing = if (Files.isRegularFile(temporary)) Files.size(temporary) else 0L
        // A part file that already holds the whole track is a finished download interrupted
        // between the last byte and the move; one longer than the track belongs to another stream.
        if (existing == total) return
        if (existing > total) Files.deleteIfExists(temporary)
        var position = if (existing in 1..<total) existing else 0L
        var reresolved = false
        Files.newOutputStream(
            temporary,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            if (position > 0L) StandardOpenOption.APPEND else StandardOpenOption.TRUNCATE_EXISTING,
        ).buffered().use { output ->
            onProgress(position, total)
            while (position < total) {
                currentCoroutineContext().ensureActive()
                val length = chunkLength(position, total, url)
                val request = DesktopDownloadHttp.request(
                    url,
                    DOWNLOAD_USER_AGENT,
                    headers,
                    rangeFrom = position,
                    rangeTo = position + length - 1,
                )
                val response = DesktopDownloadHttp.client.newCall(request).execute()
                if (response.code in REFUSAL_CODES) {
                    val code = response.code
                    response.close()
                    if (reresolved) error("Download refused after ${position}B (HTTP $code)")
                    reresolved = true
                    DesktopTrackLog.log("re-resolving '${song.title}' after HTTP $code at $position")
                    val fresh = DesktopMusicSources.resolve(song, quality, forDownload = true).getOrThrow()
                    url = fresh.url
                    headers = fresh.headers
                    if (contentLength(url, headers) != total) {
                        error("The stream changed mid-download — try again")
                    }
                    continue
                }
                response.use {
                    // A 200 here is a server ignoring the range and sending the whole file from
                    // the start: writing its opening into the middle of the output would commit
                    // a file of the right length that stops halfway through the song.
                    check(it.code == HTTP_PARTIAL) { "Download failed (HTTP ${it.code})" }
                    val body = it.body ?: error("Download failed: empty response")
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var readForChunk = 0L
                        while (readForChunk < length) {
                            currentCoroutineContext().ensureActive()
                            val wanted = minOf(buffer.size.toLong(), length - readForChunk).toInt()
                            val read = input.read(buffer, 0, wanted)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            readForChunk += read
                            position += read
                            onProgress(position, total)
                        }
                        // A range that stops short is not fatal on its own — the next pass simply
                        // asks for what is left — but one that yields nothing at all would loop
                        // here forever.
                        if (readForChunk == 0L) error("Download stalled at ${position}B")
                    }
                }
            }
        }
    }

    /**
     * Fetches a stream that isn't googlevideo's in one GET, Android's `Downloader.fetchDirect`.
     *
     * The chunk loop exists to defeat googlevideo's pacing, and nothing else paces bytes that
     * way. Worse, a server that ignores `Range` answers 200 with the whole file rather than 206
     * with the slice asked for — and that loop would then write the opening of the file into the
     * middle of the output. A partial file is resumed when the server honours the range, and
     * restarted when it does not.
     */
    private suspend fun fetchDirect(
        stream: DesktopStream,
        temporary: Path,
        onProgress: (downloaded: Long, total: Long?) -> Unit,
    ) {
        val existing = if (Files.isRegularFile(temporary)) Files.size(temporary) else 0L
        val request = DesktopDownloadHttp.request(
            stream.url,
            DOWNLOAD_USER_AGENT,
            stream.headers,
            rangeFrom = existing.takeIf { it > 0L },
        )
        DesktopDownloadHttp.client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Download failed (HTTP ${response.code})" }
            val body = response.body
            val append = existing > 0L && response.code == HTTP_PARTIAL
            val offset = if (append) existing else 0L
            val total = response.header("Content-Range")
                ?.substringAfterLast('/')
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: body.contentLength().takeIf { it > 0L }?.let { it + offset }
            if (!append && existing > 0L) Files.deleteIfExists(temporary)
            Files.newOutputStream(
                temporary,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                if (append) StandardOpenOption.APPEND else StandardOpenOption.TRUNCATE_EXISTING,
            ).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = offset
                    onProgress(downloaded, total)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
        }
    }

    /**
     * How many bytes the whole track is.
     *
     * Every progressive googlevideo URL carries it as `clen`, which costs no request at all. The
     * `bytes=0-0` probe behind it is for the URLs that don't, and reads the total out of the
     * `Content-Range` header of a one-byte response.
     */
    private fun contentLength(url: String, headers: Map<String, String>): Long? {
        totalFromUrl(url)?.let { return it }
        return runCatching {
            val request = DesktopDownloadHttp.request(
                url,
                DOWNLOAD_USER_AGENT,
                headers,
                rangeFrom = 0L,
                rangeTo = 0L,
            )
            DesktopDownloadHttp.client.newCall(request).execute().use { response ->
                totalFromContentRange(response.header("Content-Range"))
            }
        }.onFailure {
            DesktopTrackLog.log("could not measure the track: ${it.message}")
        }.getOrNull()
    }

    /** Whether [url] is googlevideo's — the only host the bounded-range fetch is for. */
    internal fun isGoogleVideo(url: String): Boolean =
        url.toHttpUrlOrNull()?.host?.endsWith("googlevideo.com") == true

    /**
     * How many bytes the next range asks for: the chunk size, the client's range limit, and what
     * is left of the track, whichever is smallest.
     */
    internal fun chunkLength(position: Long, total: Long, url: String): Long =
        minOf(CHUNK_BYTES, PlayerClient.rangeBytesFor(url), total - position)

    /** The total a `Content-Range` header states after the slash, or null when it states none. */
    internal fun totalFromContentRange(header: String?): Long? =
        header?.substringAfterLast('/')?.toLongOrNull()?.takeIf { it > 0L }

    /** The total a googlevideo URL carries as `clen`, or null when it carries none. */
    internal fun totalFromUrl(url: String): Long? =
        url.toHttpUrlOrNull()?.queryParameter("clen")?.toLongOrNull()?.takeIf { it > 0L }

    /**
     * The file saved for [song], or null when the record has outlived it.
     *
     * A download record is a claim about a folder this app does not own: the listener is expected
     * to manage it with a file manager, so an entry can name a file that has since been moved or
     * deleted. Android checks before it answers for the same reason — without it the player is
     * handed a path that is simply not there and the track refuses to play, saying only "could not
     * open stream".
     */
    fun savedFile(song: Song): Path? {
        val path = song.localPath?.let { runCatching { Path.of(it) }.getOrNull() }
            ?: song.localUri?.takeIf { it.startsWith("file:") }
                ?.let { runCatching { Path.of(URI(it)) }.getOrNull() }
            ?: return null
        return path.takeIf { Files.isRegularFile(it) && runCatching { Files.size(it) > 0 }.getOrDefault(false) }
    }

    /** Whether [song] is really on this computer, rather than merely recorded as being. */
    fun isSaved(song: Song): Boolean = savedFile(song) != null

    /**
     * The records that still name a file, with the rest dropped.
     *
     * A claim that has just been shown to be false is not worth keeping to be shown false again on
     * the next play.
     */
    fun verified(downloads: List<Song>): List<Song> = downloads.filter(::isSaved)

    /** Deletes the file saved for [song]. Returns whether there was one to delete. */
    fun delete(song: Song): Boolean {
        val file = savedFile(song) ?: return false
        return runCatching { Files.deleteIfExists(file) }
            .onFailure { DesktopTrackLog.log("could not delete the download of '${song.title}': ${it.message}") }
            .getOrDefault(false)
    }

    private fun DesktopStreamFormat.fileExtension(): String {
        val codec = codec.orEmpty().substringAfterLast('/').substringBefore(';').lowercase()
        return when (codec) {
            "flac" -> "flac"
            "wav", "wave" -> "wav"
            "alac" -> "m4a"
            "aac" -> "m4a"
            "mp3" -> "mp3"
            "ogg", "opus" -> "ogg"
            "webm" -> "webm"
            else -> "m4a"
        }
    }

    private const val HTTP_PARTIAL = 206

    /** Matches Android's range size: large enough to amortise, small enough to cancel promptly. */
    private const val CHUNK_BYTES = 2L * 1024 * 1024

    /** A URL that served its opening and then refuses: the identity behind it was stood down. */
    private val REFUSAL_CODES = setOf(403, 404, 410)
    private const val DOWNLOAD_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    private val ILLEGAL_FILENAME = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")
}
