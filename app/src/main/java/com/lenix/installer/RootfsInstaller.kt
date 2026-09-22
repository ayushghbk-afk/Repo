package com.lenix.installer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lenix.data.InstallState
import com.lenix.data.JsonInstallStateStore
import com.lenix.data.download.LayerCache
import com.lenix.data.download.LayerSpec
import com.lenix.data.download.ResumableDownloader
import com.lenix.installer.extract.ExtractionReport
import com.lenix.installer.extract.LayerCompression
import com.lenix.installer.extract.RootfsExtractor
import com.lenix.util.Digests
import com.lenix.vm.VmError
import com.lenix.vm.VmException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * One layer that was actually materialized into an instance's RootFS, and the digest it
 * was verified against before extraction (`instances/<id>/rootfs.json`).
 */
data class InstalledLayer(
    val id: String,
    val sha256: String,
    val sizeBytes: Long,
    val uncompressedBytes: Long,
    val entries: Int,
    val skippedSpecialEntries: Int,
)

/**
 * Provenance snapshot of the RootFS an instance was installed from
 * (`instances/<id>/rootfs.json`). This is *what came from the network and how it was
 * checked*; the instance record itself is owned by `data.JsonInstanceStore` as
 * `config.json`.
 *
 * [signedByKeyIdHex] and [manifestSha256] are what make the installed tree auditable
 * later ("this rootfs was extracted from layers pinned by manifest X, signed by key Y"),
 * which is the point of carrying them next to the filesystem.
 */
data class RootfsProvenance(
    val rootfsId: String,
    val distro: String,
    val codename: String,
    val arch: String,
    val version: String,
    val bootCommand: String,
    val signedByKeyIdHex: String? = null,
    val manifestSha256: String? = null,
    val verifiedAt: Long = 0,
    val layers: List<InstalledLayer> = emptyList(),
)

/**
 * The RootFS install pipeline (docs/ROOTFS_SYSTEM.md §2), Phases 3–5:
 *
 *  1. parse + hard-validate the manifest and **verify its Ed25519 signature** against the
 *     build's trusted key ring — nothing else in this class trusts a URL, a size or a
 *     digest before that gate returns (ADR-017);
 *  2. DOWNLOADING — fetch every layer through the resumable, content-addressed
 *     [LayerCache] (an interrupted install resumes at the exact byte it stopped);
 *  3. VERIFYING — re-hash every cached layer (and re-check its size) against the manifest;
 *  4. EXTRACTING — stream each layer into `instances/<id>/.tmp/rootfs/` with
 *     [RootfsExtractor]: symlinks/permissions preserved, escaping paths, setuid bits and
 *     device nodes rejected or skipped, expansion and free-space guarded (ADR-018);
 *  5. COMMITTING — atomically rename the staged tree to `rootfs/` and write the
 *     `rootfs.json` provenance record.
 *
 * Failure semantics: a checkpoint in `instances/<id>/state.json` is written at every phase
 * change and at least every [STATE_SAVE_INTERVAL_MS] while bytes move; a failed or
 * cancelled attempt always leaves the staging directory gone (so extraction restarts
 * clean) and the layer cache intact (so the download does not).
 *
 * Takes a plain [filesDir] (not a `Context`) so the whole pipeline is JVM unit testable;
 * see ADR-014/015/018.
 */
class RootfsInstaller(
    private val filesDir: File,
    private val manifestVerifier: RootfsManifestVerifier = RootfsManifestVerifier(),
    private val verifier: RootfsVerifier = RootfsVerifier(),
    private val downloader: ResumableDownloader = ResumableDownloader(
        cache = LayerCache(File(filesDir, LayerCache.LAYER_DIR)),
    ),
    private val extractor: RootfsExtractor = RootfsExtractor(),
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Pipeline events, already aggregated enough for a progress bar to consume directly. */
    sealed class Progress {
        /** The manifest's signature checked out against a trusted key. */
        data class ManifestVerified(val keyIdHex: String, val signer: String, val layerCount: Int) :
            Progress()

        /** Aggregate download progress across all layers of the manifest. */
        data class Download(val layerId: String, val bytesDone: Long, val bytesTotal: Long) :
            Progress()

        /** A cached layer is being re-hashed against the manifest. */
        data class Verifying(val layerId: String) : Progress()

        /** A verified layer is being expanded into the instance's staging rootfs. */
        data class Extracting(
            val layerId: String,
            val currentEntry: String,
            val layerBytesDone: Long,
            val layerBytesTotal: Long,
            val layerIndex: Int,
            val layerCount: Int,
        ) : Progress()

        /** The staged rootfs is being committed as the instance's `rootfs/`. */
        data class Committing(val detail: String) : Progress()

        data object Ready : Progress()
    }

    /**
     * Runs the whole pipeline for [id]. Collect the returned flow to drive an install;
     * cancelling the collector stops it at the next chunk or entry, leaving the download
     * resume point behind and the staging tree discarded.
     */
    fun install(id: String, manifestJson: String): Flow<Progress> = flow {
        // [1] TRUST: schema, then the manifest signature. Nothing below may run on an
        // unverified manifest — the digests that gate every byte are only as good as it.
        val verified = manifestVerifier.verify(manifestJson)
        val manifest = verified.manifest
        RootfsCatalog.requireSupportedArchitecture(manifest.arch)
        emit(
            Progress.ManifestVerified(
                keyIdHex = verified.keyIdHex,
                signer = verified.signer,
                layerCount = manifest.layers.size,
            ),
        )

        val instanceDir = File(File(filesDir, JsonInstallStateStore.INSTANCE_DIR), id)
        if (!instanceDir.exists() && !instanceDir.mkdirs()) {
            throw VmException(VmError.ROOTFS_EXTRACTION_FAILED, "Could not create $instanceDir.")
        }
        val stateStore = JsonInstallStateStore(File(instanceDir, JsonInstallStateStore.STATE_FILE))
        val stagingDir = File(instanceDir, STAGING_DIR)
        val stagedRootfs = File(stagingDir, ROOTFS_DIR)
        // A previous attempt's half-written tree is never reused (ADR-018): extraction is
        // idempotent from scratch, the *download* is the resumable half.
        stagingDir.deleteRecursively()
        if (!stagedRootfs.mkdirs()) {
            throw VmException(VmError.ROOTFS_EXTRACTION_FAILED, "Could not create $stagedRootfs.")
        }

        val layers = manifest.layers
        try {
            val bytesTotal = layers.sumOf { it.sizeBytes }
            var bytesDone = 0L

            // [2] DOWNLOADING — resumable, content-addressed.
            layers.forEachIndexed { index, layer ->
                var lastStateSave = clock()
                stateStore.save(
                    InstallState(
                        instanceId = id,
                        phase = PHASE_DOWNLOADING,
                        layerIndex = index,
                        layerCount = layers.size,
                        bytesDone = bytesDone,
                        bytesTotal = bytesTotal,
                        updatedAt = lastStateSave,
                    ),
                )
                val file = downloader.download(
                    LayerSpec(url = layer.url, sizeBytes = layer.sizeBytes, sha256 = layer.sha256),
                ) { layerDone, _ ->
                    emit(Progress.Download(layer.id, bytesDone + layerDone, bytesTotal))
                    val now = clock()
                    if (now - lastStateSave >= STATE_SAVE_INTERVAL_MS) {
                        lastStateSave = now
                        stateStore.save(
                            InstallState(
                                instanceId = id,
                                phase = PHASE_DOWNLOADING,
                                layerIndex = index,
                                layerCount = layers.size,
                                bytesDone = bytesDone + layerDone,
                                bytesTotal = bytesTotal,
                                updatedAt = now,
                            ),
                        )
                    }
                }
                bytesDone += file.length()
            }

            // [3] VERIFYING — re-hash every cached layer against the signed manifest.
            stateStore.save(
                InstallState(
                    instanceId = id,
                    phase = PHASE_VERIFYING,
                    layerCount = layers.size,
                    bytesDone = bytesDone,
                    bytesTotal = bytesTotal,
                    updatedAt = clock(),
                ),
            )
            layers.forEach { layer ->
                emit(Progress.Verifying(layer.id))
                verifier.verifyLayer(downloader.cache.cachedFile(layer.sha256), layer)
            }

            // [4] EXTRACTING — streaming, hardened, layer by layer in manifest order.
            val uncompressedTotal = layers.sumOf { it.uncompressedBytes }
            stateStore.save(
                InstallState(
                    instanceId = id,
                    phase = PHASE_EXTRACTING,
                    layerCount = layers.size,
                    bytesDone = 0,
                    bytesTotal = uncompressedTotal,
                    updatedAt = clock(),
                ),
            )
            val installed = ArrayList<InstalledLayer>(layers.size)
            var extractedSoFar = 0L
            layers.forEachIndexed { index, layer ->
                val compression = LayerCompression.resolve(layer.compression, layer.url, layer.id)
                var lastStateSave = clock()
                val report = extractor.extract(
                    archive = downloader.cache.cachedFile(layer.sha256),
                    compression = compression,
                    into = stagedRootfs,
                    layerId = layer.id,
                    expectedUncompressedBytes = layer.uncompressedBytes,
                ) { progress ->
                    emit(
                        Progress.Extracting(
                            layerId = layer.id,
                            currentEntry = progress.currentEntry,
                            layerBytesDone = progress.bytesDone,
                            layerBytesTotal = maxOf(progress.bytesTotal, progress.bytesDone),
                            layerIndex = index,
                            layerCount = layers.size,
                        ),
                    )
                    val now = clock()
                    if (now - lastStateSave >= STATE_SAVE_INTERVAL_MS) {
                        lastStateSave = now
                        stateStore.save(
                            InstallState(
                                instanceId = id,
                                phase = PHASE_EXTRACTING,
                                layerIndex = index,
                                layerCount = layers.size,
                                bytesDone = extractedSoFar + progress.bytesDone,
                                bytesTotal = uncompressedTotal,
                                updatedAt = now,
                            ),
                        )
                    }
                }
                extractedSoFar += report.bytesWritten
                installed += InstalledLayer(
                    id = layer.id,
                    sha256 = layer.sha256,
                    sizeBytes = layer.sizeBytes,
                    uncompressedBytes = report.bytesWritten,
                    entries = report.entries,
                    skippedSpecialEntries = report.skippedSpecial,
                )
                stateStore.save(
                    InstallState(
                        instanceId = id,
                        phase = PHASE_EXTRACTING,
                        layerIndex = index,
                        layerCount = layers.size,
                        bytesDone = extractedSoFar,
                        bytesTotal = uncompressedTotal,
                        updatedAt = clock(),
                    ),
                )
            }

            // [5] COMMITTING — atomic rename; either a complete rootfs or nothing.
            stateStore.save(
                InstallState(
                    instanceId = id,
                    phase = PHASE_COMMITTING,
                    layerCount = layers.size,
                    bytesDone = extractedSoFar,
                    bytesTotal = uncompressedTotal,
                    updatedAt = clock(),
                ),
            )
            emit(Progress.Committing("Committing RootFS"))
            val rootfsDir = File(instanceDir, ROOTFS_DIR)
            rootfsDir.deleteRecursively()
            if (!stagedRootfs.renameTo(rootfsDir)) {
                throw VmException(
                    VmError.ROOTFS_EXTRACTION_FAILED,
                    "Could not commit the staged RootFS to $rootfsDir.",
                )
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                File(instanceDir, PROVENANCE_FILE),
                RootfsProvenance(
                    rootfsId = manifest.id,
                    distro = manifest.distro,
                    codename = manifest.codename,
                    arch = manifest.arch,
                    version = manifest.version,
                    bootCommand = manifest.install.bootCommand,
                    signedByKeyIdHex = verified.keyIdHex,
                    manifestSha256 = Digests.sha256Hex(
                        RootfsManifestCanonicalizer.canonicalBytes(manifestJson),
                    ),
                    verifiedAt = clock(),
                    layers = installed,
                ),
            )
            stateStore.clear()
            stagingDir.deleteRecursively()
            emit(Progress.Ready)
        } catch (e: Throwable) {
            // Whatever failed or cancelled, an incomplete tree must not linger: the next
            // attempt re-extracts from the (still cached, still verified) layers.
            stagingDir.deleteRecursively()
            throw e
        }
    }.flowOn(Dispatchers.IO)

    /** Drops in-flight partial downloads (explicit user cancel). */
    fun discardPartials() = downloader.discardPartials()

    /** Removes an instance's incomplete staged tree (user cancel or reset). */
    fun discardStaging(id: String) {
        File(File(File(filesDir, JsonInstallStateStore.INSTANCE_DIR), id), STAGING_DIR)
            .deleteRecursively()
    }

    /** Bytes currently staged for [id] — the size the installer is growing. */
    fun stagedBytes(id: String): Long {
        val staged = File(File(File(filesDir, JsonInstallStateStore.INSTANCE_DIR), id), STAGING_DIR)
        return if (staged.exists()) staged.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else 0L
    }

    companion object {
        const val STAGING_DIR = ".tmp"
        const val ROOTFS_DIR = "rootfs"
        const val PROVENANCE_FILE = "rootfs.json"
        const val STATE_SAVE_INTERVAL_MS = 5_000L

        const val PHASE_DOWNLOADING = "DOWNLOADING"
        const val PHASE_VERIFYING = "VERIFYING"
        const val PHASE_EXTRACTING = "EXTRACTING"
        const val PHASE_COMMITTING = "COMMITTING"
    }
}
