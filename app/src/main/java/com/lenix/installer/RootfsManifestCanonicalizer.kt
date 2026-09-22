package com.lenix.installer

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.DecimalNode
import com.fasterxml.jackson.databind.node.BigIntegerNode
import com.fasterxml.jackson.databind.node.DoubleNode
import com.fasterxml.jackson.databind.node.FloatNode
import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.LongNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * Lenix Canonical JSON (v1) — the byte sequence a RootFS manifest signature covers.
 *
 * A manifest carries its own signature, so the signed message cannot be the file as
 * delivered: it is the document re-serialized under this fixed set of rules, with the
 * `signature` member removed.
 *
 *  * the top-level `signature` member is dropped;
 *  * object keys are emitted sorted by UTF-16 code unit, no insignificant whitespace,
 *    no trailing newline, UTF-8;
 *  * numbers keep their source token — floats and integers are read as
 *    `BigDecimal`/`BigInteger` so `0.30` stays `0.30` and never drifts through a
 *    binary double on the way to the payload;
 *  * strings escape `"` and backslash, use the `b t n f r` short forms, and escape
 *    every other code point outside printable ASCII as a lowercase `\uXXXX` escape.
 *
 * `scripts/canonical-json.py` is the byte-for-byte mirror used by the release
 * pipeline. The two are pinned against each other by `RootfsManifestCanonicalizerTest`
 * (fixed expected bytes) and `BundledRootfsManifestTrustTest` (the shipped manifest
 * verifies against the shipped key), so a change on one side fails CI on the other.
 */
object RootfsManifestCanonicalizer {

    /** The manifest member holding the signature — never part of the signed payload. */
    const val SIGNATURE_MEMBER = "signature"

    // `USE_BIG_DECIMAL_FOR_FLOATS` only steers data binding, not tree reading, so the node
    // factory has to be told too — otherwise `2.5000` becomes a `DoubleNode` and prints
    // `2.5`, which changes the signed payload. Exact big decimals are the whole point here.
    private val mapper: ObjectMapper = jacksonObjectMapper()
        .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true))
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    /** The canonical text of [manifestJson], with the `signature` member removed. */
    fun canonicalText(manifestJson: String): String {
        val root = try {
            mapper.readTree(manifestJson)
        } catch (e: Exception) {
            throw IllegalArgumentException("Not a readable RootFS manifest: ${e.message}", e)
        }
        require(root != null && root.isObject) { "A RootFS manifest must be a JSON object." }
        val out = StringBuilder(manifestJson.length)
        writeObject(root, out, topLevel = true)
        return out.toString()
    }

    /** UTF-8 bytes of [canonicalText] — the exact message a signature covers. */
    fun canonicalBytes(manifestJson: String): ByteArray =
        canonicalText(manifestJson).toByteArray(Charsets.UTF_8)

    private fun writeObject(node: JsonNode, out: StringBuilder, topLevel: Boolean) {
        val names = ArrayList<String>(node.size())
        node.fieldNames().forEachRemaining { name ->
            if (!(topLevel && name == SIGNATURE_MEMBER)) names.add(name)
        }
        // Kotlin string comparison is UTF-16 code-unit order, which the Python mirror
        // reproduces explicitly.
        names.sort()
        out.append('{')
        names.forEachIndexed { index, name ->
            if (index > 0) out.append(',')
            writeString(name, out)
            out.append(':')
            write(node.get(name), out)
        }
        out.append('}')
    }

    private fun write(node: JsonNode?, out: StringBuilder) {
        if (node == null || node.isNull) {
            out.append("null")
            return
        }
        when {
            node.isBoolean -> out.append(if (node.booleanValue()) "true" else "false")
            node.isObject -> writeObject(node, out, topLevel = false)
            node.isArray -> {
                out.append('[')
                var index = 0
                node.elements().forEachRemaining { element ->
                    if (index++ > 0) out.append(',')
                    write(element, out)
                }
                out.append(']')
            }
            node.isNumber -> out.append(numberText(node))
            node.isTextual -> writeString(node.textValue(), out)
            else -> throw IllegalArgumentException(
                "Unsupported value in manifest: ${node::class.java.simpleName}.",
            )
        }
    }

    /**
     * Numbers are copied through as written in the source, so the payload is stable
     * across re-formatting and identical to what the Python signer produces.
     */
    private fun numberText(node: JsonNode): String = when (node) {
        is DecimalNode -> node.decimalValue().toString()
        is BigIntegerNode -> node.bigIntegerValue().toString()
        is IntNode -> node.asInt().toString()
        is LongNode -> node.asLong().toString()
        is DoubleNode -> node.asDouble().toString()
        is FloatNode -> node.asDouble().toString()
        else -> throw IllegalArgumentException(
            "Unsupported number format in manifest (${node::class.java.simpleName}).",
        )
    }

    private fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        for (element in value) {
            when {
                element == '"' -> out.append("\\\"")
                element == '\\' -> out.append("\\\\")
                element == '\b' -> out.append("\\b")
                element == '\u000C' -> out.append("\\f")
                element == '\n' -> out.append("\\n")
                element == '\r' -> out.append("\\r")
                element == '\t' -> out.append("\\t")
                element.code < 0x20 || element.code > 0x7E -> appendUnicodeEscape(element.code, out)
                else -> out.append(element)
            }
        }
        out.append('"')
    }

    private fun appendUnicodeEscape(code: Int, out: StringBuilder) {
        out.append("\\u")
        for (shift in 12 downTo 0 step 4) {
            out.append("0123456789abcdef"[(code shr shift) and 0xF])
        }
    }
}
