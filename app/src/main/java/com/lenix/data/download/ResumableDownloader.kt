package com.lenix.data.download

import com.lenix.vm.VmError
import com.lenix.vm.VmException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import com.lenix.util.Digests
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One layer to fetch: the signed manifest is the only source of these values.
 *
 * [sha256] is the content address: it names the cache file, is validated before
 * it is ever used as a filename, and is re-verified over the completed bytes.
 */
data class LayerSpec(
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
)

/**
 * Resumable HTTP downloader for RootFS layers (Phase 3, ADR-015).
 *
 * Implements the download step of docs/ROOTFS_SYSTEM.md §2 against the
 * content-addressed [LayerCache]:
 *
 *  - downloads stream into `<sha256>.layer.part` and are renamed to `<sha256>.layer`
 *    only after the full `sha256` matches — an interrupted install therefore
 *    leaves a byte-exact resume point, never a half-trusted layer;
 *  - a `.part` is resumed with `Range: bytes=<n>-`; the ETag captured next to it
 *    is sent as `If-Range` so a changed upstream restarts from zero instead of
 *    concatenating mismatched bytes (the final checksum is the second line of
 *    defense);
 *  - servers that ignore Range (plain 200), reject it (416), or resume at a
 *    different offset make the attempt restart from byte zero;
 *  - transient failures (I/O, short reads, 408/429/5xx) are retried with
 *    exponential backoff, always resuming from whatever is on disk;
 *  - coroutine cancellation is *not* an error: it leaves the `.part` in place so
 *    the next install attempt picks up at the same byte.
 *
 * Checks the completed bytes against [LayerSpec.sha256] itself (rename only after
 * a match); the installer separately re-verifies cached layers in its VERIFYING
 * step, per docs/ROOTFS_SYSTEM.md.
 */
class ResumableDownloader(
    val cache: LayerCache,
    private val client: OkHttpClient = defaultClient(),
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val backoffMillis: (attempt: Int) -> Long = { attempt ->
        minOf(RETRY_BACKOFF_BASE_MS shl (attempt - 1), RETRY_BACKOFF_CAP_MS)
    },
) {
    /**
     * Returns the completed, checksum-verified cache file for [spec], downloading
     * or resuming it as needed. [onProgress] receives aggregate progress for this
     * layer — starting at the resumed offset, not at zero — and must be fast.
     *
     * @throws VmException with [VmError.NETWORK_ERROR] after the retry budget is
     *   spent (or on a non-retryable HTTP status), [VmError.DOWNLOAD_CORRUPTED]
     *   when the server provably serves the wrong bytes, or
     *   [VmError.CHECKSUM_FAILED] when completed bytes never hash to [LayerSpec.sha256].
     */
    suspend fun download(
        spec: LayerSpec,
        onProgress: suspend (bytesDone: Long, bytesTotal: Long) -> Unit = { _, _ -> },
    ): File {
        require(spec.sizeBytes > 0) { "Layer ${spec.sha256} has no size." }
        val finalFile = cache.cachedFile(spec.sha256)

        if (finalFile.isFile) {
            if (matchesDigest(finalFile, spec.sha256)) {
                onProgress(spec.sizeBytes, spec.sizeBytes)
                return finalFile
            }
            // A cache entry that fails its own content address is corrupt — rebuild it.
            finalFile.delete()
        }

        var attempts = 0
        while (true) {
            attempts++
            when (val result = performAttempt(spec, onProgress)) {
                is AttemptResult.Completed -> return result.file
                is AttemptResult.Retry -> {
                    if (result.discardPart) {
                        cache.partFile(spec.sha256).delete()
                        cache.etagFile(spec.sha256).delete()
                    }
                    // A cancelled download must always surface as CancellationException
                    // (leaving the `.part` resume point), never as a wrapped error —
                    // even when the retry budget is already spent.
                    currentCoroutineContext().ensureActive()
                    if (attempts >= maxAttempts) {
                        throw VmException(result.error, result.reason)
                    }
                    delay(backoffMillis(attempts))
                }
            }
        }
    }

    /** Drops every in-flight `.part` (explicit user cancel — see LayerCache). */
    fun discardPartials() = cache.discardPartials()

    private suspend fun performAttempt(
        spec: LayerSpec,
        onProgress: suspend (Long, Long) -> Unit,
    ): AttemptResult {
        val partFile = cache.partFile(spec.sha256)
        val etagFile = cache.etagFile(spec.sha256)
        val offset = if (partFile.isFile) partFile.length() else 0L

        // A partial at/after the expected size cannot be valid — restart clean.
        if (offset > 0 && offset >= spec.sizeBytes) {
            return AttemptResult.Retry(
                reason = "Stale partial for ${spec.sha256.take(SHORT_DIGEST)}: " +
                    "$offset bytes on disk, layer is ${spec.sizeBytes}.",
                error = VmError.NETWORK_ERROR,
                discardPart = true,
            )
        }

        val request = Request.Builder()
            .url(spec.url)
            .apply {
                if (offset > 0) {
                    header("Range", "bytes=$offset-")
                    val etag = etagFile.takeIf { it.isFile }?.readText()?.trim()
                    if (!etag.isNullOrEmpty()) header("If-Range", etag)
                }
            }
            .build()

        val response = try {
            client.newCall(request).awaitResponse()
        } catch (e: IOException) {
            return AttemptResult.Retry(
                reason = "Network error while fetching ${spec.url}: ${e.message}",
                error = VmError.NETWORK_ERROR,
                discardPart = false,
            )
        }

        return response.use { readResponse(spec, it, partFile, etagFile, offset, onProgress) }
    }

    private suspend fun readResponse(
        spec: LayerSpec,
        response: Response,
        partFile: File,
        etagFile: File,
        offset: Long,
        onProgress: suspend (Long, Long) -> Unit,
    ): AttemptResult {
        fun retry(reason: String, error: VmError, discardPart: Boolean) =
            AttemptResult.Retry(reason, error, discardPart)

        val code = response.code
        if (code == HTTP_RANGE_NOT_SATISFIABLE) {
            return retry(
                "Server rejected the resume offset for ${spec.url} (HTTP 416).",
                VmError.NETWORK_ERROR,
                discardPart = true,
            )
        }
        if (code != HTTP_OK && code != HTTP_PARTIAL) {
            val retryable = code == HTTP_REQUEST_TIMEOUT ||
                code == HTTP_TOO_MANY_REQUESTS ||
                code in 500..599
            return retry("HTTP $code for ${spec.url}.", VmError.NETWORK_ERROR, discardPart = !retryable)
        }

        // Where this response's body starts in the final file.
        var startOffset = offset
        if (code == HTTP_PARTIAL) {
            val range = parseContentRange(response.header("Content-Range"))
            if (range == null || range.first != offset) {
                return retry(
                    "Server resumed at ${range?.first} but $offset bytes were already on disk.",
                    VmError.DOWNLOAD_CORRUPTED,
                    discardPart = true,
                )
            }
            if (range.second != spec.sizeBytes) {
                return retry(
                    "Server says the layer is ${range.second} bytes; the manifest says ${spec.sizeBytes}.",
                    VmError.DOWNLOAD_CORRUPTED,
                    discardPart = true,
                )
            }
        } else if (offset > 0) {
            // Plain 200 for a ranged request: the server ignored Range or the
            // If-Range ETag no longer matches. The bytes on disk are unusable.
            startOffset = 0L
            partFile.delete()
        }

        val body = response.body
            ?: return retry("Empty response body for ${spec.url}.", VmError.NETWORK_ERROR, false)

        val declaredLength = body.contentLength()
        if (declaredLength >= 0 && declaredLength != spec.sizeBytes - startOffset) {
            return retry(
                "Content-Length $declaredLength does not match the remaining " +
                    "${spec.sizeBytes - startOffset} bytes of the layer.",
                VmError.DOWNLOAD_CORRUPTED,
                discardPart = true,
            )
        }

        // Remember the ETag for validated resume after a process death.
        val etag = response.header("ETag")?.trim()
        if (etag.isNullOrEmpty()) {
            etagFile.delete()
        } else {
            etagFile.writeText(etag)
        }

        // Report the starting point first, so a resumed download shows up at its
        // real offset instead of jumping from 0% to the first chunk.
        onProgress(startOffset, spec.sizeBytes)

        val written = try {
            FileOutputStream(partFile, true).use { output ->
                val input = body.byteStream()
                val buffer = ByteArray(CHUNK_BYTES)
                var total = startOffset
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    total += read
                    onProgress(total, spec.sizeBytes)
                }
                output.flush()
                total
            }
        } catch (e: IOException) {
            // Failures *mid-body* (connection reset, read timeout, truncated
            // chunked encoding) are ordinary retryable network errors — the
            // bytes already on disk stay as the resume point.
            return retry(
                "Network error while streaming ${spec.url}: ${e.message}",
                VmError.NETWORK_ERROR,
                discardPart = false,
            )
        }

        if (written != spec.sizeBytes) {
            return retry(
                "Connection closed at $written of ${spec.sizeBytes} bytes for ${spec.url}.",
                VmError.NETWORK_ERROR,
                discardPart = false,
            )
        }
        if (!matchesDigest(partFile, spec.sha256)) {
            return retry(
                "Checksum mismatch for layer ${spec.sha256.take(SHORT_DIGEST)}.",
                VmError.CHECKSUM_FAILED,
                discardPart = true,
            )
        }

        val finalFile = cache.cachedFile(spec.sha256)
        if (finalFile.exists()) finalFile.delete()
        check(partFile.renameTo(finalFile)) {
            "Could not move ${partFile.name} into the layer cache."
        }
        etagFile.delete()
        return AttemptResult.Completed(finalFile)
    }

    private sealed interface AttemptResult {
        data class Completed(val file: File) : AttemptResult
        data class Retry(val reason: String, val error: VmError, val discardPart: Boolean) :
            AttemptResult
    }

    private fun matchesDigest(file: File, expectedSha256: String): Boolean =
        Digests.sha256Hex(file).equals(expectedSha256, ignoreCase = true)

    private fun parseContentRange(header: String?): Pair<Long, Long>? {
        if (header == null) return null
        val match = CONTENT_RANGE_REGEX.find(header) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        return start to total
    }

    /** Enqueue-based [Call] bridge that cancels the HTTP call with the coroutine. */
    private suspend fun Call.awaitResponse(): Response =
        suspendCancellableCoroutine { continuation ->
            enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }

                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }
            })
            continuation.invokeOnCancellation { runCatching { cancel() } }
        }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val CHUNK_BYTES = 64 * 1024
        const val SHORT_DIGEST = 12

        private const val CONNECT_TIMEOUT_SECONDS = 20L
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val WRITE_TIMEOUT_SECONDS = 30L
        private const val RETRY_BACKOFF_BASE_MS = 1000L
        private const val RETRY_BACKOFF_CAP_MS = 8000L

        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL = 206
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_TOO_MANY_REQUESTS = 429

        /** `Content-Range: bytes <start>-<end>/<total>` → (start, total). */
        private val CONTENT_RANGE_REGEX = Regex("bytes (\\d+)-(\\d+)/(\\d+)")

        /** Layer downloads are long by design — no whole-call timeout. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }
}
