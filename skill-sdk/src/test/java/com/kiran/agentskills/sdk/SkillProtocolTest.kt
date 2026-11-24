package com.kiran.agentskills.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillProtocolTest {

    private val descriptor = SkillDescriptor(
        id = "wallpapers",
        title = "Wallpapers",
        summary = "Make live wallpapers",
        instructions = "Write GLSL ES 1.00.",
        keywords = listOf("wallpaper", "shader"),
        tools = listOf(
            ToolSpec(
                name = "create_shader_wallpaper",
                description = "Validate and preview a shader",
                parameters = ParamsSchema.build {
                    string("title", "Name")
                    string("glsl", "Body")
                    string("mood", "Optional", required = false, enum = listOf("calm", "wild"))
                },
            ),
            ToolSpec("set_it", "Applies it", risk = ToolRisk.SIDE_EFFECT),
        ),
    )

    @Test
    fun descriptor_roundTrip() {
        val back = SkillProtocol.decodeDescriptor(SkillProtocol.encodeDescriptor(descriptor))
        assertEquals(descriptor.id, back.id)
        assertEquals(descriptor.title, back.title)
        assertEquals(descriptor.summary, back.summary)
        assertEquals(descriptor.instructions, back.instructions)
        assertEquals(descriptor.keywords, back.keywords)
        assertEquals(SkillContract.PROTOCOL_VERSION, back.protocolVersion)
        assertEquals(listOf("create_shader_wallpaper", "set_it"), back.tools.map { it.name })
        assertEquals(ToolRisk.SIDE_EFFECT, back.tools[1].risk)
        assertEquals(descriptor.tools[0].parameters.toString(), back.tools[0].parameters.toString())
    }

    @Test
    fun paramsSchema_shape() {
        val p = descriptor.tools[0].parameters
        assertEquals("object", p.getString("type"))
        assertEquals("string", p.getJSONObject("properties").getJSONObject("glsl").getString("type"))
        val required = p.getJSONArray("required")
        assertEquals(2, required.length())
        assertEquals("calm", p.getJSONObject("properties").getJSONObject("mood").getJSONArray("enum").getString(0))
        // No params: an object with no "required".
        assertTrue(!ParamsSchema.build {}.has("required"))
    }

    @Test
    fun result_roundTrip_keepsArtifactsOutOfData() {
        val r = ToolResult.ok(
            org.json.JSONObject().put("ok", true).put("draft_id", "d1"),
            listOf(Artifact("a1", "preview", "Neon rain", "Tap to preview")),
        )
        val wire = SkillProtocol.encodeResult(r)
        assertTrue(wire.contains("_artifacts"))
        val back = SkillProtocol.decodeResult(wire) { null }
        assertEquals("d1", back.data.getString("draft_id"))
        assertTrue(!back.data.has("_artifacts"))
        assertEquals(listOf("a1"), back.artifacts.map { it.id })
        assertEquals("Neon rain", back.artifacts[0].title)
        assertNull(back.error)
    }

    @Test
    fun error_result() {
        val back = SkillProtocol.decodeResult(SkillProtocol.encodeResult(ToolResult.error("boom"))) { null }
        assertEquals("boom", back.error)
        assertTrue(back.artifacts.isEmpty())
    }

    @Test
    fun decode_toleratesMissingOptionalFields() {
        val d = SkillProtocol.decodeDescriptor("""{"id":"x"}""")
        assertEquals("x", d.title)
        assertEquals(0, d.protocolVersion)
        assertTrue(d.tools.isEmpty())
    }

    @Test
    fun artifactState_roundTrip() {
        val saved = ArtifactState(ArtifactState.Status.SAVED, "Saved in My wallpapers", Artifact("preview:d1", "wallpaper_preview", "Rain"))
        val back = SkillProtocol.decodeArtifactState(SkillProtocol.encodeArtifactState(saved)) { null }!!
        assertEquals(ArtifactState.Status.SAVED, back.status)
        assertEquals("Saved in My wallpapers", back.label)
        assertEquals("preview:d1", back.artifact?.id)
        assertEquals("Rain", back.artifact?.title)

        val gone = SkillProtocol.decodeArtifactState(SkillProtocol.encodeArtifactState(ArtifactState(ArtifactState.Status.GONE, "Discarded"))) { null }!!
        assertEquals(ArtifactState.Status.GONE, gone.status)
        assertNull(gone.artifact)

        // A provider that doesn't know the artifact.
        assertNull(SkillProtocol.decodeArtifactState(SkillProtocol.encodeArtifactState(null)) { null })
    }
}
