package com.iamahsana.ossstudio.extensions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class OssPackArchiveValidatorTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

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
            assertEquals(64, result.manifestSha256.length)
        } finally { archive.delete() }
    }

    @Test fun readsNamespacedDeclarativeSceneElementsAndDefaults() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val manifest = """{"format":"oss-extension","formatVersion":1,"payloadType":"declarative","id":"com.example.overlay","name":"Example Overlay","version":"1.0.0","minOssVersion":"0.1.0","capabilities":["scene-overlay"],"files":[{"path":"assets/icon.png","sha256":"${sha256(payload)}","byteSize":4}],"elements":[{"typeId":"com.example.overlay.badge","name":"Badge","kind":"sticker","resourcePath":"assets/icon.png","defaults":{"anchor":"top-right","opacity":0.8}},{"typeId":"com.example.overlay.title","name":"Title","kind":"text","defaults":{"text":"Hello","fontSize":48,"bold":true}}]}"""
        val archive = createRawPackage(manifest, "assets/icon.png" to payload)
        try {
            val result = OssPackArchiveValidator.validate(archive)
            assertEquals(2, result.manifest.elements.size)
            assertEquals("com.example.overlay.badge", result.manifest.elements[0].typeId)
            assertEquals("assets/icon.png", result.manifest.elements[0].resourcePath)
            assertEquals("top-right", result.manifest.elements[0].defaults["anchor"])
            assertEquals("0.8", result.manifest.elements[0].defaults["opacity"])
            assertEquals("Hello", result.manifest.elements[1].defaults["text"])
        } finally { archive.delete() }
    }

    @Test fun acceptsDeclaredVideoResources() {
        val payload = byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112)
        val manifest = """{"format":"oss-extension","formatVersion":1,"payloadType":"declarative","id":"com.example.video","name":"Video","version":"1.0.0","minOssVersion":"0.1.0","capabilities":["scene-overlay"],"files":[{"path":"assets/clip.mp4","sha256":"${sha256(payload)}","byteSize":${payload.size}}],"elements":[{"typeId":"com.example.video.clip","name":"Clip","kind":"video","resourcePath":"assets/clip.mp4"}]}"""
        val archive = createRawPackage(manifest, "assets/clip.mp4" to payload)
        try {
            val result = OssPackArchiveValidator.validate(archive)
            assertEquals("video", result.manifest.elements.single().kind)
            assertEquals("assets/clip.mp4", result.manifest.elements.single().resourcePath)
        } finally { archive.delete() }
    }

    @Test fun rejectsElementTypeIdsOutsideExtensionNamespace() {
        val payload = byteArrayOf(1, 2, 3)
        val manifest = """{"format":"oss-extension","formatVersion":1,"payloadType":"declarative","id":"com.example.overlay","name":"Example","version":"1.0.0","minOssVersion":"0.1.0","capabilities":["scene-overlay"],"files":[{"path":"assets/icon.png","sha256":"${sha256(payload)}","byteSize":3}],"elements":[{"typeId":"com.other.badge","name":"Badge","kind":"sticker","resourcePath":"assets/icon.png"}]}"""
        val archive = createRawPackage(manifest, "assets/icon.png" to payload)
        try {
            val error = runCatching { OssPackArchiveValidator.validate(archive) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("namespaced"))
        } finally { archive.delete() }
    }

    @Test fun rejectsElementResourcesNotDeclaredInPackageFiles() {
        val payload = byteArrayOf(1, 2, 3)
        val manifest = """{"format":"oss-extension","formatVersion":1,"payloadType":"declarative","id":"com.example.overlay","name":"Example","version":"1.0.0","minOssVersion":"0.1.0","capabilities":["scene-overlay"],"files":[{"path":"assets/icon.png","sha256":"${sha256(payload)}","byteSize":3}],"elements":[{"typeId":"com.example.overlay.badge","name":"Badge","kind":"sticker","resourcePath":"assets/missing.png"}]}"""
        val archive = createRawPackage(manifest, "assets/icon.png" to payload)
        try {
            val error = runCatching { OssPackArchiveValidator.validate(archive) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("tidak tercantum"))
        } finally { archive.delete() }
    }

    @Test fun installerPublishesOnlyVerifiedFiles() {
        val payload = byteArrayOf(8, 7, 6, 5)
        val archive = createPackage(payload)
        val root = File(context.cacheDir, "extensions-" + System.nanoTime())
        try {
            val installed = OssPackInstaller.install(archive, root)
            assertTrue(installed.isDirectory)
            assertArrayEquals(payload, File(installed, "assets/icon.png").readBytes())
            assertTrue(File(installed, "manifest.json").isFile)
            assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".staging") })
        } finally {
            archive.delete()
            root.deleteRecursively()
        }
    }

    @Test fun installedCatalogRevalidatesResourceAndReturnsElementDefinitions() {
        val payload = byteArrayOf(4, 3, 2, 1)
        val manifest = """{"format":"oss-extension","formatVersion":1,"payloadType":"declarative","id":"com.example.catalog","name":"Catalog","version":"1.0.0","minOssVersion":"0.1.0","capabilities":["scene-overlay"],"files":[{"path":"assets/badge.png","sha256":"${sha256(payload)}","byteSize":4}],"elements":[{"typeId":"com.example.catalog.badge","name":"Badge","kind":"sticker","resourcePath":"assets/badge.png","defaults":{"opacity":0.9}}]}"""
        val archive = createRawPackage(manifest, "assets/badge.png" to payload)
        val root = File(context.cacheDir, "extensions-catalog-" + System.nanoTime())
        try {
            val installed = OssPackInstaller.install(archive, root)
            val catalog = OssPackArchiveValidator.readInstalledManifest(installed)
            assertEquals("com.example.catalog", catalog.id)
            assertEquals("com.example.catalog.badge", catalog.elements.single().typeId)
            assertEquals("0.9", catalog.elements.single().defaults["opacity"])

            File(installed, "assets/badge.png").writeBytes(byteArrayOf(9, 9, 9))
            val error = runCatching { OssPackArchiveValidator.readInstalledManifest(installed) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("resource"))
        } finally {
            archive.delete()
            root.deleteRecursively()
        }
    }

    @Test fun installedElementCatalogListsAndResolvesOnlyVerifiedPackageResources() {
        val payload = byteArrayOf(4, 3, 2, 1)
        val manifest = """{"format":"oss-extension","formatVersion":1,"payloadType":"declarative","id":"com.example.catalog","name":"Catalog","version":"1.0.0","minOssVersion":"0.1.0","capabilities":["scene-overlay"],"files":[{"path":"assets/badge.png","sha256":"${sha256(payload)}","byteSize":4}],"elements":[{"typeId":"com.example.catalog.badge","name":"Badge","kind":"sticker","resourcePath":"assets/badge.png","defaults":{"opacity":0.9}},{"typeId":"com.example.catalog.title","name":"Title","kind":"text","defaults":{"text":"LIVE"}}]}"""
        val archive = createRawPackage(manifest, "assets/badge.png" to payload)
        val root = File(context.cacheDir, "extensions-element-catalog-" + System.nanoTime())
        try {
            OssPackInstaller.install(archive, root)
            val elements = OssInstalledElementCatalog.listInstalled(root)
            assertEquals(2, elements.size)
            val badge = OssInstalledElementCatalog.find(root, "com.example.catalog", "com.example.catalog.badge")
            assertNotNull(badge)
            assertEquals("sticker", badge?.kind)
            assertEquals("0.9", badge?.defaults?.get("opacity"))
            assertArrayEquals(payload, badge?.resolveResource()?.readBytes())
            val title = OssInstalledElementCatalog.find(root, "com.example.catalog", "com.example.catalog.title")
            assertNotNull(title)
            assertEquals(null, title?.resolveResource())

            File(root, "com.example.catalog/assets/badge.png").writeBytes(byteArrayOf(9, 9, 9))
            assertTrue(OssInstalledElementCatalog.listInstalled(root).isEmpty())
        } finally {
            archive.delete()
            root.deleteRecursively()
        }
    }

    @Test fun installerUsesUniqueStagingDirectoryForEachExtension() {
        val firstArchive = createPackage(byteArrayOf(1, 3, 5), id = "com.example.first")
        val secondArchive = createPackage(byteArrayOf(2, 4, 6), id = "com.example.second")
        val root = File(context.cacheDir, "extensions-multiple-" + System.nanoTime())
        try {
            val first = OssPackInstaller.install(firstArchive, root)
            val second = OssPackInstaller.install(secondArchive, root)
            assertTrue(first.isDirectory)
            assertTrue(second.isDirectory)
            assertTrue(File(first, "assets/icon.png").isFile)
            assertTrue(File(second, "assets/icon.png").isFile)
            assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".staging") })
        } finally {
            firstArchive.delete()
            secondArchive.delete()
            root.deleteRecursively()
        }
    }

    @Test fun refusesToOverwriteAnInstalledExtensionId() {
        val archive = createPackage(byteArrayOf(4, 3, 2, 1))
        val root = File(context.cacheDir, "extensions-duplicate-" + System.nanoTime())
        try {
            OssPackInstaller.install(archive, root)
            val error = runCatching { OssPackInstaller.install(archive, root) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("sudah terpasang"))
        } finally {
            archive.delete()
            root.deleteRecursively()
        }
    }

    @Test fun rejectsPayloadChecksumMismatch() {
        val archive = createPackage(byteArrayOf(1, 2, 3), manifestHashOverride = "0".repeat(64))
        try {
            val error = runCatching { OssPackArchiveValidator.validate(archive) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("assets/icon.png"))
        } finally { archive.delete() }
    }

    @Test fun rejectsExecutablePayloads() {
        val archive = createRawPackage(
            """{"format":"oss-extension","formatVersion":1,"payloadType":"declarative","id":"com.example.executabletest","name":"Example","version":"1.0.0","minOssVersion":"0.1.0","capabilities":[],"files":[]}""",
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
            assertNotNull("Arsip dengan path ../ harus ditolak", error)
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

    @Test fun rejectsUnsupportedCapabilities() {
        val archive = createRawPackage(
            """{"format":"oss-extension","formatVersion":1,"payloadType":"declarative","id":"com.example.overlay","name":"Example","version":"1.0.0","minOssVersion":"0.1.0","capabilities":["arbitrary-code"],"files":[]}"""
        )
        try {
            val error = runCatching { OssPackArchiveValidator.validate(archive) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("Kapabilitas tidak didukung"))
        } finally { archive.delete() }
    }

    @Test fun rejectsPayloadFilesMissingFromManifest() {
        val payload = byteArrayOf(2, 4, 6)
        val manifest = """{"format":"oss-extension","formatVersion":1,"payloadType":"declarative","id":"com.example.overlay","name":"Example","version":"1.0.0","minOssVersion":"0.1.0","capabilities":[],"files":[{"path":"assets/icon.png","sha256":"${sha256(payload)}","byteSize":3}]}"""
        val archive = createRawPackage(
            manifest,
            "assets/icon.png" to payload,
            "assets/hidden.png" to byteArrayOf(1, 1, 1)
        )
        try {
            val error = runCatching { OssPackArchiveValidator.validate(archive) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("tidak sama"))
        } finally { archive.delete() }
    }

    private fun createPackage(payload: ByteArray, manifestHashOverride: String? = null, id: String = "com.example.overlay"): File {
        val hash = manifestHashOverride ?: sha256(payload)
        val manifest = """{"format":"oss-extension","formatVersion":1,"payloadType":"declarative","id":"$id","name":"Example Overlay","version":"1.0.0","minOssVersion":"0.1.0","capabilities":["scene-overlay"],"files":[{"path":"assets/icon.png","sha256":"$hash","byteSize":""" + payload.size + "}]} "
        return createRawPackage(manifest.trim(), "assets/icon.png" to payload)
    }

    private fun createRawPackage(manifest: String, vararg files: Pair<String, ByteArray>): File {
        val archive = File.createTempFile("oss-test-", ".osspack", context.cacheDir)
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
