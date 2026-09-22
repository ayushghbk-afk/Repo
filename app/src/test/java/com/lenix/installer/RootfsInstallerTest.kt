package com.lenix.installer

import com.lenix.data.JsonInstallStateStore
import com.lenix.data.download.LayerCache
import com.lenix.data.download.ResumableDownloader
import com.lenix.installer.extract.RootfsExtractor
import com.lenix.installer.extract.TarFixtures
import com.lenix.vm.VmError
import com.lenix.vm.VmException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * End-to-end tests for the install pipeline as of Phases 4–5: a *signed* manifest gates
 * everything, layers download resumably and are re-hashed, and a verified layer becomes a
 * real extracted RootFS that is committed atomically (docs/ROOTFS_SYSTEM.md §2).
 *
 * Signatures are real Ed25519 over real archives: the fixtures are genuine `tar.xz` /
 * `tar.gz` layers served by MockWebServer, so this exercises the same code path a device
 * takes when it installs the Debian layer pinned in `assets/`.
 */
class RootfsInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val files = HashMap<String, ByteArray>()

    private val instanceId = "debian-ab12cd"

    /** A test release key: the manifests below are signed with it, like CI signs real ones. */
    private val releaseKey = RootfsManifestSigner.generateKeyPair()
    private val keyRing = TrustedKeyRing(listOf(releaseKey.second))

    // Layer contents, as the archives that carry them.
    private val baseArchive = TarFixtures.tarXz(
        listOf(
            TarFixtures.Entry(name = "./etc/", directory = true, mode = 0b111101101),
            TarFixtures.Entry(name = "./etc/debian_version", bytes = "bookworm/sid\n".toByteArray()),
            TarFixtures.Entry(name = "./bin/", directory = true, mode = 0b111101101),
            TarFixtures.Entry(name = "./bin/sh", bytes = "#!/bin/sh\necho hi\n".toByteArray(), mode = 0b111101101),
            TarFixtures.Entry(name = "./dev/null", type = org.apache.commons.compress.archivers.tar.TarConstants.LF_CHR),
        ),
    )
    private val desktopArchive = TarFixtures.tarGz(
        listOf(
            TarFixtures.Entry(name = "./etc/debian_version", bytes = "bookworm/sid + openbox\n".toByteArray()),
            TarFixtures.Entry(name = "./usr/local/bin/lenix-entry", bytes = "#!/bin/sh\n".toByteArray(), mode = 0b111111111),
            TarFixtures.Entry(name = "./usr/bin/openbox", bytes = "openbox-binary".toByteArray(), mode = 0b111101101),
            TarFixtures.Entry(name = "./usr/bin/openbox-box", symlinkTo = "openbox"),
        ),
    )

    private var extractionFreeBytes: Long = Long.MAX_VALUE

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = RangeFileServer(files)
        server.start()
        files["/base.layer"] = baseArchive
        files["/desktop.layer"] = desktopArchive
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun installer(
        filesDir: File,
        maxAttempts: Int = 3,
        keyRing: TrustedKeyRing = this.keyRing,
        extractionFreeBytes: Long = this.extractionFreeBytes,
    ): RootfsInstaller = RootfsInstaller(
        filesDir = filesDir,
        manifestVerifier = RootfsManifestVerifier(keyRing = keyRing),
        downloader = ResumableDownloader(
            cache = LayerCache(File(filesDir, LayerCache.LAYER_DIR)),
            client = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
            maxAttempts = maxAttempts,
            backoffMillis = { 0 },
        ),
        extractor = RootfsExtractor(freeBytes = { extractionFreeBytes }),
    )

    private fun layerJson(id: String, path: String, bytes: ByteArray, compression: String) = """
        {
          "id": "$id",
          "url": "${server.url(path)}",
          "sizeBytes": ${bytes.size},
          "uncompressedBytes": ${bytes.size * 4},
          "sha256": "${sha256(bytes)}",
          "compression": "$compression"
        }
    """.trimIndent()

    /** Builds — and, unless [unsigned] is set, signs — a manifest for the served layers. */
    private fun manifestJson(
        arch: String = "aarch64",
        baseCompression: String = "xz",
        unsigned: Boolean = false,
    ): String {
        val unsignedDocument = """
        {
          "schemaVersion": 1,
          "id": "debian-bookworm-aarch64",
          "distro": "debian",
          "codename": "bookworm",
          "arch": "$arch",
          "version": "0.1.0",
          "channel": "stable",
          "releasedAt": "2026-08-30T00:00:00Z",
          "layers": [
            ${layerJson("base", "/base.layer", baseArchive, baseCompression)},
            ${layerJson("desktop-openbox", "/desktop.layer", desktopArchive, "gz")}
          ],
          "install": {
            "estimatedFreeGb": 0.5,
            "bootCommand": "/bin/bash"
          },
          "signature": "unsigned:placeholder"
        }
        """.trimIndent()
        return if (unsigned) {
            unsignedDocument
        } else {
            RootfsManifestSigner.sign(unsignedDocument, releaseKey.first, releaseKey.second.keyId)
        }
    }

    private fun instanceDir(filesDir: File) = File(File(filesDir, "instances"), instanceId)

    private fun runInstall(filesDir: File, manifestJson: String = manifestJson()) = runBlocking {
        installer(filesDir).install(instanceId, manifestJson).toList()
    }
    @Test
    fun `installs every layer, extracts them into the instance and commits atomically`() {
        val filesDir = tmp.newFolder("files")

        val progress = runInstall(filesDir)

        assertTrue(progress.any { it is RootfsInstaller.Progress.ManifestVerified })
        assertTrue(progress.any { it is RootfsInstaller.Progress.Download })
        assertTrue(progress.any { it is RootfsInstaller.Progress.Verifying })
        assertTrue(progress.any { it is RootfsInstaller.Progress.Extracting })
        assertTrue(progress.any { it is RootfsInstaller.Progress.Committing })
        assertEquals(RootfsInstaller.Progress.Ready, progress.last())

        val verified = progress.filterIsInstance<RootfsInstaller.Progress.ManifestVerified>().single()
        assertEquals(releaseKey.second.keyIdHex, verified.keyIdHex)
        assertEquals(2, verified.layerCount)

        val rootfs = File(instanceDir(filesDir), "rootfs")
        // The base layer's files are really there, with their permissions.
        assertTrue(File(rootfs, "bin/sh").canExecute())
        assertTrue(File(rootfs, "etc/debian_version").isFile)
        assertFalse(
            "etc/debian_version permissions: " +
                java.nio.file.Files.getPosixFilePermissions(File(rootfs, "etc/debian_version").toPath()),
            File(rootfs, "etc/debian_version").canExecute(),
        )
        // The desktop layer overlaid the base file and brought its own symlink.
        assertEquals("bookworm/sid + openbox\n", readOverlayed(rootfs))
        assertTrue(File(rootfs, "usr/local/bin/lenix-entry").canExecute())
        assertTrue(java.nio.file.Files.isSymbolicLink(File(rootfs, "usr/bin/openbox-box").toPath()))
        // The character device from the base layer is skipped, not fatal.
        assertFalse(File(rootfs, "dev/null").exists())

        // No staging leftovers, no checkpoint: the commit cleared both.
        assertFalse(File(instanceDir(filesDir), ".tmp").exists())
        assertFalse(File(instanceDir(filesDir), "state.json").exists())

        // Both layers live in the shared content-addressed cache.
        val cache = LayerCache(File(filesDir, LayerCache.LAYER_DIR))
        assertTrue(cache.cachedFile(sha256(baseArchive)).isFile)
        assertTrue(cache.cachedFile(sha256(desktopArchive)).isFile)

        // The instance records what it was built from, including who signed it.
        val provenance = File(instanceDir(filesDir), "rootfs.json").readText()
        assertTrue(provenance.contains(releaseKey.second.keyIdHex))
        assertTrue(provenance.contains(sha256(baseArchive)))
        assertTrue(Regex("\"bootCommand\"\\s*:\\s*\"/bin/bash\"").containsMatchIn(provenance))
        assertTrue(
            "the skipped /dev/null must be recorded, not silently dropped",
            Regex("\"skippedSpecialEntries\"\\s*:\\s*1").containsMatchIn(provenance),
        )
        assertTrue(Regex("\"entries\"\\s*:\\s*[1-9]").containsMatchIn(provenance))
    }

    private fun readOverlayed(rootfs: File): String = File(rootfs, "etc/debian_version").readText()

    @Test
    fun `a proot-distro wrapped layer is unwrapped so the instance can boot`() {
        // proot-distro archives the whole filesystem under the build directory's name
        // (`tar -C $WORKDIR debian-aarch64`), so every member is prefixed with it. Without
        // the unwrap step the shell lands at debian-aarch64/bin/sh and the instance reports
        // "empty or missing a shell" instead of booting.
        val wrapped = TarFixtures.tarXz(
            listOf(
                TarFixtures.Entry(name = "debian-aarch64/", directory = true),
                TarFixtures.Entry(name = "debian-aarch64/etc/debian_version", bytes = "bookworm\n".toByteArray()),
                TarFixtures.Entry(name = "debian-aarch64/usr/bin/dash", bytes = "dash".toByteArray(), mode = 0b111101101),
                // Real proot-distro symlinks are absolute, so they stay valid once the
                // wrapper directory is lifted up to the rootfs root.
                TarFixtures.Entry(name = "debian-aarch64/bin/sh", symlinkTo = "/usr/bin/dash"),
            ),
        )
        files["/wrapped.layer"] = wrapped
        val manifest = RootfsManifestSigner.sign(
            """
            {
              "schemaVersion": 1,
              "id": "debian-bookworm-aarch64",
              "distro": "debian",
              "codename": "bookworm",
              "arch": "aarch64",
              "version": "0.1.0",
              "channel": "stable",
              "releasedAt": "2026-08-30T00:00:00Z",
              "layers": [
                ${layerJson("base", "/wrapped.layer", wrapped, "xz")}
              ],
              "install": { "estimatedFreeGb": 0.3, "bootCommand": "/bin/bash" },
              "signature": "unsigned:placeholder"
            }
            """.trimIndent(),
            releaseKey.first,
            releaseKey.second.keyId,
        )
        val filesDir = tmp.newFolder("files")

        val progress = runBlocking { installer(filesDir).install(instanceId, manifest).toList() }
        assertEquals(RootfsInstaller.Progress.Ready, progress.last())

        val rootfs = File(instanceDir(filesDir), "rootfs")
        // The wrapper directory is gone and the guest filesystem sits at the root, where the
        // launch-time shell check (GuestEngine) expects it.
        assertFalse(File(rootfs, "debian-aarch64").exists())
        assertTrue(File(rootfs, "etc/debian_version").isFile)
        assertTrue(File(rootfs, "bin/sh").exists())
        assertTrue(File(rootfs, "usr/bin/dash").canExecute())
        assertTrue(java.nio.file.Files.isSymbolicLink(File(rootfs, "bin/sh").toPath()))
    }

    @Test
    fun `a retry reuses cached layers and resumes the interrupted one in place`() {
        val filesDir = tmp.newFolder("files")

        // Simulate the aftermath of a killed install: base fully cached, the desktop
        // layer interrupted mid-download.
        val cache = LayerCache(File(filesDir, LayerCache.LAYER_DIR))
        cache.cachedFile(sha256(baseArchive)).apply { parentFile?.mkdirs() }?.writeBytes(baseArchive)
        // Interrupt the desktop layer one byte short of the end: fixtures are small, so the
        // resume point is derived from the archive rather than a fixed offset.
        val resumeAt = minOf(2_000L, desktopArchive.size.toLong() - 1L)
        cache.partFile(sha256(desktopArchive)).writeBytes(desktopArchive.copyOfRange(0, resumeAt.toInt()))

        val progress = runInstall(filesDir)

        assertEquals(RootfsInstaller.Progress.Ready, progress.last())
        // Only the desktop layer hit the network, resuming at the saved offset.
        assertEquals(1, server.requestCount)
        assertEquals("bytes=$resumeAt-", server.takeRequest().getHeader("Range"))

        // The first progress event already reflects the cached base layer.
        val first = progress.filterIsInstance<RootfsInstaller.Progress.Download>().first()
        assertEquals(baseArchive.size.toLong(), first.bytesDone)
        assertTrue(File(instanceDir(filesDir), "rootfs/bin/sh").isFile)
    }

    @Test
    fun `a network failure leaves a retryable checkpoint and a resumable partial`() {
        files.remove("/desktop.layer")
        val filesDir = tmp.newFolder("files")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: "/"
                if (path == "/desktop.layer") {
                    return MockResponse()
                        .setResponseCode(200)
                        .setHeader("ETag", "\"v1\"")
                        .setBody(Buffer().write(desktopArchive))
                        // One byte every 500ms: the first lands, the next never arrives
                        // inside the client's read timeout.
                        .throttleBody(1, 500, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
                return RangeFileServer(files).dispatch(request)
            }
        }
        val failingInstaller = RootfsInstaller(
            filesDir = filesDir,
            manifestVerifier = RootfsManifestVerifier(keyRing = keyRing),
            downloader = ResumableDownloader(
                cache = LayerCache(File(filesDir, LayerCache.LAYER_DIR)),
                client = OkHttpClient.Builder()
                    .retryOnConnectionFailure(false)
                    .readTimeout(300, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build(),
                maxAttempts = 1,
                backoffMillis = { 0 },
            ),
        )

        val exception = assertThrows(VmException::class.java) {
            runBlocking { failingInstaller.install(instanceId, manifestJson()).toList() }
        }
        assertEquals(VmError.NETWORK_ERROR, exception.error)

        // The checkpoint records exactly where the install died…
        val state = JsonInstallStateStore.forInstance(filesDir, instanceId).load()
        assertNotNull(state)
        assertEquals("DOWNLOADING", state?.phase)
        assertEquals(1, state?.layerIndex)
        assertEquals(2, state?.layerCount)
        // …and nothing half-built was committed.
        assertFalse(File(instanceDir(filesDir), "rootfs").exists())
        assertFalse(File(instanceDir(filesDir), ".tmp").exists())
        assertTrue(LayerCache(File(filesDir, LayerCache.LAYER_DIR)).cachedFile(sha256(baseArchive)).isFile)

        // Retry against a healthy server: base is never downloaded again.
        files["/desktop.layer"] = desktopArchive
        server.dispatcher = RangeFileServer(files)
        val progress = runInstall(filesDir)
        assertEquals(RootfsInstaller.Progress.Ready, progress.last())
        assertEquals(3, server.requestCount)
        assertEquals("/base.layer", server.takeRequest().path)
        assertEquals("/desktop.layer", server.takeRequest().path)
        assertEquals("/desktop.layer", server.takeRequest().path)
    }

    @Test
    fun `an invalid manifest fails as DOWNLOAD_CORRUPTED without touching the network`() {
        val filesDir = tmp.newFolder("files")

        val exception = assertThrows(VmException::class.java) {
            runBlocking { installer(filesDir).install(instanceId, "{}").toList() }
        }

        assertEquals(VmError.DOWNLOAD_CORRUPTED, exception.error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an unsupported architecture is rejected before any download`() {
        val filesDir = tmp.newFolder("files")

        val exception = assertThrows(VmException::class.java) {
            runBlocking {
                installer(filesDir).install(instanceId, manifestJson(arch = "x86_64")).toList()
            }
        }

        assertEquals(VmError.UNSUPPORTED_ARCHITECTURE, exception.error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an unsigned manifest is rejected before a single byte is downloaded`() {
        val filesDir = tmp.newFolder("files")

        val exception = assertThrows(VmException::class.java) {
            runBlocking {
                installer(filesDir).install(instanceId, manifestJson(unsigned = true)).toList()
            }
        }

        assertEquals(VmError.SIGNATURE_FAILED, exception.error)
        assertEquals(0, server.requestCount)
        assertFalse(instanceDir(filesDir).exists())
    }

    @Test
    fun `a manifest edited after signing is refused`() {
        val signed = manifestJson()
        // Same trick an attacker has to use on a release host: point a layer at other
        // bytes and patch the digest. The signature covers the digest, so this fails.
        val patched = signed.replace(sha256(baseArchive), sha256(desktopArchive))
        assertTrue(patched != signed)
        val filesDir = tmp.newFolder("files")

        val exception = assertThrows(VmException::class.java) {
            runBlocking { installer(filesDir).install(instanceId, patched).toList() }
        }

        assertEquals(VmError.SIGNATURE_FAILED, exception.error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a manifest signed by a key this build does not trust is refused`() {
        val stranger = RootfsManifestSigner.generateKeyPair()
        val signedByStranger = RootfsManifestSigner.sign(
            manifestJson(unsigned = true),
            stranger.first,
            stranger.second.keyId,
        )
        val filesDir = tmp.newFolder("files")

        val exception = assertThrows(VmException::class.java) {
            runBlocking { installer(filesDir).install(instanceId, signedByStranger).toList() }
        }

        assertEquals(VmError.SIGNATURE_FAILED, exception.error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `tampered layer bytes fail the checksum gate and stage nothing`() {
        files["/base.layer"] = ByteArray(baseArchive.size) { (it % 199).toByte() }
        val filesDir = tmp.newFolder("files")

        val exception = assertThrows(VmException::class.java) {
            runBlocking { installer(filesDir, maxAttempts = 2).install(instanceId, manifestJson()).toList() }
        }

        assertEquals(VmError.CHECKSUM_FAILED, exception.error)
        val cache = LayerCache(File(filesDir, LayerCache.LAYER_DIR))
        assertFalse(cache.cachedFile(sha256(baseArchive)).exists())
        assertFalse(cache.partFile(sha256(baseArchive)).exists())
        assertFalse(File(instanceDir(filesDir), "rootfs").exists())
        assertFalse(File(instanceDir(filesDir), ".tmp").exists())
    }

    @Test
    fun `running out of space while extracting keeps the cache and clears the staging tree`() {
        val filesDir = tmp.newFolder("files")
        extractionFreeBytes = 1_024 // the guard trips before the first write

        val exception = assertThrows(VmException::class.java) {
            runBlocking {
                installer(filesDir, extractionFreeBytes = extractionFreeBytes)
                    .install(instanceId, manifestJson())
                    .toList()
            }
        }
        assertEquals(VmError.INSUFFICIENT_STORAGE, exception.error)

        // The checkpoint says where it stopped; the partial tree and the cache survive
        // exactly as documented (docs/ROOTFS_SYSTEM.md §2 failure semantics).
        val state = JsonInstallStateStore.forInstance(filesDir, instanceId).load()
        assertEquals(RootfsInstaller.PHASE_EXTRACTING, state?.phase)
        assertFalse(File(instanceDir(filesDir), ".tmp").exists())
        assertFalse(File(instanceDir(filesDir), "rootfs").exists())
        assertTrue(LayerCache(File(filesDir, LayerCache.LAYER_DIR)).cachedFile(sha256(baseArchive)).isFile)

        // Retry with room to spare: no re-download, and this time the files land.
        extractionFreeBytes = Long.MAX_VALUE
        assertEquals(RootfsInstaller.Progress.Ready, runInstall(filesDir).last())
        assertTrue(File(instanceDir(filesDir), "rootfs/bin/sh").isFile)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a zstd layer is verified and then refused as unreadable, committing nothing`() {
        val filesDir = tmp.newFolder("files")

        val exception = assertThrows(VmException::class.java) {
            runBlocking {
                installer(filesDir)
                    .install(instanceId, manifestJson(baseCompression = "zstd"))
                    .toList()
            }
        }

        assertEquals(VmError.UNSUPPORTED_COMPRESSION, exception.error)
        // Both layers were fetched and hashed first — the refusal is about the *format*,
        // not about the bytes — and nothing was committed.
        assertEquals(2, server.requestCount)
        assertFalse(File(instanceDir(filesDir), "rootfs").exists())
        assertFalse(File(instanceDir(filesDir), ".tmp").exists())
    }

    /** A static file server that honors `Range` and reports an `ETag`. */
    private class RangeFileServer(private val files: Map<String, ByteArray>) : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path?.substringBefore('?') ?: "/"
            val body = files[path] ?: return MockResponse().setResponseCode(404)
            val range = request.getHeader("Range")
            val from = range?.let { RANGE.find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
            return when {
                range != null && from >= body.size -> MockResponse().setResponseCode(416)
                range == null -> MockResponse()
                    .setResponseCode(200)
                    .setHeader("ETag", ETAG)
                    .setBody(Buffer().write(body))

                else -> MockResponse()
                    .setResponseCode(206)
                    .setHeader("ETag", ETAG)
                    .setHeader("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
                    .setBody(Buffer().write(body.copyOfRange(from.toInt(), body.size)))
            }
        }

        private companion object {
            const val ETAG = "\"v1\""
            val RANGE = Regex("bytes=(\\d+)-")
        }
    }
}
