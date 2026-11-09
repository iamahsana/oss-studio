package com.iamahsana.ossstudio.scene.persistence

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.iamahsana.ossstudio.scene.model.SceneAsset
import com.iamahsana.ossstudio.scene.model.SceneSourceType
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

data class ImportedSceneAsset(
    val asset: SceneAsset,
    val sourceType: SceneSourceType,
    val displayName: String
)

/** Copies user-selected media into app-private storage with size and integrity checks. */
object SceneAssetStorage {
    const val MAX_IMAGE_BYTES = 50L * 1024L * 1024L
    const val MAX_VIDEO_BYTES = 512L * 1024L * 1024L

    private val supportedMimeTypes = mapOf(
        "image/jpeg" to ("jpg" to SceneSourceType.IMAGE),
        "image/png" to ("png" to SceneSourceType.IMAGE),
        "image/webp" to ("webp" to SceneSourceType.IMAGE),
        "image/gif" to ("gif" to SceneSourceType.GIF),
        "video/mp4" to ("mp4" to SceneSourceType.VIDEO),
        "video/webm" to ("webm" to SceneSourceType.VIDEO),
        "video/3gpp" to ("3gp" to SceneSourceType.VIDEO),
        "video/quicktime" to ("mov" to SceneSourceType.VIDEO)
    )

    fun classifyMimeType(value: String): Pair<String, SceneSourceType>? {
        val normalized = value.substringBefore(';').trim().lowercase(Locale.ROOT)
        return supportedMimeTypes[normalized]
    }

    fun importUri(context: Context, projectId: String, uri: Uri): ImportedSceneAsset {
        require(projectId.isNotBlank()) { "Project ID must not be blank" }
        val resolver = context.contentResolver
        val providerName = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        val displayName = sanitizeDisplayName(providerName ?: "Imported media")
        val mimeType = resolver.getType(uri)
            ?: mimeFromName(displayName)
            ?: throw IllegalArgumentException("Format media tidak dikenali")
        val classified = classifyMimeType(mimeType)
            ?: throw IllegalArgumentException("Format media belum didukung: $mimeType")
        val (extension, sourceType) = classified
        val maxBytes = if (sourceType == SceneSourceType.VIDEO) MAX_VIDEO_BYTES else MAX_IMAGE_BYTES

        val projectFolder = MessageDigest.getInstance("SHA-256")
            .digest(projectId.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }
        val relativePath = "scene_assets/$projectFolder/${UUID.randomUUID()}.$extension"
        val root = context.filesDir.canonicalFile
        val target = File(root, relativePath).canonicalFile
        val parent = target.parentFile ?: error("Invalid asset destination")
        require(parent.path.startsWith(root.path + File.separator)) { "Asset destination escaped app storage" }
        check(parent.exists() || parent.mkdirs()) { "Unable to create asset directory" }
        val temporary = File(parent, ".${target.name}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L

        try {
            val input = resolver.openInputStream(uri) ?: error("Unable to open selected media")
            input.use { source ->
                FileOutputStream(temporary).buffered().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        totalBytes += count
                        require(totalBytes <= maxBytes) { "Ukuran media melebihi batas ${maxBytes / (1024 * 1024)} MB" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                }
            }
            require(totalBytes > 0L) { "File media kosong" }
            require(hasValidContentSignature(temporary, mimeType)) {
                "Isi file tidak sesuai dengan format media yang dipilih"
            }
            check(temporary.renameTo(target)) { "Unable to finalize imported media" }
        } catch (error: Throwable) {
            temporary.delete()
            target.delete()
            throw error
        }

        val asset = SceneAsset(
            relativePath = relativePath,
            mimeType = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT),
            byteSize = totalBytes,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            provenance = "user-import"
        )
        return ImportedSceneAsset(asset, sourceType, displayName)
    }



    internal fun hasValidContentSignature(file: File, mimeType: String): Boolean {
        if (!file.isFile || file.length() < 4L) return false
        val header = ByteArray(12)
        val count = FileInputStream(file).use { input ->
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read < 0) break
                offset += read
            }
            offset
        }
        if (count < 4) return false
        fun ascii(offset: Int, value: String): Boolean {
            val bytes = value.toByteArray(Charsets.US_ASCII)
            return offset + bytes.size <= count &&
                bytes.indices.all { header[offset + it] == bytes[it] }
        }
        return when (mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)) {
            "image/jpeg" -> header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()
            "image/png" -> count >= 8 && header.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            )
            "image/webp" -> count >= 12 && ascii(0, "RIFF") && ascii(8, "WEBP")
            "image/gif" -> ascii(0, "GIF87a") || ascii(0, "GIF89a")
            "video/webm" -> count >= 4 && header[0] == 0x1A.toByte() &&
                header[1] == 0x45.toByte() && header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()
            "video/mp4" -> count >= 12 && ascii(4, "ftyp") &&
                listOf("isom", "iso2", "mp41", "mp42", "avc1", "M4V ", "dash").any { ascii(8, it) }
            "video/quicktime" -> count >= 12 && ascii(4, "ftyp") && ascii(8, "qt  ")
            "video/3gpp" -> count >= 12 && ascii(4, "ftyp") && ascii(8, "3gp")
            else -> false
        }
    }

    fun deleteAsset(context: Context, asset: SceneAsset): Boolean {
        require(asset.relativePath.startsWith("scene_assets/")) { "Only managed scene assets may be deleted" }
        val root = context.filesDir.canonicalFile
        val target = File(root, asset.relativePath).canonicalFile
        require(target.path.startsWith(root.path + File.separator)) { "Asset path escaped app storage" }
        if (!target.exists()) return false
        require(target.isFile) { "Asset path is not a regular file" }
        return target.delete()
    }

    private fun sanitizeDisplayName(value: String): String =
        value.substringAfterLast('/').substringAfterLast('\\')
            .filter { it.code >= 0x20 && it.code != 0x7f }
            .take(100)
            .ifBlank { "Imported media" }

    private fun mimeFromName(name: String): String? {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return supportedMimeTypes.entries.firstOrNull { it.value.first == extension }?.key
    }
}
