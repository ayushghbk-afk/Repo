package com.lenix.data.download

import com.lenix.vm.VmError
import com.lenix.vm.VmException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LayerCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sha = "a".repeat(64)
    private val otherSha = "b".repeat(64)

    private fun cache() = LayerCache(tmp.newFolder())

    @Test
    fun `files are content-addressed by sha256`() {
        val dir = tmp.newFolder()
        val cache = LayerCache(dir)

        assertEquals(File(dir, "$sha.layer"), cache.cachedFile(sha))
        assertEquals(File(dir, "$sha.layer.part"), cache.partFile(sha))
        assertEquals(File(dir, "$sha.layer.etag"), cache.etagFile(sha))
    }

    @Test
    fun `malformed digests are rejected before they become filenames`() {
        val cache = cache()

        val pathTrick = assertThrows(VmException::class.java) { cache.cachedFile("../escape") }
        assertEquals(VmError.DOWNLOAD_CORRUPTED, pathTrick.error)
        assertThrows(VmException::class.java) { cache.partFile("too-short") }
        assertThrows(VmException::class.java) { cache.etagFile("z".repeat(64)) }
    }

    @Test
    fun `discardPartials keeps completed layers`() {
        val cache = cache()
        cache.cachedFile(sha).writeBytes(byteArrayOf(1))
        cache.partFile(sha).writeBytes(byteArrayOf(1))
        cache.etagFile(sha).writeText("\"v1\"")
        cache.partFile(otherSha).writeBytes(byteArrayOf(1))

        cache.discardPartials()

        assertTrue(cache.cachedFile(sha).isFile)
        assertFalse(cache.partFile(sha).exists())
        assertFalse(cache.etagFile(sha).exists())
        assertFalse(cache.partFile(otherSha).exists())
    }

    @Test
    fun `cachedBytes only counts completed layers`() {
        val cache = cache()
        cache.cachedFile(sha).writeBytes(ByteArray(100))
        cache.partFile(sha).writeBytes(ByteArray(50))

        assertEquals(100L, cache.cachedBytes())
    }
}
