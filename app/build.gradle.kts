plugins {
    id("com.android.application")
    // Required with AGP 9 built-in Kotlin: AGP does not wire the Compose
    // compiler itself. Version 2.2.10 matches the Kotlin compiler embedded in
    // AGP 9.3.2 (see its POM: kotlin-gradle-plugin 2.2.10).
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}

android {
    namespace = "com.lenix"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lenix"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Engine binaries (libproot.so, libprootloader.so, libtalloc.so…) ship in
        // src/main/jniLibs/<abi>/ so the APK contains them under lib/<abi>/ and the
        // package manager EXTRACTS them to /data/app/.../lib/<abi>/ — the only
        // app-reachable location where SELinux allows execve() on Android 10+ (ADR-021).
        // They are executables named lib*.so precisely so both AGP (which packages only
        // *.so from jniLibs) and the installer (which extracts only lib*.so) keep them.
        // useLegacyPackaging=true forces extraction to disk instead of being loaded
        // straight from the APK — an exec target needs a real file path (ADR-022).
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        // GuestRuntime (and friends) use android.util.Log for diagnostics; in JVM unit
        // tests the android.jar stubs throw "not mocked" unless default return values
        // are enabled.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        warningsAsErrors = false
        disable += setOf(
            "NotificationPermission",
            "ForegroundServiceType",
            "StartForegroundMissingType",
            "NewApi",
            "UnusedAttribute",
            "IconLauncherShape",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.10.0")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // RootFS manifest parsing (JSON)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")

    // Resumable RootFS downloads (HTTP Range resume, ETag/If-Range validation).
    // ARCHITECTURE.md pins OkHttp for the downloader; see docs/DECISIONS.md ADR-015.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // RootFS manifest signatures (Phase 4) need no dependency at all: Ed25519 ships with
    // the platform's JCA from API 29, which is also minSdk here (see ADR-017).
    //
    // Streaming RootFS extraction (Phase 5) is pure Java, so it works before the native
    // engine lands: Commons Compress reads the tar stream (ustar/pax/GNU long names,
    // sparse entries) and XZ for Java decodes the .tar.xz layers that proot-distro and
    // our own builder publish. Both are dependency-light, license-clean (Apache-2.0 and
    // 0BSD) and carry no native code; see ADR-018.
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.12")

    // Local unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

/**
 * Guard against the two ways the engine payload silently disappears from an APK.
 *
 * 1. The payload dir is empty (nobody ran `scripts/fetch-engine.sh`), so the app builds
 *    fine and then fails on-device with NATIVE_ENGINE_FAILED.
 * 2. A file is there but not named `lib*.so`. Packaging it under `lib/<abi>/` is not
 *    enough: for a non-debuggable package Android's installer only extracts entries
 *    whose base name starts with `lib` and ends with `.so`
 *    (frameworks/base, libs/androidfw/ApkParsing.cpp, ValidLibraryPathLastSlash()), so
 *    a `proot` / `loader` / `libtalloc.so.2` payload never reaches nativeLibraryDir.
 *    Debug builds relax that filter, which is exactly why this bug survives testing.
 *
 * Release builds fail hard; debug builds only warn, so contributors can still work on
 * UI without fetching GPL binaries.
 */
val engineAbis = listOf("arm64-v8a")
val engineRequiredFiles: Map<String, List<String>> = mapOf(
    "arm64-v8a" to listOf(
        "libproot.so", "libprootloader.so", "libtalloc.so", "libandroid-shmem.so"
    ),
)

// Plain (abi -> dir) pairs resolved during configuration: the checks below run at
// execution time and must not touch `Project` (configuration-cache safe).
val enginePayloadDirs: List<Pair<String, File>> =
    engineAbis.map { abi -> abi to file("src/main/jniLibs/$abi") }

// Pure lambda (no script/Project capture) so the providers below stay
// configuration-cache safe.
val engineProblemsOf: (List<Pair<String, File>>, Map<String, List<String>>) -> List<String> =
    { dirs, required ->
        dirs.flatMap { (abi, dir) ->
            val files = dir.listFiles()?.filter { it.isFile && it.name != ".gitkeep" }.orEmpty()
            val problems = mutableListOf<String>()
            if (files.isEmpty()) {
                problems += "No PRoot engine payload in app/src/main/jniLibs/$abi/ — " +
                    "run ./scripts/fetch-engine.sh $abi"
            } else {
                files.map { it.name }
                    .filterNot { it.startsWith("lib") && it.endsWith(".so") }
                    .forEach {
                        problems += "app/src/main/jniLibs/$abi/$it is not named lib*.so, so " +
                            "Android will not extract it from the APK — re-run " +
                            "./scripts/fetch-engine.sh $abi"
                    }
                required[abi].orEmpty().forEach { name ->
                    if (files.none { it.name == name }) {
                        problems += "Missing required engine file " +
                            "app/src/main/jniLibs/$abi/$name (the Termux proot build " +
                            "depends on it and the guest will not start without it) — " +
                            "run ./scripts/fetch-engine.sh $abi"
                    }
                }
            }
            problems
        }
    }

/**
 * Fetches the engine payload as part of the build.
 *
 * The binaries are GPL and intentionally untracked, so *something* has to run
 * `scripts/fetch-engine.sh` or the APK ships an empty `lib/<abi>/`. Doing it here rather
 * than only in a CI workflow means a plain `./gradlew assembleDebug` on a fresh clone
 * also produces a working APK. Set `-PskipEngineFetch=true` (or `SKIP_ENGINE_FETCH=1`)
 * to build the UI without pulling binaries; the payload check then only warns.
 */
val fetchEnginePayload by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Downloads the PRoot engine payload into src/main/jniLibs/<abi>/."

    val abi = engineAbis.first()
    val script = rootProject.file("scripts/fetch-engine.sh")
    val payloadDir = file("src/main/jniLibs/$abi")

    // Skip entirely once a correctly named payload exists, so rebuilds need no network.
    // Deliberately `onlyIf` rather than `outputs.dir(payloadDir)`: the payload lives in
    // the source tree, and declaring it as a task output invites Gradle's stale-output
    // cleanup to delete files there.
    // Skip only when the payload is COMPLETE. Checking "any lib*.so present" let a
    // half-staged payload (e.g. libproot.so + loader but no libtalloc/libandroid-shmem,
    // produced by older fetch-engine.sh runs) skip the fetch forever and ship a broken
    // APK. Deliberately `onlyIf` rather than `outputs.dir(payloadDir)`: the payload
    // lives in the source tree, and declaring it as a task output invites Gradle's
    // stale-output cleanup to delete files there.
    onlyIf {
        val present = payloadDir.listFiles()?.map { it.name }?.toSet().orEmpty()
        engineRequiredFiles[abi].orEmpty().any { it !in present }
    }

    commandLine("bash", script.absolutePath, abi)
    // A missing engine must not look like a green build, but an offline dev should still
    // get a usable error from verifyEnginePayload rather than a stack trace here.
    isIgnoreExitValue = true
    doLast {
        val result = executionResult.get()
        if (result.exitValue != 0) {
            // ::warning:: surfaces this in CI annotations, where the raw log is often
            // not reachable.
            logger.warn(
                "::warning::scripts/fetch-engine.sh failed (exit ${result.exitValue}). " +
                    "The APK will have no PRoot engine unless you add it manually."
            )
        } else {
            val staged = payloadDir.list()?.sorted().orEmpty()
            logger.lifecycle("fetch-engine.sh staged: " + staged.joinToString())
        }
    }
}

val skipEngineFetch = providers.gradleProperty("skipEngineFetch").orNull == "true" ||
    providers.environmentVariable("SKIP_ENGINE_FETCH").orNull == "1"

// GitHub Actions (like most CI systems) sets CI=true. The debug build normally only
// *warns* about an incomplete engine payload so offline UI work stays possible; on CI
// it must fail hard instead — otherwise a broken engine pipeline would ship broken
// APKs again. This keeps the hard gate independent of the workflow files, so it is
// active even while the workflow-level steps cannot be updated (the GitHub App
// pushing for this repo has no `workflows` permission).
val isCi = providers.environmentVariable("CI").orNull == "true"

val verifyEnginePayload by tasks.registering {
    group = "verification"
    description = "Fails the build when the PRoot engine payload is missing or misnamed."
    if (!skipEngineFetch) dependsOn(fetchEnginePayload)
    // Read through a provider so the task holds no Project reference at execution time
    // (configuration-cache safe).
    val dirs = enginePayloadDirs
    val required = engineRequiredFiles
    val check = engineProblemsOf
    val problems = provider { check(dirs, required) }
    doLast {
        val found = problems.get()
        if (found.isNotEmpty()) {
            throw org.gradle.api.GradleException(
                "Engine payload check failed:\n  - " + found.joinToString("\n  - ") +
                    "\nThe APK would install without a runnable engine and fail at START " +
                    "with NATIVE_ENGINE_FAILED (see docs/DECISIONS.md ADR-022)."
            )
        }
    }
}

val warnEnginePayload by tasks.registering {
    description = "Warns (without failing) when a debug build has no usable engine payload."
    if (!skipEngineFetch) dependsOn(fetchEnginePayload)
    val dirs = enginePayloadDirs
    val required = engineRequiredFiles
    val check = engineProblemsOf
    val problems = provider { check(dirs, required) }
    doLast { problems.get().forEach { logger.warn("WARNING: $it") } }
}

// The payload must exist before anything *reads* src/main/resources, not merely before
// `assembleDebug` finishes: assemble is a lifecycle task and Gradle is free to run the
// packaging tasks before any other dependency of it. Hooking the java-resource merge
// and packaging tasks directly is what actually gets the engine into the APK.
if (!skipEngineFetch) {
    tasks.matching { task ->
        task.name.contains("JavaRes") || task.name.matches(Regex("package(Debug|Release)"))
    }.configureEach { dependsOn(fetchEnginePayload) }
}

// On CI the same pre-package tasks must pass the hard payload check BEFORE anything
// reads jniLibs: `assembleDebug` is a lifecycle task and Gradle is free to run the
// packaging tasks in parallel with its other dependencies, so hooking only
// `assembleDebug` cannot guarantee the gate fires before packaging reads the payload.
if (isCi) {
    tasks.matching { task ->
        task.name.contains("JavaRes") || task.name.matches(Regex("package(Debug|Release)"))
    }.configureEach { dependsOn(verifyEnginePayload) }
}

// Release must never ship without an engine; debug only warns so UI work stays possible
// offline (and debug builds do extract the historic unprefixed names anyway).
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }
    .configureEach { dependsOn(verifyEnginePayload) }

tasks.matching { it.name == "assembleDebug" }.configureEach {
    if (isCi) dependsOn(verifyEnginePayload) else dependsOn(warnEnginePayload)
}

/**
 * Verifies the *built APK*, not the source tree.
 *
 * The source-tree checks above can pass while the APK still ends up without a usable
 * engine (a packaging rule drops the payload, a file gets renamed, the fetch runs
 * after packaging). `scripts/verify-apk-engine.sh` opens the real archive and asserts
 * `lib/<abi>/` contains an entry Android will actually extract, emitting `::error::`
 * so the reason shows up in CI annotations.
 */
val verifyApkEngineDebug by tasks.registering(Exec::class) {
    group = "verification"
    description = "Asserts the built debug APK carries an extractable engine under lib/<abi>/."

    val abi: String = engineAbis.first()
    val script: File = rootProject.file("scripts/verify-apk-engine.sh")
    val apkDir: File = layout.buildDirectory.dir("outputs/apk/debug").get().asFile

    // Run only once an APK exists AND the source payload was complete. An incomplete
    // source payload is already handled by warnEnginePayload (debug) /
    // verifyEnginePayload (release) — failing again here would break the "debug builds
    // only warn, so UI work stays possible offline" promise. Packaging bugs (files
    // present in the tree but dropped from the APK) still fail here; CI runs the
    // script explicitly after assemble* so an incomplete payload fails the job there.
    onlyIf {
        apkDir.isDirectory &&
            engineRequiredFiles[abi].orEmpty().all { name -> File(file("src/main/jniLibs/$abi"), name).isFile }
    }
    commandLine("bash", script.absolutePath, apkDir.absolutePath, abi)
}

val verifyApkEngineRelease by tasks.registering(Exec::class) {
    group = "verification"
    description = "Asserts the built release APK carries an extractable engine under lib/<abi>/."

    val abi: String = engineAbis.first()
    val script: File = rootProject.file("scripts/verify-apk-engine.sh")
    val apkDir: File = layout.buildDirectory.dir("outputs/apk/release").get().asFile

    // Same completeness guard as the debug task.
    onlyIf {
        apkDir.isDirectory &&
            engineRequiredFiles[abi].orEmpty().all { name -> File(file("src/main/jniLibs/$abi"), name).isFile }
    }
    commandLine("bash", script.absolutePath, apkDir.absolutePath, abi)
}

tasks.matching { it.name == "assembleDebug" }.configureEach { finalizedBy(verifyApkEngineDebug) }
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(verifyApkEngineRelease) }

/**
 * CI reads the console, not the HTML report, so a failing test has to explain itself there:
 * full exception messages and stack traces (one summarized line is not a diagnosis), plus
 * anything a test prints on purpose.
 */
tasks.withType<Test>().configureEach {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
        showStandardStreams = true
    }
}
