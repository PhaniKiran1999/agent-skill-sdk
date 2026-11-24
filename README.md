# Agent Skill SDK

[![](https://jitpack.io/v/PhaniKiran1999/agent-skill-sdk.svg)](https://jitpack.io/#PhaniKiran1999/agent-skill-sdk)

Lets an on-device agent app (the **host**, e.g. PAgent) use tools that live in **other apps**
(**skill providers**, e.g. the Shaderscape wallpaper app). It works like MCP on Android, over Binder:

- providers describe their tools with JSON Schemas and short prompt instructions;
- the host discovers providers at runtime and offers their tools to its LLM;
- tools return JSON for the model, plus **artifacts** (PendingIntents into the provider's own UI)
  for the user.

```
 Host app (agent + LLM)                Skill provider app(s)
 ┌──────────────────────┐   Binder    ┌────────────────────────────┐
 │ SkillDirectory ──────┼── discover ─▶ <service> + <meta-data>      │
 │ SkillConnection ─────┼── describe ─▶ SkillProviderService         │
 │                      │◀─ invoke ───┤   onInvoke(ToolCall)         │
 │ chat card ──tap──────┼─ PendingIntent ─▶ provider's own UI         │
 └──────────────────────┘             └────────────────────────────┘
```

## Install

The SDK is published through [JitPack](https://jitpack.io/#PhaniKiran1999/agent-skill-sdk).

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.PhaniKiran1999.*") }
        }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.PhaniKiran1999:agent-skill-sdk:0.1.0")
}
```

With a version catalog:
```toml
[versions]
agentSkillSdk = "0.1.0"
[libraries]
agent-skill-sdk = { group = "com.github.PhaniKiran1999", name = "agent-skill-sdk", version.ref = "agentSkillSdk" }
```

### Developing the SDK and an app together
Clone this repo next to the app and add this to the app's `local.properties` (it's per machine and never committed):
```properties
useLocalSkillSdk=true
```
The app's `settings.gradle.kts` then uses `includeBuild("../agent-skill-sdk")` and substitutes the published module with
this source tree, so SDK edits show up in the app straight away. PAgent and Shaderscape already have this switch; for a new app, copy the
block at the end of their `settings.gradle.kts`.

## Releasing a new version
1. Bump `VERSION_NAME` in `gradle.properties`, then commit.
2. Tag the same version and push it: `git tag 0.2.0 && git push origin main 0.2.0`.
3. Open `https://jitpack.io/#PhaniKiran1999/agent-skill-sdk` (or request the POM). JitPack builds the tag on first request.
4. Bump `agentSkillSdk` in each app's version catalog.

Changing the AIDL interface: only **append** methods. Hosts and providers update separately, so the transaction order of existing
methods must stay the same.

## Add a new skill app in 3 steps

1. **Depend on the SDK** (see [Install](#install)).
2. **Implement the service.**
   ```kotlin
   class NotesSkillService : SkillProviderService() {
       override fun descriptor() = SkillDescriptor(
           id = "notes", title = "Notes", summary = "Saves and finds personal notes.",
           keywords = listOf("note", "remember"),
           instructions = "Use save_note when the user asks you to remember something.",
           tools = listOf(ToolSpec("save_note", "Saves a note.", ParamsSchema.build { string("text", "The note") })),
       )
       override suspend fun onInvoke(call: ToolCall): ToolResult = when (call.tool) {
           "save_note" -> { /* … */ ToolResult.ok(JSONObject().put("ok", true)) }
           else -> ToolResult.error("Unknown tool")
       }
   }
   ```
3. **Declare it** in `AndroidManifest.xml`:
   ```xml
   <service android:name=".NotesSkillService" android:exported="true"
       android:permission="com.kiran.agentskills.permission.SKILL">
       <intent-filter><action android:name="com.kiran.agentskills.action.SKILL_PROVIDER" /></intent-filter>
       <meta-data android:name="com.kiran.agentskills.skill.ID" android:value="notes" />
       <meta-data android:name="com.kiran.agentskills.skill.TITLE" android:value="Notes" />
       <meta-data android:name="com.kiran.agentskills.skill.SUMMARY" android:value="Saves and finds personal notes." />
       <meta-data android:name="com.kiran.agentskills.skill.KEYWORDS" android:value="note,remember" />
   </service>
   ```

Install the app and the host picks it up on the next message. The host needs no code change.

## Host setup
```xml
<uses-permission android:name="com.kiran.agentskills.permission.SKILL" />
<queries><intent><action android:name="com.kiran.agentskills.action.SKILL_PROVIDER" /></intent></queries>
```
Then:
- `SkillDirectory(context).discover()` lists providers from their manifests, without binding.
- `SkillConnection(context, component)` binds lazily and unbinds when idle. It provides `describe()` and `invoke(tool, args)`.

PAgent wraps these in an ADK `Toolset`, which also routes which skills reach the prompt.

## Protocol (v1)

| Call | Payload |
|---|---|
| `describe()` | `{protocol, id, title, summary, keywords[], instructions, tools[{name, description, parameters (JSON Schema), risk}]}` |
| `invoke(requestId, tool, argsJson, callback)` | `callback.onProgress(status)*`, then `callback.onResult(resultJson, Bundle artifacts)` |
| `cancel(requestId)` | best-effort |
| `resolveArtifact(requestId, artifactId, callback)` | `{status: pending\|saved\|gone\|unknown, label}` plus a fresh artifact while it can still be opened |

- `resultJson` is the tool's data plus `_artifacts: [{id, kind, title, subtitle}]`.
- Each artifact id maps to a `PendingIntent` in the `Bundle`.
- Errors are `{"error": "..."}`.

## Design rules for providers
- **Return drafts, not side effects.** Let the user decide in your own UI, which is the artifact. Tools with `risk = SIDE_EFFECT` are blocked by the host until it has a confirmation flow.
- **Keep instructions short.** On-device models have small contexts, and instructions are added only while the skill is selected.
- **Make errors fixable.** Return concrete messages the model can act on, as the wallpaper skill does with compiler errors and line numbers.
- **Keep artifact ids stable and answer `onResolveArtifact`.** Hosts store artifact ids in chat history and ask later what became of them. A PendingIntent dies with the host process, but the id doesn't. Report whether it's still pending, was saved or is gone, and return a fresh `Artifact` while it can be opened. The default returns null, so cards show as unavailable.

## Security
- Provider services require the **signature** permission `com.kiran.agentskills.permission.SKILL`, which this library's manifest defines.
- Only apps signed with the same key can bind, and `SkillDirectory` ignores services that aren't guarded by it.
- Opening third-party skills would need a different trust model (allow-list, user consent per skill). That's on the roadmap.

More: [design-docs/architecture.md](design-docs/architecture.md), [design-docs/sequence-flows.md](design-docs/sequence-flows.md).
