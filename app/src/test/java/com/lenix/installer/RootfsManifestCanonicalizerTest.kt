package com.lenix.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins the signature payload format (docs/ROOTFS_SYSTEM §3).
 *
 * The expected strings below are the output of `scripts/canonical-json.py`, i.e. the
 * release pipeline's view of the same document. If this test ever fails, the two
 * implementations have drifted apart and every manifest signed by CI would be rejected
 * on devices — exactly the failure mode it exists to prevent.
 */
class RootfsManifestCanonicalizerTest {

    private val manifest = """
        {
          "version": "1.0.0",
          "id": "debian-bookworm-aarch64",
          "layers": [
            { "sha256": "abc", "id": "base", "uncompressedBytes": 150000000,
              "sizeBytes": 42894344, "compression": "xz" }
          ],
          "signature": "ed25519:this-must-never-be-part-of-the-payload",
          "install": { "bootCommand": "/bin/bash", "estimatedFreeGb": 0.3 },
          "compatibility": { "minRamMb": 2048 }
        }
    """.trimIndent()

    private val canonical =
        "{\"compatibility\":{\"minRamMb\":2048},\"id\":\"debian-bookworm-aarch64\"," +
            "\"install\":{\"bootCommand\":\"/bin/bash\",\"estimatedFreeGb\":0.3}," +
            "\"layers\":[{\"compression\":\"xz\",\"id\":\"base\",\"sha256\":\"abc\"," +
            "\"sizeBytes\":42894344,\"uncompressedBytes\":150000000}],\"version\":\"1.0.0\"}"

    @Test
    fun `sorts keys, drops whitespace and removes the signature member`() {
        assertEquals(canonical, RootfsManifestCanonicalizer.canonicalText(manifest))
    }

    @Test
    fun `reformatting a manifest does not change what is signed`() {
        val reformatted = canonical
            .replace(",", ",\n  ")
            .replace(":", " : ")
            .replace("{", "{ ")

        assertEquals(canonical, RootfsManifestCanonicalizer.canonicalText(reformatted))
    }

    @Test
    fun `numbers keep their source token`() {
        val input = """{"b": 2.5000, "a": 1.0e7, "c": 42}"""

        val out = RootfsManifestCanonicalizer.canonicalText(input)

        // `2.5000` must not be trimmed and `1.0e7` must not pass through a binary double;
        // the exponent is spelled exactly the way Python's Decimal writes it.
        assertEquals("""{"a":1.0E+7,"b":2.5000,"c":42}""", out)
    }

    @Test
    fun `escapes exactly the characters the python mirror escapes`() {
        // Each JSON escape is spelled `\\u00e9` in source, i.e. an escaped backslash followed
        // by the letters: the *document text* stays plain ASCII and holds real JSON escapes,
        // while Kotlin never sees a `\u` escape of its own. The canonical form must keep the
        // short form for tab and re-escape the control character, DEL, the non-ASCII letter
        // and the quote — byte-for-byte what `scripts/canonical-json.py` emits for this same
        // input, which is what makes the two implementations one contract.
        val input = "{\"z\": \"caf\\u00e9 \\u0001 q\\t\\u007f \\\"\"}"
        val expected = "{\"z\":\"caf\\u00e9 \\u0001 q\\t\\u007f \\\"\"}"

        assertEquals(expected, RootfsManifestCanonicalizer.canonicalText(input))
    }

    @Test
    fun `rejects anything that is not one json object`() {
        assertThrows(IllegalArgumentException::class.java) {
            RootfsManifestCanonicalizer.canonicalText("[1,2,3]")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootfsManifestCanonicalizer.canonicalText("{\"id\": }")
        }
    }

    @Test
    fun `a manifest without a signature member canonicalizes unchanged`() {
        val noSignature = """{"b": 1, "a": 2}"""

        assertEquals("""{"a":2,"b":1}""", RootfsManifestCanonicalizer.canonicalText(noSignature))
    }
}
