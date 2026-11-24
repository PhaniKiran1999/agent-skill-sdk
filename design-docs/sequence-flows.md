# Sequence Flows

## 1. Discovery and routing (every model request)

```mermaid
sequenceDiagram
    autonumber
    participant ADK as ADK LlmAgent
    participant TS as SkillToolset
    participant Hub as SkillHub
    participant PM as PackageManager
    participant P as Provider service
    participant R as SkillRouter

    ADK->>TS: getTools(readonlyContext)
    TS->>Hub: installed()
    Hub->>PM: queryIntentServices(SKILL_PROVIDER, GET_META_DATA)
    PM-->>Hub: services guarded by SKILL permission + meta-data
    alt descriptor not cached for this app version
        Hub->>P: bind + describe()
        P-->>Hub: SkillDescriptor JSON (tools, schemas, instructions)
    end
    TS->>R: select(user text, candidates, skills used in session)
    R-->>TS: relevant skills
    TS-->>ADK: RemoteSkillTool per tool (JSON Schema → ADK Schema)
    ADK->>TS: processLlmRequest()
    TS-->>ADK: + instructions of the selected skills only
```

## 2. Create → validate → repair → preview → decide

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant Chat as PAgent chat
    participant LLM as LLM (ADK)
    participant Tool as RemoteSkillTool
    participant W as WallpaperSkillService
    participant V as ShaderValidator (offscreen GPU)
    participant St as CompositionStore
    participant Prev as PreviewActivity

    User->>Chat: "make me an interactive neon rain wallpaper"
    Chat->>LLM: run
    LLM->>Tool: create_shader_wallpaper(title, glsl)
    Tool->>W: invoke (Binder)
    W->>W: ShaderTidier (fences, #version, precision…)
    W->>V: validate(code)
    V-->>W: Invalid(["Line 3: 'hash' : no matching overloaded function"])
    W-->>Tool: {ok:false, errors, attempts_left: 2, glsl_after_auto_fixes}
    Tool-->>LLM: tool response
    LLM->>Tool: create_shader_wallpaper(title, fixed glsl)
    Tool->>W: invoke
    W->>V: validate(code)
    V-->>W: Valid(avgFrameMs, warnings)
    W->>St: saveDraft(composition with LayerSource.Shader)
    W-->>Tool: {ok:true, draft_id} + Artifact(PendingIntent → PreviewActivity)
    Tool-->>LLM: {ok:true, draft_id, artifacts:[{id,title}]}
    Note over Chat: AdkAgentRuntime emits AgentEvent.Artifact → card under the reply
    LLM-->>Chat: "Neon Rain is ready, tap the preview to try it."
    User->>Chat: tap card
    Chat->>Prev: PendingIntent.send (foreground BAL opt-in)
    User->>Prev: touch to try, then Save / Set as wallpaper / Edit layers / Discard
    Prev->>St: promoteDraft / deleteDraft
    User->>Chat: "make the ripples bigger"
    LLM->>Tool: revise_shader_wallpaper(draft_id, glsl)
    Tool->>W: invoke
    alt draft still pending
        W->>St: update the same draft
    else draft was saved or discarded
        W->>St: saveDraft(new draft)
        Note over W: The result tells the model a new draft was made.
    end
    W-->>Tool: {ok:true, draft_id} + Artifact
```

## 3. Install a new skill app

```mermaid
sequenceDiagram
    autonumber
    actor Dev
    participant App as New skill app
    participant Host as PAgent

    Dev->>App: depend on com.github.PhaniKiran1999:agent-skill-sdk (JitPack), subclass SkillProviderService, declare <service> + meta-data
    Dev->>App: sign with the same key, install
    Host->>Host: next message: SkillDirectory finds it, describe(), router may select it
    Note over Host: No host code change or release needed
```

## 4. Reopening an old chat (artifact state)

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant Chat as PAgent chat
    participant Store as AdkSessionStore
    participant Hub as SkillHub
    participant P as Provider (e.g. WallpaperSkillService)

    User->>Chat: open an old chat (or return to the app)
    Chat->>Store: load events
    Store-->>Chat: messages with ArtifactRef(id, title, kind, skill)
    Note over Chat: Cards show "Checking…"
    loop each card
        Chat->>Hub: resolve(ref)
        Hub->>P: resolveArtifact(id)
        P-->>Hub: ArtifactState(pending | saved | gone, label, fresh Artifact?)
        Hub-->>Chat: state
    end
    Note over Chat: pending → "not saved yet", saved → "in My wallpapers",<br/>gone → "discarded or expired", no answer → "unavailable"
    User->>Chat: tap a pending or saved card
    Chat->>P: send the fresh PendingIntent → PreviewActivity
```
