package com.kiran.agentskills.sdk

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** One tool call as a provider sees it. */
class ToolCall(
    val requestId: String,
    val tool: String,
    val args: JSONObject,
    /** Package of the calling agent app. */
    val callerPackage: String?,
    private val progress: (String) -> Unit,
) {
    /** Reports a short status to the agent while the tool runs. */
    fun progress(status: String) = progress.invoke(status)
}

/**
 * Base class for a skill provider: subclass it, implement [descriptor] and [onInvoke], and declare
 * the service in your manifest:
 * ```
 * <service android:name=".MySkillService"
 *     android:exported="true"
 *     android:permission="com.kiran.agentskills.permission.SKILL">
 *     <intent-filter><action android:name="com.kiran.agentskills.action.SKILL_PROVIDER" /></intent-filter>
 *     <meta-data android:name="com.kiran.agentskills.skill.ID" android:value="my_skill" />
 *     <meta-data android:name="com.kiran.agentskills.skill.TITLE" android:value="My skill" />
 *     <meta-data android:name="com.kiran.agentskills.skill.SUMMARY" android:value="What it does" />
 *     <meta-data android:name="com.kiran.agentskills.skill.KEYWORDS" android:value="word,other" />
 * </service>
 * ```
 * Calls run as coroutines on [Dispatchers.Default]. A thrown exception becomes an `error` result,
 * and `cancel()` from the host cancels the coroutine.
 */
abstract class SkillProviderService : Service() {

    protected val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = ConcurrentHashMap<String, Job>()

    abstract fun descriptor(): SkillDescriptor

    abstract suspend fun onInvoke(call: ToolCall): ToolResult

    /**
     * What became of [artifactId], an artifact one of your tools returned earlier, or null if
     * you don't know it. Return a fresh [Artifact] (with its PendingIntent) while it can still be
     * opened. The default knows nothing, so artifacts from old chats show as unavailable.
     */
    open suspend fun onResolveArtifact(artifactId: String): ArtifactState? = null

    private val binder = object : ISkillProvider.Stub() {
        override fun describe(): String = SkillProtocol.encodeDescriptor(descriptor())

        override fun invoke(requestId: String, tool: String, argsJson: String, callback: ISkillCallback) {
            val caller = packageManager.getNameForUid(Binder.getCallingUid())
            val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
            val call = ToolCall(requestId, tool, args, caller) { status ->
                runCatching { callback.onProgress(requestId, status) }
            }
            running[requestId] = serviceScope.launch {
                val result = try {
                    if (descriptor().tools.none { it.name == tool }) ToolResult.error("Unknown tool: $tool")
                    else onInvoke(call)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Tool $tool failed", e)
                    ToolResult.error(e.message ?: e::class.java.simpleName)
                } finally {
                    running.remove(requestId)
                }
                val extras = Bundle().apply { result.artifacts.forEach { a -> a.action?.let { putParcelable(a.id, it) } } }
                try {
                    callback.onResult(requestId, SkillProtocol.encodeResult(result), extras)
                } catch (e: RemoteException) {
                    Log.w(TAG, "Agent went away before receiving $tool's result")
                }
            }
        }

        override fun cancel(requestId: String) {
            running.remove(requestId)?.cancel()
        }

        override fun resolveArtifact(requestId: String, artifactId: String, callback: ISkillCallback) {
            serviceScope.launch {
                val state = try {
                    onResolveArtifact(artifactId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Resolving $artifactId failed", e)
                    null
                }
                val result = SkillProtocol.encodeArtifactState(state)
                val extras = Bundle().apply { state?.artifact?.action?.let { putParcelable(state.artifact.id, it) } }
                try {
                    callback.onResult(requestId, result, extras)
                } catch (e: RemoteException) {
                    Log.w(TAG, "Agent went away before receiving the state of $artifactId")
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "SkillProvider"
    }
}
