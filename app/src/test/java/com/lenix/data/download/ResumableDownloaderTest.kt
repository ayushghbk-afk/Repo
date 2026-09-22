package com.lenix.data.download

import com.lenix.vm.VmError
import com.lenix.vm.VmException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Phase 3 contract tests for the resumable downloader, against a MockWebServer
 * that mimics a Range-capable static file host (the semantics GitHub Releases
 * layer assets rely on).
 *
 * Test clients disable OkHttp's internal connection retries so the request
 * counts assert *our* retry policy, not OkHttp's.
 */
class ResumableDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer

    /** Path → bytes, e.g. "/base.layer". Read by the dispatcher on its own thread. */
    private val files = ConcurrentHashMap<String, ByteArray>()

    private val data = ByteArray(100_000) { (it % 251).toByte() }

    /** `Range: bytes=<n>-` → <n>, used by the inline throttled dispatchers. */
    private val rangeStart = Regex("bytes=(\\d+)-")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = RangeFileServer(files)
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun cache(): LayerCache = LayerCache(tmp.newFolder("cache-${System.nanoTime()}"))

    private fun downloader(
        cache: LayerCache,
        maxAttempts: Int = 3,
        client: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
    ) = ResumableDownloader(
        cache = cache,
        client = client,
        maxAttempts = maxAttempts,
        backoffMillis = { 0 },
    )

    private fun spec(path: String = "/base.layer", bytes: ByteArray = data) = LayerSpec(
        url = server.url(path).toString(),
        sizeBytes = bytes.size.toLong(),
        sha256 = sha256(bytes),
    )

    @Test
    fun `downloads a full layer, verifies it and moves it into the cache`() = runBlocking {
        files["/base.layer"] = data
        val cache = cache()
        val layer = spec()

        val progress = mutableListOf<Long>()
        val file = downloader(cache).download(layer) { done, _ -> progress.add(done) }

        assertEquals(cache.cachedFile(layer.sha256), file)
        assertArrayEquals(data, file.readBytes())
        assertFalse(cache.partFile(layer.sha256).exists())
        assertFalse(cache.etagFile(layer.sha256).exists())
        assertEquals(data.size.toLong(), progress.last())
        assertEquals(progress.toList(), progress.sorted())
    }

    @Test
    fun `resumes from an existing part with Range and If-Range`() = runBlocking {
        files["/base.layer"] = data
        val cache = cache()
        val layer = spec()

        // Simulate a previous, interrupted run: some bytes on disk plus its ETag.
        cache.partFile(layer.sha256).writeBytes(data.copyOfRange(0, 1234))
        cache.etagFile(layer.sha256).writeText("\"v1\"")

        val progress = mutableListOf<Long>()
        val file = downloader(cache).download(layer) { done, _ -> progress.add(done) }

        // Exactly one request, resuming at the part size with both validators.
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("bytes=1234-", request.getHeader("Range"))
        assertEquals("\"v1\"", request.getHeader("If-Range"))
        // Progress starts at the resumed offset, not at zero.
        assertEquals(1234L, progress.first())
        assertEquals(data.size.toLong(), progress.last())
        assertArrayEquals(data, file.readBytes())
    }

    @Test
    fun `restarts from zero when the server ignores range validation`() = runBlocking {
        files["/base.layer"] = data
        val cache = cache()
        val layer = spec()

        // A part whose ETag no longer matches upstream.
        cache.partFile(layer.sha256).writeBytes(data.copyOfRange(0, 500))
        cache.etagFile(layer.sha256).writeText("\"v1\"")

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("ETag", "\"v2\"")
                    .setBody(Buffer().write(data))
        }

        val file = downloader(cache).download(layer)

        assertArrayEquals(data, file.readBytes())
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertNotNull(request.getHeader("Range"))
        assertEquals("\"v1\"", request.getHeader("If-Range"))
    }

    @Test
    fun `a 416 clears the part and restarts from zero`() = runBlocking {
        files["/base.layer"] = data
        val cache = cache()
        val layer = spec()
        cache.partFile(layer.sha256).writeBytes(data.copyOfRange(0, 1234))

        // Ranged requests are rejected; plain requests get the whole file.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.getHeader("Range") != null) {
                    MockResponse().setResponseCode(416)
                } else {
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("ETag", "\"v1\"")
                        .setBody(Buffer().write(data))
                }
            }
        }

        val file = downloader(cache, maxAttempts = 2).download(layer)

        assertArrayEquals(data, file.readBytes())
        assertEquals(2, server.requestCount)
        assertEquals("bytes=1234-", server.takeRequest().getHeader("Range"))
        assertNull(server.takeRequest().getHeader("Range"))
    }

    @Test
    fun `checksum mismatch retries from scratch then fails with CHECKSUM_FAILED`() {
        val wrongBytes = ByteArray(data.size) { (it % 247).toByte() }
        files["/base.layer"] = wrongBytes
        val cache = cache()
        val layer = spec()

        val exception = assertThrows(VmException::class.java) {
            runBlocking { downloader(cache, maxAttempts = 3).download(layer) }
        }

        assertEquals(VmError.CHECKSUM_FAILED, exception.error)
        // Every attempt restarted from byte zero, and nothing partial survived.
        assertEquals(3, server.requestCount)
        assertFalse(cache.partFile(layer.sha256).exists())
    }

    @Test
    fun `retryable http failures keep the partial and stop after the retry budget`() {
        files["/base.layer"] = data
        val cache = cache()
        val layer = spec()
        cache.partFile(layer.sha256).writeBytes(data.copyOfRange(0, 1234))

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(503)
        }

        val exception = assertThrows(VmException::class.java) {
            runBlocking { downloader(cache, maxAttempts = 3).download(layer) }
        }

        assertEquals(VmError.NETWORK_ERROR, exception.error)
        assertEquals(3, server.requestCount)
        // The resume point is intact for the next attempt.
        assertEquals(1234L, cache.partFile(layer.sha256).length())
    }

    @Test
    fun `io failures keep the partial and stop after the retry budget`() {
        files["/base.layer"] = data
        val cache = cache()
        val layer = spec()
        cache.partFile(layer.sha256).writeBytes(data.copyOfRange(0, 1234))

        // Every response transfers only half the remaining bytes and then severs
        // the socket mid-body: a plain mid-stream I/O failure, no timers needed.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val from = request.getHeader("Range")
                    ?.let { rangeStart.find(it)?.groupValues?.get(1)?.toLongOrNull() }
                    ?: 0L
                return MockResponse()
                    .setResponseCode(if (from > 0L) 206 else 200)
                    .setHeader("ETag", "\"v1\"")
                    .setHeader("Content-Range", "bytes $from-${data.size - 1}/${data.size}")
                    .setBody(Buffer().write(data.copyOfRange(from.toInt(), data.size)))
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            }
        }

        val exception = assertThrows(VmException::class.java) {
            runBlocking { downloader(cache, maxAttempts = 2).download(layer) }
        }

        assertEquals(VmError.NETWORK_ERROR, exception.error)
        // The retry budget held — but the *wire* count is 1 or 2: after a
        // mid-body sever, OkHttp may fail the second call at the connection
        // layer before its request ever reaches the server. Either way the
        // downloader made at most maxAttempts attempts; the strict
        // "attempts == wire requests" budget is proven by the HTTP-503 test
        // above, whose responses complete cleanly.
        val wireRequests = server.requestCount
        assertTrue("wire requests: $wireRequests", wireRequests in 1..2)
        // Each severed attempt kept the bytes that did arrive, so the resume
        // point advanced past the original 1234 bytes but never restarted from
        // zero (half, then half of the remainder: ~50–75k bytes on disk).
        val partLength = cache.partFile(layer.sha256).length()
        assertTrue("resume point survived: $partLength bytes", partLength in 1234L until data.size.toLong())
    }

    @Test
    fun `a completed layer in the cache is never requested again`() = runBlocking {
        val cache = cache()
        val layer = spec()
        cache.cachedFile(layer.sha256).writeBytes(data)

        val file = downloader(cache).download(layer)

        assertEquals(cache.cachedFile(layer.sha256), file)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a corrupt cache entry is re-downloaded`() = runBlocking {
        files["/base.layer"] = data
        val cache = cache()
        val layer = spec()
        cache.cachedFile(layer.sha256).writeBytes(ByteArray(data.size))

        val file = downloader(cache).download(layer)

        assertArrayEquals(data, file.readBytes())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `cancellation leaves the partial in place for the next attempt`() {
        files["/base.layer"] = data
        val cache = cache()
        val layer = spec()
        val downloader = downloader(cache)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("ETag", "\"v1\"")
                    .setBody(Buffer().write(data))
                    .throttleBody(1024, 50, TimeUnit.MILLISECONDS)
        }

        // The download must run on a worker thread: its body reads are blocking,
        // so on runBlocking's single event-loop thread delay(400) could never
        // preempt them and the "cancellation" would only land after completion.
        runBlocking {
            val job = launch(Dispatchers.IO) {
                downloader.download(layer)
            }
            delay(400)
            job.cancelAndJoin()
        }

        val part = cache.partFile(layer.sha256)
        assertTrue("partial download should survive cancellation", part.isFile)
        val resumedFrom = part.length()
        assertTrue(resumedFrom in 1 until data.size.toLong())

        // And the next attempt resumes from wherever it stopped.
        server.dispatcher = RangeFileServer(files)
        val file = runBlocking { downloader.download(layer) }
        assertArrayEquals(data, file.readBytes())
        server.takeRequest() // the cancelled attempt
        assertEquals("bytes=$resumedFrom-", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun `cancellation surfaces as CancellationException even on the last attempt`() {
        files["/base.layer"] = data
        val cache = cache()
        val layer = spec()
        val downloader = downloader(cache, maxAttempts = 1)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("ETag", "\"v1\"")
                    .setBody(Buffer().write(data))
                    .throttleBody(1024, 50, TimeUnit.MILLISECONDS)
        }

        var thrown: Throwable? = null
        runBlocking {
            // See the comment in `cancellation leaves the partial in place for
            // the next attempt`: blocking body reads need their own thread.
            val job = launch(Dispatchers.IO) {
                try {
                    downloader.download(layer)
                } catch (e: CancellationException) {
                    thrown = e
                } catch (e: VmException) {
                    thrown = e
                }
            }
            delay(400)
            job.cancelAndJoin()
        }

        assertTrue("expected CancellationException, got $thrown", thrown is CancellationException)
        assertTrue(cache.partFile(layer.sha256).isFile)
    }

    @Test
    fun `a socket disconnect mid body is a retryable network error`() {
        files["/base.layer"] = data
        val cache = cache()
        val layer = spec()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("ETag", "\"v1\"")
                    .setBody(Buffer().write(data))
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        }

        val exception = assertThrows(VmException::class.java) {
            runBlocking { downloader(cache, maxAttempts = 1).download(layer) }
        }

        assertEquals(VmError.NETWORK_ERROR, exception.error)
        // Truncated transfers keep whatever bytes arrived; the checksum gate at
        // completion (and the final length check) makes them safe to resume from.
        val part = cache.partFile(layer.sha256)
        if (part.isFile) {
            assertTrue(part.length() <= data.size)
        }
    }

    /**
     * A static file server that honors `Range` and reports an `ETag` — the
     * behavior a layer host needs for resumable downloads.
     */
    private class RangeFileServer(
        private val files: Map<String, ByteArray>,
        private val etag: String = "\"v1\"",
    ) : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path?.substringBefore('?') ?: "/"
            val body = files[path] ?: return MockResponse().setResponseCode(404)
            val range = request.getHeader("Range")
            val from = range
                ?.let { RANGE.find(it)?.groupValues?.get(1)?.toLongOrNull() }
                ?: 0L
            return when {
                range != null && from >= body.size -> MockResponse().setResponseCode(416)
                range == null -> MockResponse()
                    .setResponseCode(200)
                    .setHeader("ETag", etag)
                    .setBody(Buffer().write(body))

                else -> MockResponse()
                    .setResponseCode(206)
                    .setHeader("ETag", etag)
                    .setHeader("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
                    .setBody(Buffer().write(body.copyOfRange(from.toInt(), body.size)))
            }
        }

        companion object {
            private val RANGE = Regex("bytes=(\\d+)-")
        }
    }
}
