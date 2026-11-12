package com.iamahsana.ossstudio.scene.persistence

import com.iamahsana.ossstudio.scene.model.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON persistence codec. Schema v1 remains readable; all subsequent writes
 * use v2 so old projects are upgraded only after a successful decode.
 */
object SceneProjectJsonCodec {
    private const val CURRENT_SCHEMA_VERSION = 3
    private const val MIN_SUPPORTED_SCHEMA_VERSION = 1

    fun encode(p: SceneProject): String = JSONObject().apply {
        put("schemaVersion", CURRENT_SCHEMA_VERSION); put("id",p.id); put("name",p.name)
        put("createdAt",p.createdAtEpochMillis); put("updatedAt",p.updatedAtEpochMillis)
        put("activeSceneId",p.activeSceneId ?: JSONObject.NULL); put("version",p.version)
        put("scenes",JSONArray().apply { p.scenes.forEach { put(sceneToJson(it)) } })
        put("sources",JSONArray().apply { p.sources.forEach { put(sourceToJson(it)) } })
        put("assets",JSONArray().apply { p.assets.forEach { put(assetToJson(it)) } })
    }.toString()

    fun decode(json: String): SceneProject {
        val o=JSONObject(json)
        val schemaVersion = o.getInt("schemaVersion")
        require(schemaVersion in MIN_SUPPORTED_SCHEMA_VERSION..CURRENT_SCHEMA_VERSION) {
            "Unsupported project schema: $schemaVersion"
        }
        val active=o.opt("activeSceneId").nullableString()
        return SceneProject(o.getString("id"),o.getString("name"),o.getLong("createdAt"),o.getLong("updatedAt"),
            active, o.getJSONArray("scenes").objects(::sceneFromJson),
            o.getJSONArray("sources").objects(::sourceFromJson),
            o.getJSONArray("assets").objects(::assetFromJson),o.getInt("version"))
    }

    private fun sceneToJson(s: Scene)=JSONObject().apply {
        put("id",s.id);put("projectId",s.projectId);put("name",s.name)
        put("w",s.canvas.width);put("h",s.canvas.height);put("orientation",s.canvas.orientation.name)
        put("background",s.backgroundArgb)
        put("elements",JSONArray().apply { s.elements.forEach { e -> put(JSONObject().apply {
            put("id",e.id);put("source",e.sourceId);put("x",e.transform.x);put("y",e.transform.y)
            put("w",e.transform.width);put("h",e.transform.height);put("rotation",e.transform.rotationDegrees)
            put("cl",e.crop.left);put("ct",e.crop.top);put("cr",e.crop.right);put("cb",e.crop.bottom)
            put("opacity",e.opacity);put("visible",e.visible);put("locked",e.locked);put("z",e.zIndex)
            put("group",e.groupId ?: JSONObject.NULL)
            put("extension", extensionToJson(e.extensionRequirement))
        }) } })
    }
    private fun sceneFromJson(o: JSONObject): Scene {
        val elements=o.getJSONArray("elements").objects { e ->
            SceneElement(e.getString("id"),e.getString("source"),
                ElementTransform(e.getDouble("x").toFloat(),e.getDouble("y").toFloat(),e.getDouble("w").toFloat(),e.getDouble("h").toFloat(),e.getDouble("rotation").toFloat()),
                CropRect(e.getDouble("cl").toFloat(),e.getDouble("ct").toFloat(),e.getDouble("cr").toFloat(),e.getDouble("cb").toFloat()),
                e.getDouble("opacity").toFloat(),e.getBoolean("visible"),e.getBoolean("locked"),e.getInt("z"),
                e.opt("group").nullableString(), extensionFromJson(e.opt("extension")))
        }
        return Scene(o.getString("id"),o.getString("projectId"),o.getString("name"),
            CanvasSize(o.getInt("w"),o.getInt("h"),CanvasOrientation.valueOf(o.getString("orientation"))),
            o.getLong("background"),elements)
    }
    private fun sourceToJson(s: SceneSource)=JSONObject().apply {
        put("id",s.id);put("type",s.type.name);put("name",s.displayName);put("enabled",s.enabled)
        put("asset",s.assetId ?: JSONObject.NULL);put("text",s.text ?: JSONObject.NULL)
        put("settings",JSONObject().apply { s.settings.forEach { (k,v)->put(k,v) } })
        put("extension", extensionToJson(s.extensionRequirement))
    }
    private fun sourceFromJson(o: JSONObject): SceneSource {
        val settings=o.getJSONObject("settings")
        return SceneSource(o.getString("id"),SceneSourceType.valueOf(o.getString("type")),o.getString("name"),
            o.getBoolean("enabled"),o.opt("asset").nullableString(),o.opt("text").nullableString(),
            buildMap { settings.keys().forEach { put(it,settings.getString(it)) } },
            extensionFromJson(o.opt("extension")))
    }
    private fun assetToJson(a: SceneAsset)=JSONObject().apply {
        put("id",a.id);put("path",a.relativePath);put("mime",a.mimeType);put("size",a.byteSize);put("sha",a.sha256)
        put("license",a.license ?: JSONObject.NULL);put("provenance",a.provenance ?: JSONObject.NULL)
    }
    private fun assetFromJson(o: JSONObject)=SceneAsset(o.getString("id"),o.getString("path"),o.getString("mime"),
        o.getLong("size"),o.getString("sha"),o.opt("license").nullableString(),o.opt("provenance").nullableString())
    private fun extensionToJson(requirement: ExtensionRequirement?): Any =
        requirement?.let {
            JSONObject().apply {
                put("id", it.extensionId)
                put("version", it.version)
                put("elementTypeId", it.elementTypeId)
            }
        } ?: JSONObject.NULL

    private fun extensionFromJson(value: Any?): ExtensionRequirement? {
        val json = value.nullableObject() ?: return null
        return ExtensionRequirement(json.getString("id"), json.getString("version"), json.getString("elementTypeId"))
    }

    private fun Any?.nullableObject(): JSONObject? =
        if (this == null || this === JSONObject.NULL) null else this as JSONObject

    private fun Any?.nullableString(): String? = if (this == null || this === JSONObject.NULL) null else this as String
    private fun <T> JSONArray.objects(decode:(JSONObject)->T): List<T> = (0 until length()).map { decode(getJSONObject(it)) }
}
