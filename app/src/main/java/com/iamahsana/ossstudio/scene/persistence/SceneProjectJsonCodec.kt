package com.iamahsana.ossstudio.scene.persistence

import com.iamahsana.ossstudio.scene.model.*
import org.json.JSONArray
import org.json.JSONObject

data class DecodedSceneProject(
    val project: SceneProject,
    val sourceSchemaVersion: Int,
    val migrated: Boolean
)

/**
 * JSON persistence codec. Schemas v1-v3 remain readable; v4 persists LIVE
 * orientation while retaining the stable extension identifiers introduced in v3.
 */
object SceneProjectJsonCodec {
    const val CURRENT_SCHEMA_VERSION = 4
    private const val MIN_SUPPORTED_SCHEMA_VERSION = 1

    fun encode(p: SceneProject): String = JSONObject().apply {
        put("schemaVersion", CURRENT_SCHEMA_VERSION)
        put("id", p.id)
        put("name", p.name)
        put("createdAt", p.createdAtEpochMillis)
        put("updatedAt", p.updatedAtEpochMillis)
        put("activeSceneId", p.activeSceneId ?: JSONObject.NULL)
        put("version", p.version)
        put("liveOrientation", p.liveOrientation.name)
        put("scenes", JSONArray().apply { p.scenes.forEach { put(sceneToJson(it)) } })
        put("sources", JSONArray().apply { p.sources.forEach { put(sourceToJson(it)) } })
        put("assets", JSONArray().apply { p.assets.forEach { put(assetToJson(it)) } })
    }.toString()

    fun decode(json: String): SceneProject = decodeWithMetadata(json).project

    fun decodeWithMetadata(json: String): DecodedSceneProject {
        val o = JSONObject(json)
        val schemaVersion = o.getInt("schemaVersion")
        require(schemaVersion in MIN_SUPPORTED_SCHEMA_VERSION..CURRENT_SCHEMA_VERSION) {
            "Unsupported project schema: $schemaVersion"
        }

        val active = o.opt("activeSceneId").nullableString()
        val scenes = o.getJSONArray("scenes").objects(::sceneFromJson)
        val sources = o.getJSONArray("sources").objects(::sourceFromJson)
        val assets = o.getJSONArray("assets").objects(::assetFromJson)

        val orientation = if (schemaVersion >= CURRENT_SCHEMA_VERSION || o.has("liveOrientation")) {
            CanvasOrientation.valueOf(o.getString("liveOrientation"))
        } else {
            scenes.firstOrNull { it.id == active }?.canvas?.orientation
                ?: scenes.firstOrNull()?.canvas?.orientation
                ?: CanvasOrientation.PORTRAIT
        }

        val decodedProject = SceneProject(
            id = o.getString("id"),
            name = o.getString("name"),
            createdAtEpochMillis = o.getLong("createdAt"),
            updatedAtEpochMillis = o.getLong("updatedAt"),
            activeSceneId = active,
            scenes = scenes,
            sources = sources,
            assets = assets,
            version = o.getInt("version"),
            liveOrientation = orientation
        )

        if (schemaVersion < CURRENT_SCHEMA_VERSION) {
            return DecodedSceneProject(
                project = SceneProjectOrientationConverter.convert(decodedProject, orientation),
                sourceSchemaVersion = schemaVersion,
                migrated = true
            )
        }

        return DecodedSceneProject(decodedProject, schemaVersion, migrated = false)
    }

    private fun sceneToJson(s: Scene) = JSONObject().apply {
        put("id", s.id); put("projectId", s.projectId); put("name", s.name)
        put("w", s.canvas.width); put("h", s.canvas.height); put("orientation", s.canvas.orientation.name)
        put("background", s.backgroundArgb)
        put("elements", JSONArray().apply { s.elements.forEach { e -> put(JSONObject().apply {
            put("id", e.id); put("source", e.sourceId); put("x", e.transform.x); put("y", e.transform.y)
            put("w", e.transform.width); put("h", e.transform.height); put("rotation", e.transform.rotationDegrees)
            put("cl", e.crop.left); put("ct", e.crop.top); put("cr", e.crop.right); put("cb", e.crop.bottom)
            put("opacity", e.opacity); put("visible", e.visible); put("locked", e.locked); put("z", e.zIndex)
            put("group", e.groupId ?: JSONObject.NULL)
        }) } })
    }

    private fun sceneFromJson(o: JSONObject): Scene {
        val elements = o.getJSONArray("elements").objects { e ->
            SceneElement(
                e.getString("id"),
                e.getString("source"),
                ElementTransform(
                    e.getDouble("x").toFloat(),
                    e.getDouble("y").toFloat(),
                    e.getDouble("w").toFloat(),
                    e.getDouble("h").toFloat(),
                    e.getDouble("rotation").toFloat()
                ),
                CropRect(
                    e.getDouble("cl").toFloat(),
                    e.getDouble("ct").toFloat(),
                    e.getDouble("cr").toFloat(),
                    e.getDouble("cb").toFloat()
                ),
                e.getDouble("opacity").toFloat(),
                e.getBoolean("visible"),
                e.getBoolean("locked"),
                e.getInt("z"),
                e.opt("group").nullableString()
            )
        }
        return Scene(
            o.getString("id"),
            o.getString("projectId"),
            o.getString("name"),
            CanvasSize(
                o.getInt("w"),
                o.getInt("h"),
                CanvasOrientation.valueOf(o.getString("orientation"))
            ),
            o.getLong("background"),
            elements
        )
    }

    private fun sourceToJson(s: SceneSource) = JSONObject().apply {
        put("id", s.id); put("type", s.type.name); put("name", s.displayName); put("enabled", s.enabled)
        put("asset", s.assetId ?: JSONObject.NULL); put("text", s.text ?: JSONObject.NULL)
        put("settings", JSONObject().apply { s.settings.forEach { (k, v) -> put(k, v) } })
        s.extensionId?.let { put("extensionId", it) }
        s.typeId?.let { put("typeId", it) }
    }

    private fun sourceFromJson(o: JSONObject): SceneSource {
        val settings = o.getJSONObject("settings")
        return SceneSource(
            o.getString("id"),
            SceneSourceType.valueOf(o.getString("type")),
            o.getString("name"),
            o.getBoolean("enabled"),
            o.opt("asset").nullableString(),
            o.opt("text").nullableString(),
            buildMap { settings.keys().forEach { put(it, settings.getString(it)) } },
            o.opt("extensionId").nullableString(),
            o.opt("typeId").nullableString()
        )
    }

    private fun assetToJson(a: SceneAsset) = JSONObject().apply {
        put("id", a.id); put("path", a.relativePath); put("mime", a.mimeType); put("size", a.byteSize); put("sha", a.sha256)
        put("license", a.license ?: JSONObject.NULL); put("provenance", a.provenance ?: JSONObject.NULL)
    }

    private fun assetFromJson(o: JSONObject) = SceneAsset(
        o.getString("id"),
        o.getString("path"),
        o.getString("mime"),
        o.getLong("size"),
        o.getString("sha"),
        o.opt("license").nullableString(),
        o.opt("provenance").nullableString()
    )

    private fun Any?.nullableString(): String? =
        if (this == null || this === JSONObject.NULL) null else this as String

    private fun <T> JSONArray.objects(decode: (JSONObject) -> T): List<T> =
        (0 until length()).map { decode(getJSONObject(it)) }
}
