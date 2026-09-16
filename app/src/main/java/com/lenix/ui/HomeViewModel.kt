package com.lenix.ui

import android.app.Application
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lenix.data.JsonInstanceStore
import com.lenix.data.JsonInstallStateStore
import com.lenix.data.JsonSettingsStore
import com.lenix.data.LenixSettings
import com.lenix.data.SelectionStore
import com.lenix.installer.RootfsCatalog
import com.lenix.installer.RootfsInstaller
import com.lenix.installer.RootfsManifest
import com.lenix.installer.RootfsManifestVerifier
import com.lenix.installer.TrustedKeyRing
import com.lenix.installer.extract.RootfsExtractor
import com.lenix.nativebridge.EngineInstaller
import com.lenix.nativebridge.NativeSetup
import com.lenix.vm.DistroSpec
import com.lenix.vm.VmError
import com.lenix.vm.VmException
import com.lenix.vm.VmInstance
import com.lenix.vm.VmManager
import com.lenix.vm.VmState
import com.lenix.vm.isBusy
import com.lenix.vm.launch.GuestRuntime
import com.lenix.vm.pty.TerminalSnapshot
import com.lenix.vm.service.VmRuntimeService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class HomeUiState(
    val instances: Map<String, VmInstance> = emptyMap(),
    val selectedInstance: VmInstance = VmInstance.DEFAULT,
    val installProgress: InstallProgress = InstallProgress.Inactive,
    val settings: LenixSettings = LenixSettings(),
    val message: String? = null,
    val navigateTo: String? = null,
    val isEngineAvailable: Boolean = true,
) {
    data class InstallProgress(
        val state: VmState? = null,
        val fraction: Float = 0f,
        val message: String = "",
    ) {
        companion object {
            val Inactive = InstallProgress()
        }
    }
}

/**
 * Keeps the monitor UI reactive without turning [MainActivity] into a giant monster.
 *
 * Owns the persisted instance manager (Phase 2) and drives the real RootFS install
 * pipeline (Phases 3–5): the bundled manifest is signature-verified against the signing
 * key embedded in the APK, its layers are downloaded through the resumable downloader,
 * re-hashed, extracted into the instance's staging directory and committed atomically.
 * Every state transition is persisted, so an interrupted install resumes where it
 * stopped. App settings are loaded from / persisted to `filesDir/settings.json` here
 * too — one owner, one file.
 *
 * This is also the only place that touches Android `Context` APIs on the install path
 * (asset loading, `StatFs`): everything it wires up takes plain files and lambdas and is
 * therefore JVM-unit-testable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    application: Application,
    val vmManager: VmManager = VmManager(
        store = JsonInstanceStore(File(application.filesDir, INSTANCE_DIR)),
    ),
    private val selectionStore: SelectionStore = SelectionStore(
        File(application.filesDir, SELECTION_FILE),
    ),
    private val settingsStore: JsonSettingsStore = JsonSettingsStore(
        File(application.filesDir, SETTINGS_FILE),
    ),
    private val manifestVerifier: RootfsManifestVerifier = RootfsManifestVerifier(
        keyRing = bundledTrustedKeys(application),
    ),
    private val installer: RootfsInstaller = RootfsInstaller(
        filesDir = application.filesDir,
        manifestVerifier = manifestVerifier,
        extractor = RootfsExtractor(freeBytes = { freeBytesOn(application.filesDir) }),
    ),
    /**
     * The guest runtime that manages PRoot engine lifecycle and shell sessions.
     * Made internal (not private) so DesktopScreen can access it for VNC connection handling.
     */
    internal val guestRuntime: GuestRuntime = GuestRuntime(
        filesDir = application.filesDir,
        manager = vmManager,
        nativeLibDir = enginePayloadDir(application),
        abi = runtimeAbi(application),
    ),
) : AndroidViewModel(application) {

    private val vmDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** The ABI the engine payload must match (mirrors [runtimeAbi]). */
    private val engineAbi: String = runtimeAbi(getApplication<Application>())

    private var selectedId: String =
        selectionStore.load()?.let { saved -> vmManager.getInstance(saved)?.id }
            ?: vmManager.selectedInstance().id

    private val mutableHomeState = MutableStateFlow(
        HomeUiState(
            instances = vmManager.instances.value,
            selectedInstance = vmManager.getInstance(selectedId) ?: vmManager.selectedInstance(),
            settings = settingsStore.load(),
            isEngineAvailable = guestRuntime.isEngineAvailable(),
        ),
    )

    val uiState: StateFlow<HomeUiState> = mutableHomeState.asStateFlow()

    private var installJob: Job? = null

    /**
     * The terminal window's state. Owned here rather than by the screen: the reader
     * lives as long as the guest session, so navigating away from the terminal neither
     * loses the transcript nor leaves the guest's stdout pipe unread (which would
     * block the shell once 64 KiB of output piled up).
     */
    private val mutableTerminal = MutableStateFlow(TerminalSnapshot.disconnected(TERMINAL_IDLE))

    /** The terminal window's transcript, or why no shell is attached to it. */
    val terminalState: StateFlow<TerminalSnapshot> = mutableTerminal.asStateFlow()

    private var terminalJob: Job? = null

    init {
        viewModelScope.launch {
            vmManager.instances.collect { instances ->
                mutableHomeState.update { current ->
                    val selected = instances[selectedId]
                        ?: instances.values.minByOrNull { it.updatedAt }?.also { fallback ->
                            selectedId = fallback.id
                        }
                        ?: VmInstance.DEFAULT
                    current.copy(
                        instances = instances,
                        selectedInstance = selected,
                        isEngineAvailable = guestRuntime.isEngineAvailable(),
                    )
                }
            }
        }
        seedInterruptedInstall()
    }

    /**
     * Installs (or resumes installing) the RootFS for the selected instance.
     *
     * Runs the whole pipeline (docs/ROOTFS_SYSTEM.md §2): signed manifest → resumable
     * download → re-hash → streaming extraction → atomic commit. A retry after an
     * interruption resumes from the layer cache and any `.part` file instead of restarting
     * the download from zero; extraction always re-runs from scratch, which is cheap and
     * cannot leave a half-extracted tree behind.
     *
     * The manifest is verified here as well as inside the installer, so an untrusted or
     * malformed manifest surfaces as an instance error before any bytes are fetched or
     * any storage is reserved.
     */
    fun install() {
        val id = mutableHomeState.value.selectedInstance.id
        val instance = vmManager.getInstance(id)
        if (instance == null) {
            message("Create an instance first.")
            return
        }
        if (instance.state.isBusy) return
        if (instance.state == VmState.READY) {
            message("Already installed — reset the instance to reinstall.")
            return
        }

        val option = RootfsCatalog.options.firstOrNull {
            it.enabled && it.manifestAsset != null &&
                it.displayName.equals(instance.distro, ignoreCase = true)
        }
        val manifestJson = option?.manifestAsset?.let(::readAsset)
        if (manifestJson == null) {
            message("No RootFS is bundled for ${instance.distro} in this build.")
            return
        }

        val verified = try {
            manifestVerifier.verify(manifestJson)
        } catch (e: VmException) {
            vmManager.markError(id, e.error)
            message(e.message ?: "The RootFS manifest could not be verified.")
            return
        } catch (e: Exception) {
            vmManager.markError(id, VmError.DOWNLOAD_CORRUPTED)
            message("The bundled RootFS manifest is invalid: ${e.message}")
            return
        }
        val manifest = verified.manifest

        if (mutableHomeState.value.settings.smartStorage) {
            val required = requiredBytesFor(manifest)
            val available = availableBytes()
            if (available >= 0 && available < required) {
                message(
                    "Not enough free storage: need about ${formatBytes(required)}, " +
                        "only ${formatBytes(available)} available.",
                )
                return
            }
        }

        installJob?.cancel()
        installJob = viewModelScope.launch(vmDispatcher) {
            try {
                vmManager.beginInstall(id)
                installer.install(id, manifestJson).collect { progress ->
                    onInstallProgress(id, progress)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VmException) {
                vmManager.markError(id, e.error)
                message(e.message ?: "Install failed.")
            } catch (e: Exception) {
                vmManager.markError(id, VmError.UNKNOWN)
                message(e.message ?: "Install failed.")
            }
        }
    }

    /** Stops an in-flight install; verified layers stay cached for the next attempt. */
    fun cancelInstall() {
        val job = installJob ?: return
        installJob = null
        viewModelScope.launch(vmDispatcher) {
            job.cancelAndJoin()
            val id = mutableHomeState.value.selectedInstance.id
            installer.discardPartials()
            installer.discardStaging(id)
            JsonInstallStateStore.forInstance(getApplication<Application>().filesDir, id).clear()
            val instance = vmManager.getInstance(id)
            if (instance != null && instance.state.isBusy) {
                vmManager.markError(id, VmError.INSTALL_INTERRUPTED)
            }
            mutableHomeState.update { state ->
                state.copy(
                    installProgress = HomeUiState.InstallProgress.Inactive,
                    message = "Install cancelled. Completed layers stay cached for the next attempt.",
                )
            }
        }
    }

    /**
     * Re-validates / autofixes the PRoot engine if missing, clearing NATIVE_ENGINE_FAILED.
     *
     * Engines cannot be downloaded at runtime on Android 10+ (see ADR-021): the signed
     * APK payload is the only exec-able location, so this surfaces *why* it is missing
     * and what to drop into `app/src/main/jniLibs/<abi>/`.
     */
    fun autofixEngine() {
        val id = mutableHomeState.value.selectedInstance.id
        viewModelScope.launch(vmDispatcher) {
            mutableHomeState.update { current ->
                current.copy(
                    message = "Checking PRoot engine…",
                    installProgress = HomeUiState.InstallProgress(
                        state = VmState.INSTALLING,
                        fraction = 0.5f,
                        message = "Validating the $engineAbi PRoot engine payload…",
                    ),
                )
            }
            val installed = autoInstallEngineInternal()
            if (installed) {
                val instance = vmManager.getInstance(id)
                if (instance?.state == VmState.ERROR && instance.lastError == VmError.NATIVE_ENGINE_FAILED) {
                    val rootfs = File(getApplication<Application>().filesDir, "instances/$id/rootfs")
                    if (rootfs.isDirectory) {
                        vmManager.markReady(id)
                    } else {
                        vmManager.reset(id)
                    }
                }
                val recovered = vmManager.getInstance(id)?.state == VmState.READY
                mutableHomeState.update { current ->
                    current.copy(
                        isEngineAvailable = true,
                        installProgress = HomeUiState.InstallProgress.Inactive,
                        message = if (recovered) {
                            "PRoot engine detected! Ready to START."
                        } else {
                            "PRoot engine detected. Install the RootFS to continue."
                        },
                    )
                }
            } else {
                vmManager.markError(id, VmError.NATIVE_ENGINE_FAILED)
                val status = EngineInstaller.ensureEngine(
                    filesDir = getApplication<Application>().filesDir,
                    abi = runtimeAbi(getApplication<Application>()),
                    nativeLibDir = enginePayloadDir(),
                )
                mutableHomeState.update { current ->
                    current.copy(
                        isEngineAvailable = false,
                        installProgress = HomeUiState.InstallProgress.Inactive,
                        message = status.reason ?: "PRoot engine for $engineAbi is missing.",
                    )
                }
            }
        }
    }

    private fun autoInstallEngineInternal(): Boolean =
        EngineInstaller.ensureEngine(
            filesDir = getApplication<Application>().filesDir,
            abi = runtimeAbi(getApplication<Application>()),
            nativeLibDir = enginePayloadDir(),
        ).isReady

    private fun enginePayloadDir(): File? = enginePayloadDir(getApplication<Application>())

    fun start() {
        val id = mutableHomeState.value.selectedInstance.id
        if (vmManager.getInstance(id) == null) return
        val desktop = mutableHomeState.value.settings.autoStartDesktop
        val background = mutableHomeState.value.settings.allowBackground
        viewModelScope.launch(vmDispatcher) {
            try {
                if (!guestRuntime.isEngineAvailable()) {
                    mutableHomeState.update { current ->
                        current.copy(message = "PRoot engine missing. Checking signed APK payload…")
                    }
                    val fixed = autoInstallEngineInternal()
                    if (!fixed) {
                        vmManager.markError(id, VmError.NATIVE_ENGINE_FAILED)
                        val status = EngineInstaller.ensureEngine(
                            filesDir = getApplication<Application>().filesDir,
                            abi = runtimeAbi(getApplication<Application>()),
                            nativeLibDir = enginePayloadDir(),
                        )
                        mutableHomeState.update { current ->
                            current.copy(
                                isEngineAvailable = false,
                                message = status.reason
                                    ?: "PRoot engine for $engineAbi is missing. Tap AUTOFIX ENGINE for details.",
                            )
                        }
                        return@launch
                    }
                    mutableHomeState.update { current -> current.copy(isEngineAvailable = true) }
                }

                guestRuntime.start(id, desktop = desktop)
                attachTerminal(id)
                if (background) {
                    withContext(Dispatchers.Main) {
                        VmRuntimeService.start(getApplication())
                    }
                }
                val dest = when {
                    desktop -> Routes.DESKTOP
                    else -> Routes.TERMINAL
                }
                mutableHomeState.update { state ->
                    state.copy(
                        message = if (desktop) {
                            "Openbox session starting — connecting the built-in VNC viewer."
                        } else {
                            "Linux shell is running. Open the terminal to type commands."
                        },
                        navigateTo = dest,
                    )
                }
            } catch (e: VmException) {
                detachTerminal(TERMINAL_IDLE)
                message(e.message ?: "Could not start the Linux environment.")
            } catch (e: Exception) {
                detachTerminal(TERMINAL_IDLE)
                vmManager.markError(id, VmError.NATIVE_ENGINE_FAILED)
                message(e.message ?: "Could not start the Linux environment.")
            }
        }
    }

    fun stop() {
        val id = mutableHomeState.value.selectedInstance.id
        if (vmManager.getInstance(id) == null) return
        viewModelScope.launch(vmDispatcher) {
            try {
                guestRuntime.stop(id)
            } catch (_: Exception) {
                vmManager.markStopped(id)
            }
            // Always detach: a failed stop still means the window has no live shell.
            detachTerminal(TERMINAL_STOPPED)
            withContext(Dispatchers.Main) {
                VmRuntimeService.stop(getApplication())
            }
            mutableHomeState.update { state -> state.copy(message = null) }
        }
    }

    /**
     * Types one line into the running guest shell. Writes happen off the main thread:
     * a guest that is not draining its stdin would otherwise block the caller.
     */
    fun sendToTerminal(line: String) {
        val terminal = guestRuntime.terminal(mutableHomeState.value.selectedInstance.id)
        if (terminal == null) {
            detachTerminal(TERMINAL_IDLE)
            return
        }
        viewModelScope.launch(Dispatchers.IO) { terminal.send(line) }
    }

    fun sendRawToTerminal(text: String) {
        val terminal = guestRuntime.terminal(mutableHomeState.value.selectedInstance.id)
        if (terminal == null) {
            detachTerminal(TERMINAL_IDLE)
            return
        }
        viewModelScope.launch(Dispatchers.IO) { terminal.sendRaw(text) }
    }

    fun sendCtrlCToTerminal() {
        val terminal = guestRuntime.terminal(mutableHomeState.value.selectedInstance.id) ?: return
        viewModelScope.launch(Dispatchers.IO) { terminal.sendCtrlC() }
    }

    fun sendCtrlDToTerminal() {
        val terminal = guestRuntime.terminal(mutableHomeState.value.selectedInstance.id) ?: return
        viewModelScope.launch(Dispatchers.IO) { terminal.sendCtrlD() }
    }

    /** Closes the guest's stdin — the only end-of-input a pipe-backed shell honors. */
    fun sendEofToTerminal() {
        val terminal = guestRuntime.terminal(mutableHomeState.value.selectedInstance.id) ?: return
        viewModelScope.launch(Dispatchers.IO) { terminal.sendEof() }
    }

    /** Clears the terminal window's scrollback; the guest session is untouched. */
    fun clearTerminal() {
        guestRuntime.terminal(mutableHomeState.value.selectedInstance.id)?.clear()
    }

    fun updateTerminalSize(cols: Int, rows: Int) {
        guestRuntime.terminal(mutableHomeState.value.selectedInstance.id)?.updateSize(cols, rows)
    }

    /** Mirrors the running session's terminal into [terminalState] until it is replaced. */
    private fun attachTerminal(id: String) {
        terminalJob?.cancel()
        val terminal = guestRuntime.terminal(id)
        if (terminal == null) {
            mutableTerminal.value = TerminalSnapshot.disconnected(TERMINAL_IDLE)
            return
        }
        terminalJob = viewModelScope.launch {
            terminal.snapshot.collect { snapshot -> mutableTerminal.value = snapshot }
        }
    }

    private fun detachTerminal(notice: String) {
        terminalJob?.cancel()
        terminalJob = null
        mutableTerminal.value = TerminalSnapshot.disconnected(notice)
    }

    fun consumeNavigation() {
        mutableHomeState.update { state -> state.copy(navigateTo = null) }
    }

    fun vncPort(): Int? = guestRuntime.session(mutableHomeState.value.selectedInstance.id)?.vncPort

    fun reset() {
        val id = mutableHomeState.value.selectedInstance.id
        if (vmManager.getInstance(id) == null) return
        viewModelScope.launch(vmDispatcher) {
            vmManager.reset(id)
            installer.discardStaging(id)
            JsonInstallStateStore.forInstance(getApplication<Application>().filesDir, id).clear()
            mutableHomeState.update { state ->
                state.copy(
                    installProgress = HomeUiState.InstallProgress.Inactive,
                    message = "Instance reset.",
                )
            }
        }
    }

    /** Makes [id] the instance shown and controlled on the Home screen. */
    fun selectInstance(id: String) {
        val instance = vmManager.getInstance(id) ?: return
        selectedId = id
        viewModelScope.launch(vmDispatcher) { selectionStore.save(id) }
        mutableHomeState.update { state -> state.copy(selectedInstance = instance) }
    }

    /** Creates a new instance from a catalog entry and selects it. */
    fun createInstance(name: String, distroId: String) {
        viewModelScope.launch(vmDispatcher) {
            val option = RootfsCatalog.options.firstOrNull { it.id == distroId }
            if (option == null || !option.enabled) {
                message("That distro is not available in v0.1.")
                return@launch
            }
            try {
                val created = vmManager.createInstance(
                    name = name,
                    distro = DistroSpec(
                        distro = option.displayName,
                        codename = option.codename,
                        version = option.version,
                        architecture = option.architecture,
                    ),
                )
                selectedId = created.id
                selectionStore.save(created.id)
                mutableHomeState.update { state ->
                    state.copy(selectedInstance = created, message = "Instance '${created.name}' created.")
                }
            } catch (e: IllegalArgumentException) {
                message(e.message ?: "Invalid instance.")
            } catch (e: IllegalStateException) {
                message(e.message ?: "Instance limit reached.")
            }
        }
    }

    fun renameInstance(id: String, newName: String) {
        viewModelScope.launch(vmDispatcher) {
            try {
                vmManager.renameInstance(id, newName)
                message("Instance renamed.")
            } catch (e: IllegalArgumentException) {
                message(e.message ?: "Invalid name.")
            }
        }
    }

    fun deleteInstance(id: String) {
        viewModelScope.launch(vmDispatcher) {
            try {
                vmManager.deleteInstance(id)
                if (selectedId == id) {
                    val next = vmManager.instances.value.values.minByOrNull { it.updatedAt }
                    if (next != null) {
                        selectedId = next.id
                        selectionStore.save(next.id)
                    } else {
                        selectionStore.clear()
                    }
                }
                message("Instance deleted.")
            } catch (e: IllegalStateException) {
                message(e.message ?: "Instance is busy.")
            }
        }
    }

    /**
     * Updates one setting and persists the whole set to `filesDir/settings.json`.
     * The UI state updates optimistically; the disk write is serialized on the
     * manager dispatcher.
     */
    fun updateSettings(transform: (LenixSettings) -> LenixSettings) {
        val updated = transform(mutableHomeState.value.settings)
        mutableHomeState.update { state -> state.copy(settings = updated) }
        viewModelScope.launch(vmDispatcher) { settingsStore.save(updated) }
    }

    /** Disk usage for one instance, for the instance manager list. */
    suspend fun diskUsageBytes(id: String): Long = withContext(vmDispatcher) {
        vmManager.diskUsageBytes(id)
    }

    fun dismissMessage() {
        mutableHomeState.update { state -> state.copy(message = null) }
    }

    /**
     * After a process death mid-install, the instance recovers as
     * ERROR/INSTALL_INTERRUPTED with a persisted checkpoint; surface that progress so the
     * Home screen can offer RESUME at the right percentage.
     *
     * The two phases are honest about different things: an interrupted *download* resumes
     * at the exact byte it stopped at, while an interrupted *extraction* re-runs from
     * scratch on top of the cached layers (ADR-018) — so the label and the bar's position
     * are chosen per phase instead of pretending one progress scale fits both.
     */
    private fun seedInterruptedInstall() {
        val instance = vmManager.getInstance(selectedId) ?: return
        if (instance.state != VmState.ERROR || instance.lastError != VmError.INSTALL_INTERRUPTED) {
            return
        }
        val state = JsonInstallStateStore
            .forInstance(getApplication<Application>().filesDir, selectedId)
            .load() ?: return
        if (state.bytesTotal <= 0L) return
        val phaseFraction = (state.bytesDone.toFloat() / state.bytesTotal).coerceIn(0f, 1f)
        val extracting = state.phase == RootfsInstaller.PHASE_EXTRACTING
        val fraction = if (extracting) {
            EXTRACTING_START + phaseFraction * (EXTRACTING_END - EXTRACTING_START)
        } else {
            phaseFraction * (MANIFEST_FRACTION + DOWNLOAD_SHARE)
        }
        val message = if (extracting) {
            "Interrupted while extracting layer ${state.layerIndex + 1}/${state.layerCount} — " +
                "the verified layers stay cached; RESUME INSTALL re-extracts the RootFS."
        } else {
            "Interrupted at ${(phaseFraction * 100).toInt()}% — " +
                "tap RESUME INSTALL to continue the download where it stopped."
        }
        mutableHomeState.update { current ->
            current.copy(
                installProgress = HomeUiState.InstallProgress(
                    state = if (extracting) VmState.EXTRACTING else VmState.DOWNLOADING,
                    fraction = fraction.coerceIn(0f, 1f),
                    message = message,
                ),
            )
        }
    }

    private fun onInstallProgress(id: String, progress: RootfsInstaller.Progress) {
        when (progress) {
            is RootfsInstaller.Progress.ManifestVerified -> {
                vmManager.markVerifying(id)
                updateProgress(
                    VmState.VERIFYING,
                    MANIFEST_FRACTION,
                    "Manifest signed by ${progress.signer} — ${progress.layerCount} layer(s)",
                )
            }

            is RootfsInstaller.Progress.Download -> updateProgress(
                VmState.DOWNLOADING,
                MANIFEST_FRACTION + fractionOf(progress.bytesDone, progress.bytesTotal) * DOWNLOAD_SHARE,
                "Downloading ${progress.layerId} — ${formatBytes(progress.bytesDone)} / " +
                    "${formatBytes(progress.bytesTotal)}",
            )

            is RootfsInstaller.Progress.Verifying -> {
                vmManager.markVerifying(id)
                updateProgress(VmState.VERIFYING, VERIFYING_FRACTION, "Verifying ${progress.layerId} …")
            }

            is RootfsInstaller.Progress.Extracting -> {
                vmManager.markExtracting(id)
                // Real per-layer progress: entries are written as they stream, so the bar
                // moves through the whole extraction window instead of sticking at a fixed
                // percentage.
                val withinPhase = (progress.layerIndex.toFloat() +
                    fractionOf(progress.layerBytesDone, progress.layerBytesTotal)) /
                    maxOf(progress.layerCount, 1)
                updateProgress(
                    VmState.EXTRACTING,
                    EXTRACTING_START + withinPhase.coerceIn(0f, 1f) * (EXTRACTING_END - EXTRACTING_START),
                    "Extracting ${progress.layerId} ${progress.layerIndex + 1}/${progress.layerCount} — " +
                        "${formatBytes(progress.layerBytesDone)} / " +
                        "${formatBytes(progress.layerBytesTotal)}\n${progress.currentEntry}",
                )
            }

            is RootfsInstaller.Progress.Committing -> {
                vmManager.markInstalling(id)
                updateProgress(VmState.INSTALLING, COMMITTING_FRACTION, progress.detail)
            }

            RootfsInstaller.Progress.Ready -> {
                vmManager.markReady(id)
                mutableHomeState.update { state ->
                    state.copy(
                        installProgress = HomeUiState.InstallProgress(
                            state = VmState.READY,
                            fraction = 1f,
                            message = "Ready",
                        ),
                        message = null,
                    )
                }
            }
        }
    }

    private fun fractionOf(done: Long, total: Long): Float =
        if (total <= 0L) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

    /**
     * Free space an install is expected to need: the compressed layers in the
     * shared cache plus extraction headroom (docs/ROOTFS_SYSTEM.md §4).
     */
    private fun requiredBytesFor(manifest: RootfsManifest): Long {
        val compressed = manifest.layers.sumOf { it.sizeBytes }
        val uncompressed = manifest.layers.sumOf { it.uncompressedBytes }
        return compressed + uncompressed * EXTRACTION_HEADROOM_NUMERATOR /
            EXTRACTION_HEADROOM_DENOMINATOR
    }

    /** Available bytes on the app's internal storage, or -1 when unknowable. */
    private fun availableBytes(): Long = freeBytesOn(getApplication<Application>().filesDir)

    private fun readAsset(path: String): String? = try {
        getApplication<Application>().assets.open(path).bufferedReader().use { it.readText() }
    } catch (e: IOException) {
        null
    }

    private fun updateProgress(state: VmState, fraction: Float, text: String) {
        mutableHomeState.update { current ->
            current.copy(
                installProgress = HomeUiState.InstallProgress(
                    state = state,
                    fraction = fraction.coerceIn(0f, 1f),
                    message = text,
                ),
                message = null,
            )
        }
    }

    private fun message(text: String) {
        mutableHomeState.update { state -> state.copy(message = text) }
    }

    companion object {
        const val INSTANCE_DIR = "instances"
        const val SELECTION_FILE = "selected_instance"
        const val SETTINGS_FILE = "settings.json"

        /**
         * The progress bar's segments: a sliver for the manifest check, the bulk for the
         * download (it dominates wall-clock time), then verifying, extracting (scaled by
         * real unpacked bytes) and the commit.
         */
        const val MANIFEST_FRACTION = 0.02f
        const val DOWNLOAD_SHARE = 0.78f
        const val VERIFYING_FRACTION = 0.85f
        const val EXTRACTING_START = 0.85f
        const val EXTRACTING_END = 0.96f
        const val COMMITTING_FRACTION = 0.98f

        /** Shown by the terminal window while no guest session exists. */
        const val TERMINAL_IDLE =
            "No guest shell is attached.\nPress START on Home so PRoot can spawn /bin/bash, " +
                "then reopen Terminal."

        /** Shown after the guest was stopped on purpose. */
        const val TERMINAL_STOPPED = "Guest stopped. Press START on Home to launch the shell again."

        const val EXTRACTION_HEADROOM_NUMERATOR = 6L
        const val EXTRACTION_HEADROOM_DENOMINATOR = 5L

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1e9)
            bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1e6)
            else -> "$bytes B"
        }
    }
}

/** Available bytes on [dir]'s volume, or -1 when the platform cannot say (never blocks). */
private fun freeBytesOn(dir: File): Long = try {
    StatFs(dir.absolutePath).availableBytes
} catch (e: Exception) {
    -1L
}

/**
 * The ABI the engine payload must match. Android reports the *supported* ABI list in
 * preference order; v0.1 ships arm64-v8a only, but honoring the device keeps the
 * engine check honest on emulators/x86_64.
 */
private fun runtimeAbi(application: Application): String = try {
    android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: NativeSetup.DEFAULT_ABI
} catch (_: Throwable) {
    NativeSetup.DEFAULT_ABI
}

/**
 * The signed APK native payload directory (`ApplicationInfo.nativeLibraryDir`), or null
 * when the installation exposes no native library directory (e.g. a build without any
 * engine payload). The engine resolver handles null, so the caller must never construct
 * `File(null)` — that throws on the main thread before AUTOFIX can run.
 */
private fun enginePayloadDir(application: Application): File? =
    application.applicationInfo.nativeLibraryDir?.let(::File)

/**
 * The Ed25519 keys this build trusts to sign RootFS manifests, read from the APK's
 * assets (docs/ROOTFS_SYSTEM.md §1). An unreadable or missing asset yields an empty ring,
 * which makes every install fail as SIGNATURE_FAILED rather than silently skipping
 * verification.
 */
private fun bundledTrustedKeys(application: Application): TrustedKeyRing = try {
    val text = application.assets
        .open(RootfsCatalog.SIGNING_KEYS_ASSET)
        .bufferedReader()
        .use { it.readText() }
    TrustedKeyRing.parse(text)
} catch (e: Exception) {
    TrustedKeyRing.EMPTY
}
