package com.iamahsana.ossstudio.extensions

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

data class OssPackFile(val path: String, val sha256: String, val byteSize: Long)
data class OssPackElement(
    val typeId: String,
    val name: String,
    val kind: String,
    val resourcePath: String?,
    val defaults: Map<String, String>
)
data class OssPackManifest(
    val id: String, val name: String, val version: String, val minOssVersion: String,
    val capabilities: Set<String>, val files: List<OssPackFile>,
    val elements: List<OssPackElement> = emptyList()
)
data class OssPackValidationResult(val manifest: OssPackManifest, val totalUncompressedBytes: Long, val manifestSha256: String)

/** Validates the non-executable .osspack resource format. This is not an extension code loader. */
object OssPackArchiveValidator {
    const val MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L
    const val MAX_UNCOMPRESSED_BYTES = 128L * 1024L * 1024L
    const val MAX_ENTRY_BYTES = 32L * 1024L * 1024L
    const val MAX_MANIFEST_BYTES = 1024L * 1024L
    const val MAX_ENTRIES = 128
    private const val MAX_COMPRESSION_RATIO = 200L
    private const val MANIFEST_NAME = "manifest.json"
    private val supportedCapabilities = setOf("scene-overlay", "media-source", "text-widget")
    private val allowedExtensions = setOf("json", "png", "jpg", "jpeg", "webp", "gif", "ttf", "otf", "mp3", "wav", "ogg", "mp4", "webm")
    private val supportedElementKinds = setOf("text", "image", "sticker", "shape", "background", "font", "gif", "video")
    private val forbiddenExtensions = setOf("apk", "dex", "jar", "so", "class", "js", "mjs", "cjs", "html", "htm", "wasm", "sh", "bat", "ps1", "py")

    fun readInstalledManifest(directory: File): OssPackManifest {
        require(directory.isDirectory) { "Direktori ekstensi tidak ditemukan" }
        val root = directory.canonicalFile
        val manifestFile = File(root, MANIFEST_NAME).canonicalFile
        require(manifestFile.parentFile == root && manifestFile.isFile) { "Manifest ekstensi tidak ditemukan" }
        require(manifestFile.length() in 1..MAX_MANIFEST_BYTES) { "Ukuran manifest ekstensi tidak valid" }
        val manifest = parseManifest(manifestFile.readBytes())
        require(manifest.id == directory.name) { "ID manifest tidak cocok dengan direktori ekstensi" }
        manifest.files.forEach { item ->
            val file = File(root, item.path).canonicalFile
            require(file.path.startsWith(root.path + File.separator) && file.isFile) {
                "Resource ekstensi tidak ditemukan: ${item.path}"
            }
            require(file.length() == item.byteSize) { "Ukuran resource ekstensi berubah: ${item.path}" }
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            require(digest.digest().toHex().equals(item.sha256, ignoreCase = true)) {
                "Checksum resource ekstensi berubah: ${item.path}"
            }
        }
        return manifest
    }

    fun validate(archive: File): OssPackValidationResult {
        require(archive.isFile) { "Paket OSS tidak ditemukan" }
        require(archive.name.lowercase(Locale.ROOT).endsWith(".osspack")) { "Ekstensi paket harus .osspack" }
        val archiveBytes = archive.length()
        require(archiveBytes in 1..MAX_ARCHIVE_BYTES) { "Ukuran paket harus 1 byte sampai 64 MB" }
        val entries = LinkedHashMap<String, OssPackFile>()
        var manifestBytes: ByteArray? = null
        var totalBytes = 0L
        var entryCount = 0

        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    validatePath(entry.name.removeSuffix("/"))
                    zip.closeEntry()
                    continue
                }
                entryCount++
                require(entryCount <= MAX_ENTRIES) { "Paket berisi terlalu banyak file" }
                val path = validatePath(entry.name)
                require(!entries.containsKey(path) && !(path == MANIFEST_NAME && manifestBytes != null)) {
                    "Paket memiliki path file duplikat"
                }
                validateFileType(path)
                val isManifest = path == MANIFEST_NAME
                val limit = if (isManifest) MAX_MANIFEST_BYTES else MAX_ENTRY_BYTES
                val digest = MessageDigest.getInstance("SHA-256")
                val captured = if (isManifest) ByteArrayOutputStream() else null
                var entryBytes = 0L
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    entryBytes += read
                    totalBytes += read
                    require(entryBytes <= limit) { "Ukuran file paket terlalu besar: $path" }
                    require(totalBytes <= MAX_UNCOMPRESSED_BYTES) { "Ukuran hasil ekstraksi paket melebihi 128 MB" }
                    digest.update(buffer, 0, read)
                    captured?.write(buffer, 0, read)
                }
                zip.closeEntry()
                if (isManifest) manifestBytes = captured?.toByteArray()
                else entries[path] = OssPackFile(path, digest.digest().toHex(), entryBytes)
            }
        }

        require(totalBytes <= maxOf(archiveBytes, 1L) * MAX_COMPRESSION_RATIO) { "Rasio kompresi paket tidak wajar" }
        val rawManifest = requireNotNull(manifestBytes) { "manifest.json tidak ditemukan di root paket" }
        val manifest = parseManifest(rawManifest)
        val declared = manifest.files.associateBy { it.path }
        require(declared.size == manifest.files.size) { "Manifest memiliki path file duplikat" }
        require(declared.keys == entries.keys) { "Daftar file manifest tidak sama dengan isi paket" }
        manifest.files.forEach { expected ->
            val actual = entries.getValue(expected.path)
            require(actual.sha256.equals(expected.sha256, ignoreCase = true)) { "Checksum file tidak cocok: ${expected.path}" }
            require(actual.byteSize == expected.byteSize) { "Ukuran file tidak cocok dengan manifest: ${expected.path}" }
        }
        return OssPackValidationResult(manifest, totalBytes, MessageDigest.getInstance("SHA-256").digest(rawManifest).toHex())
    }

    private fun parseManifest(bytes: ByteArray): OssPackManifest {
        val json = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }
            .getOrElse { throw IllegalArgumentException("manifest.json bukan JSON yang valid", it) }
        require(json.optString("format") == "oss-extension") { "Format paket tidak dikenali" }
        require(json.optInt("formatVersion", -1) == 1) { "Versi format paket tidak didukung" }
        require(json.optString("payloadType") == "declarative") {
            "Hanya paket deklaratif yang didukung; kode executable tidak diizinkan"
        }
        val id = json.requiredString("id")
        require(id.matches(Regex("^[a-z][a-z0-9]*(?:[.-][a-z0-9]+){1,5}$"))) { "ID ekstensi tidak valid" }
        val name = json.requiredString("name")
        require(name.length <= 80) { "Nama ekstensi terlalu panjang" }
        val version = json.requiredString("version")
        val minOssVersion = json.requiredString("minOssVersion")
        require(VERSION.matches(version)) { "Versi ekstensi tidak valid" }
        require(VERSION.matches(minOssVersion)) { "Versi minimum OSS tidak valid" }
        val capabilitiesJson = json.optJSONArray("capabilities") ?: JSONArray()
        val capabilities = buildSet {
            for (index in 0 until capabilitiesJson.length()) {
                val capability = capabilitiesJson.optString(index)
                require(capability in supportedCapabilities) { "Kapabilitas tidak didukung: $capability" }
                require(add(capability)) { "Kapabilitas duplikat: $capability" }
            }
        }
        val filesJson = json.optJSONArray("files") ?: throw IllegalArgumentException("Manifest harus memiliki daftar files")
        require(filesJson.length() in 1..MAX_ENTRIES) { "Daftar file manifest tidak valid" }
        val files = (0 until filesJson.length()).map { index ->
            val item = filesJson.optJSONObject(index) ?: throw IllegalArgumentException("Entri file manifest tidak valid")
            val path = validatePath(item.requiredString("path"))
            require(path != MANIFEST_NAME) { "Manifest tidak boleh mencantumkan dirinya sendiri" }
            validateFileType(path)
            val hash = item.requiredString("sha256").lowercase(Locale.ROOT)
            require(hash.matches(Regex("^[a-f0-9]{64}$"))) { "Checksum manifest tidak valid" }
            val size = item.optLong("byteSize", -1L)
            require(size in 1..MAX_ENTRY_BYTES) { "Ukuran file manifest tidak valid" }
            OssPackFile(path, hash, size)
        }
        val elementsJson = json.optJSONArray("elements") ?: JSONArray()
        require(elementsJson.length() <= 64) { "Paket mendefinisikan terlalu banyak elemen" }
        val declaredPaths = files.mapTo(mutableSetOf()) { it.path }
        val elements = (0 until elementsJson.length()).map { index ->
            val item = elementsJson.optJSONObject(index)
                ?: throw IllegalArgumentException("Definisi elemen ekstensi tidak valid")
            val typeId = item.requiredString("typeId")
            require(typeId.matches(Regex("^[a-z][a-z0-9]*(?:[.-][a-z0-9]+){1,7}$")) && typeId.startsWith(id + ".")) {
                "typeId harus namespaced di bawah ID ekstensi: $typeId"
            }
            val elementName = item.requiredString("name")
            require(elementName.length <= 80) { "Nama elemen terlalu panjang" }
            val kind = item.requiredString("kind")
            require(kind in supportedElementKinds) { "Jenis elemen tidak didukung: $kind" }
            val resourcePath = item.opt("resourcePath").takeUnless { it == null || it === JSONObject.NULL }
                ?.let { it as? String ?: throw IllegalArgumentException("resourcePath harus berupa teks") }
            if (resourcePath != null) {
                validatePath(resourcePath)
                require(resourcePath in declaredPaths) { "Resource elemen tidak tercantum di files: $resourcePath" }
            }
            if (kind in setOf("image", "sticker", "background", "gif", "video", "font")) {
                require(resourcePath != null) { "Elemen $kind wajib memiliki resourcePath" }
            }
            val defaultsJson = item.optJSONObject("defaults") ?: JSONObject()
            val defaults = buildMap {
                defaultsJson.keys().forEach { key ->
                    require(key.matches(Regex("^[a-zA-Z][a-zA-Z0-9_.-]{0,63}$"))) { "Nama properti default tidak valid: $key" }
                    val value = defaultsJson.get(key)
                    require(value is String || value is Number || value is Boolean) { "Nilai default harus primitif: $key" }
                    put(key, value.toString())
                }
            }
            OssPackElement(typeId, elementName, kind, resourcePath, defaults)
        }
        require(elements.map { it.typeId }.distinct().size == elements.size) { "typeId elemen duplikat" }
        require(elements.isEmpty() || "scene-overlay" in capabilities) {
            "Paket dengan elemen scene wajib memiliki kapabilitas scene-overlay"
        }
        return OssPackManifest(id, name, version, minOssVersion, capabilities, files, elements)
    }

    private fun JSONObject.requiredString(key: String): String {
        val value = optString(key).trim()
        require(value.isNotEmpty() && value != "null") { "Field manifest wajib: $key" }
        return value
    }

    private fun validatePath(value: String): String {
        require(value.isNotBlank() && !value.startsWith("/") && !value.startsWith("\\")) { "Path paket tidak valid" }
        require(!value.contains('\\') && value.none { it.code < 0x20 || it.code == 0x7f }) { "Path paket mengandung karakter terlarang" }
        require(value.split('/').all { it.isNotBlank() && it != "." && it != ".." }) { "Path traversal tidak diizinkan" }
        require(!Regex("^[A-Za-z]:").containsMatchIn(value)) { "Path absolut tidak diizinkan" }
        return value
    }

    private fun validateFileType(path: String) {
        val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(extension !in forbiddenExtensions) { "File executable tidak diizinkan: $path" }
        require(extension in allowedExtensions) { "Jenis file paket tidak didukung: $path" }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private val VERSION = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-[0-9A-Za-z.-]+)?$")
}
