package com.iamahsana.ossstudio.extensions

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/** Extracts a validated .osspack into app-private staging and verifies every payload again. */
object OssPackInstaller {
    fun install(archive: File, extensionsDirectory: File): File {
        val validation = OssPackArchiveValidator.validate(archive)
        require(extensionsDirectory.exists() || extensionsDirectory.mkdirs()) { "Direktori ekstensi tidak dapat dibuat" }
        val root = extensionsDirectory.canonicalFile
        val destination = File(root, validation.manifest.id).canonicalFile
        require(destination.parentFile == root) { "ID ekstensi keluar dari direktori instalasi" }
        require(!destination.exists()) { "Ekstensi dengan ID ini sudah terpasang" }
        val staging = File(root, ".\${validation.manifest.id}-\${UUID.randomUUID()}.staging")
        check(staging.mkdir()) { "Direktori staging tidak dapat dibuat" }
        try {
            val expected = validation.manifest.files.associateBy { it.path }
            val written = mutableSetOf<String>()
            var manifestWritten = false
            ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        require(isSafePath(entry.name.removeSuffix("/"))) { "Path direktori paket tidak valid" }
                        zip.closeEntry()
                        continue
                    }
                    val path = entry.name
                    require(isSafePath(path)) { "Path file paket tidak valid" }
                    require(path == "manifest.json" || path in expected) {
                        "Paket berubah setelah validasi: file tidak terdaftar"
                    }
                    if (path == "manifest.json") {
                        require(!manifestWritten) { "Manifest duplikat saat ekstraksi" }
                        val output = safeTarget(staging, path)
                        output.parentFile?.let { check(it.exists() || it.mkdirs()) }
                        FileOutputStream(output).use { target ->
                            val buffer = ByteArray(8 * 1024)
                            var count = 0L
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                count += read
                                require(count <= OssPackArchiveValidator.MAX_MANIFEST_BYTES) { "Manifest terlalu besar saat ekstraksi" }
                                target.write(buffer, 0, read)
                            }
                        }
                        manifestWritten = true
                    } else {
                        require(written.add(path)) { "File duplikat saat ekstraksi: $path" }
                        val specification = expected.getValue(path)
                        val digest = MessageDigest.getInstance("SHA-256")
                        val output = safeTarget(staging, path)
                        output.parentFile?.let { check(it.exists() || it.mkdirs()) }
                        var count = 0L
                        FileOutputStream(output).use { target ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                count += read
                                require(count <= specification.byteSize && count <= OssPackArchiveValidator.MAX_ENTRY_BYTES) {
                                    "Ukuran file berubah saat ekstraksi: $path"
                                }
                                digest.update(buffer, 0, read)
                                target.write(buffer, 0, read)
                            }
                        }
                        require(count == specification.byteSize) { "Ukuran file tidak cocok saat ekstraksi: $path" }
                        require(digest.digest().toHex().equals(specification.sha256, ignoreCase = true)) {
                            "Checksum berubah saat ekstraksi: $path"
                        }
                    }
                    zip.closeEntry()
                }
            }
            require(manifestWritten) { "Manifest hilang saat ekstraksi" }
            require(written == expected.keys) { "Daftar file berubah saat ekstraksi" }
            check(staging.renameTo(destination)) { "Tidak dapat menerbitkan ekstensi secara atomik" }
            return destination
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun safeTarget(root: File, relativePath: String): File {
        val target = File(root, relativePath).canonicalFile
        require(target.path.startsWith(root.canonicalPath + File.separator)) { "Path ekstraksi keluar dari direktori staging" }
        return target
    }

    private fun isSafePath(path: String): Boolean =
        path.isNotBlank() && !path.startsWith("/") && !path.startsWith("\\") &&
            !path.contains('\\') && path.none { it.code < 0x20 || it.code == 0x7f } &&
            !Regex("^[A-Za-z]:").containsMatchIn(path) &&
            path.split('/').all { it.isNotBlank() && it != "." && it != ".." }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
