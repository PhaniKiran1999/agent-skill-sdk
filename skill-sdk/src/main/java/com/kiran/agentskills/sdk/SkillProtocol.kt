package com.kiran.agentskills.sdk

import android.app.PendingIntent
import org.json.JSONArray
import org.json.JSONObject

/** Names shared by agent hosts and skill providers. */
object SkillContract {
    /** Bumped on incompatible wire changes. Hosts skip providers that report another major. */
    const val PROTOCOL_VERSION = 1

    /** Intent action a provider service declares in its intent-filter. */
    const val ACTION_SKILL_PROVIDER = "com.kiran.agentskills.action.SKILL_PROVIDER"

    /** Signature permission that guards provider services (defined in this library's manifest). */
    const val PERMISSION = "com.kiran.agentskills.permission.SKILL"

    /**
     * `<meta-data>` keys on the provider `<service>`, so hosts can route without binding.
     * Values are plain strings, and keywords are comma-separated.
     */
    const val META_ID = "com.kiran.agentskills.skill.ID"
    const val META_TITLE = "com.kiran.agentskills.skill.TITLE"
    const val META_SUMMARY = "com.kiran.agentskills.skill.SUMMARY"
    const val META_KEYWORDS = "com.kiran.agentskills.skill.KEYWORDS"

    internal const val KEY_ARTIFACTS = "_artifacts"
    internal const val KEY_ERROR = "error"
}

/** How much a tool can affect the world. Hosts ask the user before running SIDE_EFFECT tools. */
enum class ToolRisk { READ_ONLY, SIDE_EFFECT }

/** One tool: [parameters] is a JSON Schema object (build it with [ParamsSchema]). */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JSONObject = ParamsSchema.build {},
    val risk: ToolRisk = ToolRisk.READ_ONLY,
)

/**
 * What a provider tells the agent about itself. [instructions] is prompt text that hosts add to
 * the model's context only while this skill is selected, so keep it short.
 */
data class SkillDescriptor(
    val id: String,
    val title: String,
    val summary: String,
    val instructions: String,
    val tools: List<ToolSpec>,
    val keywords: List<String> = emptyList(),
    val protocolVersion: Int = SkillContract.PROTOCOL_VERSION,
)

/** Something the user can open, e.g. a preview. [action] launches the provider's UI. */
data class Artifact(
    val id: String,
    val kind: String,
    val title: String,
    val subtitle: String = "",
    val action: PendingIntent? = null,
)

/**
 * A tool's outcome. [data] goes back to the model as JSON. [artifacts] reach the host app (and its
 * UI) and are listed in the JSON by id, title and kind, never as the PendingIntent itself.
 */
data class ToolResult(
    val data: JSONObject,
    val artifacts: List<Artifact> = emptyList(),
) {
    val error: String? get() = data.optString(SkillContract.KEY_ERROR).ifEmpty { null }

    companion object {
        fun ok(data: JSONObject = JSONObject(), artifacts: List<Artifact> = emptyList()) = ToolResult(data, artifacts)
        fun error(message: String) = ToolResult(JSONObject().put(SkillContract.KEY_ERROR, message))
    }
}

/** JSON encoding of the protocol. Pure Kotlin + org.json, so it's unit-testable. */
object SkillProtocol {

    fun encodeDescriptor(d: SkillDescriptor): String = JSONObject()
        .put("protocol", d.protocolVersion)
        .put("id", d.id)
        .put("title", d.title)
        .put("summary", d.summary)
        .put("instructions", d.instructions)
        .put("keywords", JSONArray(d.keywords))
        .put("tools", JSONArray().apply {
            d.tools.forEach {
                put(
                    JSONObject()
                        .put("name", it.name)
                        .put("description", it.description)
                        .put("parameters", it.parameters)
                        .put("risk", it.risk.name.lowercase()),
                )
            }
        })
        .toString()

    fun decodeDescriptor(json: String): SkillDescriptor {
        val o = JSONObject(json)
        val tools = o.optJSONArray("tools") ?: JSONArray()
        return SkillDescriptor(
            protocolVersion = o.optInt("protocol", 0),
            id = o.getString("id"),
            title = o.optString("title", o.getString("id")),
            summary = o.optString("summary"),
            instructions = o.optString("instructions"),
            keywords = o.optJSONArray("keywords").strings(),
            tools = (0 until tools.length()).map { i ->
                val t = tools.getJSONObject(i)
                ToolSpec(
                    name = t.getString("name"),
                    description = t.optString("description"),
                    parameters = t.optJSONObject("parameters") ?: ParamsSchema.build {},
                    risk = if (t.optString("risk") == "side_effect") ToolRisk.SIDE_EFFECT else ToolRisk.READ_ONLY,
                )
            },
        )
    }

    /** The JSON sent over the wire: [ToolResult.data] plus an `_artifacts` summary list. */
    fun encodeResult(r: ToolResult): String {
        val o = JSONObject(r.data.toString())
        if (r.artifacts.isNotEmpty()) {
            o.put(SkillContract.KEY_ARTIFACTS, JSONArray().apply {
                r.artifacts.forEach {
                    put(JSONObject().put("id", it.id).put("kind", it.kind).put("title", it.title).put("subtitle", it.subtitle))
                }
            })
        }
        return o.toString()
    }

    /** Rebuilds a result, attaching PendingIntents via [actionFor] (from the callback Bundle). */
    fun decodeResult(json: String, actionFor: (String) -> PendingIntent?): ToolResult {
        val o = JSONObject(json)
        val arr = o.optJSONArray(SkillContract.KEY_ARTIFACTS)
        o.remove(SkillContract.KEY_ARTIFACTS)
        val artifacts = if (arr == null) emptyList() else (0 until arr.length()).map { i ->
            val a = arr.getJSONObject(i)
            val id = a.getString("id")
            Artifact(id, a.optString("kind"), a.optString("title"), a.optString("subtitle"), actionFor(id))
        }
        return ToolResult(o, artifacts)
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }
}

/**
 * Small builder for a tool's JSON Schema parameters object:
 * ```
 * ParamsSchema.build {
 *     string("title", "Short name for the wallpaper")
 *     string("glsl", "Fragment shader body")
 *     string("mood", "Optional mood", required = false)
 * }
 * ```
 */
class ParamsSchema private constructor() {
    private val properties = JSONObject()
    private val required = JSONArray()

    fun string(name: String, description: String, required: Boolean = true, enum: List<String>? = null) =
        add(name, JSONObject().put("type", "string").put("description", description).apply {
            if (enum != null) put("enum", JSONArray(enum))
        }, required)

    fun integer(name: String, description: String, required: Boolean = true) =
        add(name, JSONObject().put("type", "integer").put("description", description), required)

    fun number(name: String, description: String, required: Boolean = true) =
        add(name, JSONObject().put("type", "number").put("description", description), required)

    fun boolean(name: String, description: String, required: Boolean = true) =
        add(name, JSONObject().put("type", "boolean").put("description", description), required)

    private fun add(name: String, schema: JSONObject, isRequired: Boolean) {
        properties.put(name, schema)
        if (isRequired) required.put(name)
    }

    companion object {
        fun build(block: ParamsSchema.() -> Unit): JSONObject {
            val b = ParamsSchema().apply(block)
            return JSONObject().put("type", "object").put("properties", b.properties).apply {
                if (b.required.length() > 0) put("required", b.required)
            }
        }
    }
}
