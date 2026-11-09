package com.iamahsana.ossstudio.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OssPackArchiveValidatorTest {
    @Test fun acceptsHashedDeclarativeResourcePackage() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val archive = createPackage(payload)
        try {
            val result = OssPackArchiveValidator.validate(archive)
            assertEquals("com.example.overlay", result.manifest.id)
            assertEquals("Example Overlay", result.manifest.name)
            assertEquals(setOf("scene-overlay"), result.manifest.capabilities)
            assertEquals(1, result.manifest.files.size)
            assertEquals(payload.size.toLong(), result.manifest.files.single().byteSize)
            assertEquals(payload.size.toLong() + manifestSize(payload), result.totalUncompressedBytes)
        } finally { archive.delete() }
    }

    @Test fun rejectsPayloadChecksumMismatch() {
        val archive = createPackage(byteArrayOf(1, 2, 3), manifestHashOverride = "0".repeat(64))
        try {
            val error = runCatching { OssPackArchiveValidator.validate(archive) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("Checksum"))
        } finally { archive.delete() }
    }

    @Test fun rejectsExecutablePayloads() {
        val archive = createRawPackage(
            """{"format":"oss-extension","formatVersion":1,"payloadType":"declarative","id":"com.example.overlay","name":"Example","version":"1.0.0","minOssVersion":"0.1.0","capabilities":[],"files":[]}""",
            "payload.dex" to byteArrayOf(1, 2, 3, 4)
        )
        try {
            val error = runCatching { OssPackArchiveValidator.validate(archive) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("executable"))
        } finally { archive.delete() }
    }

    @Test fun rejectsArchivePathTraversal() {
        val archive = createRawPackage(
            """{"format":"oss-extension","formatVersion":1,"payloadType":"declarative","id":"com.example.overlay","name":"Example","version":"1.0.0","minOssVersion":"0.1.0","capabilities":[],"files":[]}""",
            "../escape.png" to byteArrayOf(1, 2, 3, 4)
        )
        try {
            val error = runCatching { OssPackArchiveValidator.validate(archive) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("traversal"))
        } finally { archive.delete() }
    }

    @Test fun rejectsUnsupportedFormatVersion() {
        val archive = createRawPackage(
            """{"format":"oss-extension","formatVersion":9,"payloadType":"declarative","id":"com.example.overlay","name":"Example","version":"1.0.0","minOssVersion":"0.1.0","capabilities":[],"files":[]}"""
        )
        try {
            val error = runCatching { OssPackArchiveValidator.validate(archive) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("Versi format"))
        } finally { archive.delete() }
    }

    private fun createPackage(payload: ByteArray, manifestHashOverride: String? = null): File {
        val hash = manifestHashOverride ?: sha256(payload)
        val manifest = """{"format":"oss-extension","formatVersion":1,"payloadType":"declarative","id":"com.example.overlay","name":"Example Overlay","version":"1.0.0","minOssVersion":"0.1.0","capabilities":["scene-overlay"],"files":[{"path":"assets/icon.png","sha256":"$hash","byteSize":""" + payload.size + "}]} "
        return createRawPackage(manifest.trim(), "assets/icon.png" to payload)
    }

    private fun createRawPackage(manifest: String, vararg files: Pair<String, ByteArray>): File {
        val archive = File.createTempFile("oss-test-", ".osspack")
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            files.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return archive
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manifestSize(payload: ByteArray): Long {
        val hash = sha256(payload)
        return ("""{"format":"oss-extension","formatVersion":1,"payloadType":"declarative","id":"com.example.overlay","name":"Example Overlay","version":"1.0.0","minOssVersion":"0.1.0","capabilities":["scene-overlay"],"files":[{"path":"assets/icon.png","sha256":"$hash","byteSize":""" + payload.size + "}]}").toByteArray().size.toLong()
    }
}
