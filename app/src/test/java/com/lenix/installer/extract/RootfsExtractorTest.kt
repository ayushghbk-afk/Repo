package com.lenix.installer.extract

import com.lenix.installer.extract.TarFixtures.Entry
import com.lenix.vm.VmError
import com.lenix.vm.VmException
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * Phase 5 extraction tests: a verified layer must become a *real* guest filesystem —
 * files, directories, symlinks and hard links with their permissions — while everything
 * a hostile archive can try (escaping paths, symlinked parents, decompression bombs,
 * device nodes) is refused before it touches storage.
 */
class RootfsExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val extractor = RootfsExtractor()

    private fun staging(): File = tmp.newFolder()

    private fun extract(
        archive: ByteArray,
        compression: LayerCompression = LayerCompression.NONE,
        into: File = staging(),
        expectedBytes: Long = 0L,
        extractor: RootfsExtractor = this.extractor,
        onProgress: (ExtractionProgress) -> Unit = {},
    ): Pair<File, ExtractionReport> {
        val file = TarFixtures.archiveInto(tmp.newFile(), archive)
        val report = runBlocking {
            extractor.extract(
                archive = file,
                compression = compression,
                into = into,
                layerId = "base",
                expectedUncompressedBytes = expectedBytes,
                onProgress = { progress -> onProgress(progress) },
            )
        }
        return into to report
    }

    @Test
    fun `extracts files, directories and symlinks with their permissions`() {
        val archive = TarFixtures.tar(
            listOf(
                Entry(name = "./etc/", directory = true, mode = 0b111101101),
                Entry(name = "./etc/hostname", bytes = "lenix\n".toByteArray()),
                Entry(name = "./bin/sh", bytes = "#!/bin/sh\n".toByteArray(), mode = 0b111101101),
                Entry(name = "./bin/sh-link", symlinkTo = "sh"),
            ),
        )

        val (root, report) = extract(archive)

        assertEquals("lenix\n", TarFixtures.readText(File(File(root, "etc"), "hostname")))
        assertTrue(File(root, "bin/sh").canExecute())
        assertFalse(
            "etc/hostname permissions: ${Files.getPosixFilePermissions(File(root, "etc/hostname").toPath())}",
            File(root, "etc/hostname").canExecute(),
        )
        // The archive said 0644; the guest filesystem keeps the owner's bits and nothing
        // else, so a packaged file can never end up readable by another uid.
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(File(root, "etc/hostname").toPath()),
        )
        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
            Files.getPosixFilePermissions(File(root, "bin/sh").toPath()),
        )
        assertTrue(Files.isSymbolicLink(File(root, "bin/sh-link").toPath()))
        assertEquals(
            "sh",
            Files.readSymbolicLink(File(root, "bin/sh-link").toPath()).toString(),
        )
        assertEquals(2, report.files)
        assertEquals(1, report.directories)
        assertEquals(1, report.symlinks)
        assertEquals(0, report.hardLinks)
        assertEquals(0, report.skippedSpecial)
        assertEquals("lenix\n".toByteArray().size + "#!/bin/sh\n".toByteArray().size, report.bytesWritten.toInt())
    }

    @Test
    fun `gz and xz layers extract identically`() {
        val entries = listOf(
            Entry(name = "etc/os-release", bytes = "ID=debian\n".toByteArray()),
            Entry(name = "usr/bin/hello", bytes = "hello".toByteArray(), mode = 0b111101101),
        )
        val codecs = listOf(
            LayerCompression.NONE to TarFixtures.tar(entries),
            LayerCompression.GZIP to TarFixtures.tarGz(entries),
            LayerCompression.XZ to TarFixtures.tarXz(entries),
        )

        codecs.forEach { (compression, archive) ->
            val root = staging()
            val report = extract(archive, compression = compression, into = root).second
            assertEquals("ID=debian\n", TarFixtures.readText(File(root, "etc/os-release")))
            assertArrayEquals("hello".toByteArray(), File(root, "usr/bin/hello").readBytes())
            assertTrue(File(root, "usr/bin/hello").canExecute())
            assertEquals(2, report.files)
        }
    }

    @Test
    fun `a member that walks out of the rootfs is rejected`() {
        val archive = TarFixtures.tar(
            listOf(
                Entry(name = "etc/passwd", bytes = "safe".toByteArray()),
                Entry(name = "../../../../tmp/pwned", bytes = "evil".toByteArray()),
            ),
        )
        val outside = File(tmp.root.parentFile, "lenix-extractor-escape-probe")
        outside.delete()

        val exception = assertThrows(VmException::class.java) {
            extract(archive)
        }

        assertEquals(VmError.ROOTFS_EXTRACTION_FAILED, exception.error)
        assertTrue(exception.message!!.contains("escaping path"))
        assertFalse(outside.exists())
    }

    @Test
    fun `an absolute member stays inside the rootfs instead of the host root`() {
        val archive = TarFixtures.tar(listOf(Entry(name = "/etc/passwd", bytes = "guest".toByteArray())))

        val (root, report) = extract(archive)

        assertEquals("guest", TarFixtures.readText(File(root, "etc/passwd")))
        assertEquals(1, report.files)
    }

    @Test
    fun `a symlinked parent cannot redirect a later write outside the root`() {
        // The classic tar attack: publish `etc` as a symlink to a host directory, then
        // write through it. The write must be refused even though both names are clean.
        val archive = TarFixtures.tar(
            listOf(
                Entry(name = "etc", symlinkTo = tmp.root.absolutePath),
                Entry(name = "etc/pwned", bytes = "nope".toByteArray()),
            ),
        )

        val exception = assertThrows(VmException::class.java) { extract(archive) }

        assertEquals(VmError.ROOTFS_EXTRACTION_FAILED, exception.error)
        assertFalse(File(tmp.root, "pwned").exists())
    }

    @Test
    fun `hard links are re-created inside the root and dangling ones fail`() {
        val linked = listOf(
            Entry(name = "./usr/bin/dash", bytes = "#!/bin/sh\n".toByteArray(), mode = 0b111101101),
            Entry(name = "./bin/sh", hardLinkTo = "./usr/bin/dash"),
        )
        val (root, report) = extract(TarFixtures.tar(linked))

        val original = File(root, "usr/bin/dash")
        val alias = File(root, "bin/sh")
        assertArrayEquals(original.readBytes(), alias.readBytes())
        assertTrue(
            "the alias must be a real hard link to the same inode",
            Files.isSameFile(original.toPath(), alias.toPath()),
        )
        assertEquals(1, report.files)
        assertEquals(1, report.hardLinks)

        val dangling = TarFixtures.tar(
            listOf(Entry(name = "./bin/sh", hardLinkTo = "./usr/bin/never-written")),
        )
        val exception = assertThrows(VmException::class.java) { extract(dangling) }
        assertEquals(VmError.ROOTFS_EXTRACTION_FAILED, exception.error)
        assertTrue(exception.message!!.contains("never wrote"))
    }

    @Test
    fun `device nodes and fifos are skipped, not fatal`() {
        val archive = TarFixtures.tar(
            listOf(
                Entry(name = "./dev/null", type = TarConstants.LF_CHR),
                Entry(name = "./dev/zero", type = TarConstants.LF_BLK),
                Entry(name = "./run/socket", type = TarConstants.LF_FIFO),
                Entry(name = "./etc/hostname", bytes = "lenix".toByteArray()),
            ),
        )

        val (root, report) = extract(archive)

        assertEquals(1, report.files)
        assertEquals(3, report.skippedSpecial)
        assertFalse(File(root, "dev/null").exists())
        assertEquals("lenix", TarFixtures.readText(File(root, "etc/hostname")))
    }

    @Test
    fun `a layer that expands far beyond the manifest is refused`() {
        val payload = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val archive = TarFixtures.tar(listOf(Entry(name = "big.bin", bytes = payload)))
        // The manifest claims 512 KiB unpacked; the cap is 4x that plus slack, so a real
        // 2 MiB archive is still refused: the numbers must come apart, not the guard.
        val stingy = RootfsExtractor(maxExpansionFactor = 1.0, expansionSlackBytes = 0)

        val exception = assertThrows(VmException::class.java) {
            extract(archive, into = staging(), expectedBytes = 512L * 1024, extractor = stingy)
        }

        assertEquals(VmError.ROOTFS_EXTRACTION_FAILED, exception.error)
        assertTrue(exception.message!!.contains("expanded past"))
    }

    @Test
    fun `an empty archive and a truncated archive both fail as extraction errors`() {
        val empty = assertThrows(VmException::class.java) { extract(TarFixtures.tar(emptyList())) }
        assertEquals(VmError.ROOTFS_EXTRACTION_FAILED, empty.error)
        assertTrue(empty.message!!.contains("empty"))

        val truncated = TarFixtures.tar(
            listOf(Entry(name = "half.bin", bytes = ByteArray(4096))),
        ).copyOf(3072) // cut the archive mid-file, leaving a valid header behind
        val broken = assertThrows(VmException::class.java) { extract(truncated) }
        assertEquals(VmError.ROOTFS_EXTRACTION_FAILED, broken.error)
    }

    @Test
    fun `a nearly full device stops extracting with insufficient storage`() {
        val entries = (1..200).map { Entry(name = "f$it", bytes = ByteArray(2048) { 'x'.code.toByte() }) }
        val starving = RootfsExtractor(freeBytes = { 1024L }, minFreeBytes = 32L * 1024 * 1024)

        val exception = assertThrows(VmException::class.java) {
            extract(TarFixtures.tar(entries), extractor = starving)
        }

        assertEquals(VmError.INSUFFICIENT_STORAGE, exception.error)
    }

    @Test
    fun `zstd layers are reported as unsupported rather than mis-extracted`() {
        val exception = assertThrows(VmException::class.java) {
            extract(TarFixtures.tar(listOf(Entry(name = "x", bytes = "y".toByteArray()))), compression = LayerCompression.ZSTD)
        }
        assertEquals(VmError.UNSUPPORTED_COMPRESSION, exception.error)
    }

    @Test
    fun `progress is monotonic and ends at the extracted size`() {
        val entries = (1..40).map { f -> Entry(name = "dir$f/it", bytes = ByteArray(64 * 1024) { 'a'.code.toByte() }) }
        val seen = ArrayList<ExtractionProgress>()

        val (root, report) = extract(
            TarFixtures.tar(entries),
            expectedBytes = entries.sumOf { it.bytes.size.toLong() },
            onProgress = { seen += it },
        )

        assertTrue("progress must be reported, got ${seen.size}", seen.isNotEmpty())
        assertTrue(seen.zipWithNext().all { (before, after) -> after.bytesDone >= before.bytesDone })
        assertEquals(report.bytesWritten, seen.last().bytesDone)
        assertEquals("dir40/it", seen.last().currentEntry)
        assertEquals(entries.sumOf { it.bytes.size.toLong() }, seen.last().bytesTotal)
        seen.forEach { assertEquals("base", it.layerId) }
    }

    @Test
    fun `layers overlay in order, so a desktop layer wins over the base`() {
        val root = staging()
        extract(
            TarFixtures.tar(
                listOf(
                    Entry(name = "etc/motd", bytes = "base".toByteArray()),
                    Entry(name = "usr/bin/openbox", bytes = "#!/bin/sh\n".toByteArray(), mode = 0b111101101),
                ),
            ),
            into = root,
        )
        extract(
            TarFixtures.tar(
                listOf(
                    Entry(name = "etc/motd", bytes = "desktop".toByteArray()),
                    Entry(name = "usr/local/bin/lenix-entry", bytes = "#!/bin/sh\n".toByteArray(), mode = 0b111101101),
                ),
            ),
            into = root,
        )

        assertEquals("desktop", TarFixtures.readText(File(root, "etc/motd")))
        assertTrue(File(root, "usr/bin/openbox").isFile)
        assertTrue(File(root, "usr/local/bin/lenix-entry").canExecute())
    }

    @Test
    fun `unwrap a single top-level wrapper directory (proot-distro layout)`() {
        // proot-distro archives the whole filesystem under the build dir's name
        // (`tar -C $WORKDIR debian-aarch64`), so every member is prefixed with it. A
        // real RootFS root always has several top-level entries, so exactly one top-level
        // directory is the wrapper — the guest must land at the root, not one level deep.
        val archive = TarFixtures.tar(
            listOf(
                Entry(name = "debian-aarch64/", directory = true),
                Entry(name = "debian-aarch64/bin/", directory = true),
                Entry(name = "debian-aarch64/bin/sh", bytes = "#!/bin/sh\n".toByteArray(), mode = 0b111101101),
                Entry(name = "debian-aarch64/etc/os-release", bytes = "ID=debian\n".toByteArray()),
                Entry(name = "debian-aarch64/usr/bin/dash", bytes = "dash".toByteArray(), mode = 0b111101101),
                Entry(name = "debian-aarch64/usr/bin/sh-link", symlinkTo = "dash"),
            ),
        )

        val (root, report) = extract(archive)

        // The wrapper directory is gone and the filesystem is at the root.
        assertFalse(File(root, "debian-aarch64").exists())
        assertTrue(File(root, "bin/sh").canExecute())
        assertEquals("ID=debian\n", TarFixtures.readText(File(root, "etc/os-release")))
        assertTrue(File(root, "usr/bin/dash").isFile)
        assertTrue(Files.isSymbolicLink(File(root, "usr/bin/sh-link").toPath()))
        assertEquals(
            "dash",
            Files.readSymbolicLink(File(root, "usr/bin/sh-link").toPath()).toString(),
        )
        assertEquals(3, report.files)
        assertEquals(2, report.directories)
        assertEquals(1, report.symlinks)
    }

    @Test
    fun `leave a rootfs already laid out at the root untouched`() {
        // An archive with several top-level entries is not a wrapper and must not move.
        val archive = TarFixtures.tar(
            listOf(
                Entry(name = "bin/", directory = true),
                Entry(name = "bin/sh", bytes = "#!/bin/sh\n".toByteArray(), mode = 0b111101101),
                Entry(name = "etc/", directory = true),
                Entry(name = "etc/hostname", bytes = "lenix\n".toByteArray()),
            ),
        )

        val (root, _) = extract(archive)

        assertEquals("lenix\n", TarFixtures.readText(File(root, "etc/hostname")))
        assertTrue(File(root, "bin/sh").canExecute())
        assertTrue(File(root, "bin").isDirectory)
        assertTrue(File(root, "etc").isDirectory)
    }

    @Test
    fun `a lone standard top-level directory is not a wrapper`() {
        // A real rootfs directory that happens to be the archive's only top-level entry
        // (e.g. an archive of just /etc) must stay where it is — it is not a proot-distro
        // wrapper. Its name is a standard rootfs dir and it holds no nested rootfs dirs.
        val archive = TarFixtures.tar(
            listOf(
                Entry(name = "etc/", directory = true),
                Entry(name = "etc/hostname", bytes = "lenix\n".toByteArray()),
            ),
        )

        val (root, _) = extract(archive)

        assertEquals("lenix\n", TarFixtures.readText(File(root, "etc/hostname")))
        assertTrue(File(root, "etc").isDirectory)
        assertFalse(File(root, "hostname").exists())
    }
}

