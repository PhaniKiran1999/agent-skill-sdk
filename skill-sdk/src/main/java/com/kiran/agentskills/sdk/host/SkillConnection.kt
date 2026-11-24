package com.kiran.agentskills.sdk.host

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import com.kiran.agentskills.sdk.ISkillCallback
import com.kiran.agentskills.sdk.ISkillProvider
import com.kiran.agentskills.sdk.SkillContract
import com.kiran.agentskills.sdk.SkillDescriptor
import com.kiran.agentskills.sdk.SkillProtocol
import com.kiran.agentskills.sdk.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A lazily bound connection to one skill provider. The connection binds on first use, reconnects
 * after the provider dies, and unbinds after [idleTimeout] without calls.
 */
class SkillConnection(
    context: Context,
    val component: ComponentName,
    private val idleTimeout: Duration = 60.seconds,
) : AutoCloseable {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var provider: ISkillProvider? = null
    private var connection: ServiceConnection? = null
    private var idleJob: Job? = null

    /** Fetches and parses the provider's descriptor. */
    suspend fun describe(): SkillDescriptor {
        val p = ensureBound()
        val json = withContext(Dispatchers.IO) { p.describe() }
        scheduleIdleUnbind()
        return SkillProtocol.decodeDescriptor(json)
    }

    /**
     * Calls [tool] and suspends until the provider answers. Cancelling the coroutine cancels the
     * call on the provider too. If the provider dies or times out, the result is an error.
     */
    suspend fun invoke(
        tool: String,
        args: JSONObject,
        timeout: Duration = 60.seconds,
        onProgress: (String) -> Unit = {},
    ): ToolResult {
        val p = ensureBound()
        val requestId = UUID.randomUUID().toString()
        return try {
            withTimeout(timeout) {
                suspendCancellableCoroutine { cont ->
                    val callback = object : ISkillCallback.Stub() {
                        override fun onProgress(requestId: String, status: String) = onProgress(status)

                        override fun onResult(requestId: String, resultJson: String, artifacts: Bundle?) {
                            artifacts?.classLoader = PendingIntent::class.java.classLoader
                            val result = SkillProtocol.decodeResult(resultJson) { id -> artifacts?.pendingIntent(id) }
                            if (cont.isActive) cont.resume(result)
                        }
                    }
                    cont.invokeOnCancellation { runCatching { p.cancel(requestId) } }
                    try {
                        p.invoke(requestId, tool, args.toString(), callback)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(ToolResult.error("Skill provider unavailable: ${e.message}"))
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            runCatching { p.cancel(requestId) }
            ToolResult.error("The ${component.packageName} skill timed out after $timeout.")
        } finally {
            scheduleIdleUnbind()
        }
    }

    private suspend fun ensureBound(): ISkillProvider = mutex.withLock {
        provider?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        unbindLocked()
        val ready = CompletableDeferred<ISkillProvider?>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val p = ISkillProvider.Stub.asInterface(binder)
                provider = p
                ready.complete(p)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                provider = null // Android rebinds automatically; ensureBound() checks liveness.
            }

            override fun onBindingDied(name: ComponentName) {
                provider = null
                ready.complete(null)
            }

            override fun onNullBinding(name: ComponentName) {
                ready.complete(null)
            }
        }
        val intent = Intent(SkillContract.ACTION_SKILL_PROVIDER).setComponent(component)
        val bound = appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        if (!bound) {
            runCatching { appContext.unbindService(conn) }
            error("Cannot bind to ${component.flattenToShortString()}")
        }
        connection = conn
        withTimeout(BIND_TIMEOUT) { ready.await() } ?: run {
            unbindLocked()
            error("${component.flattenToShortString()} refused the connection")
        }
    }

    private fun scheduleIdleUnbind() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(idleTimeout)
            mutex.withLock { unbindLocked() }
        }
    }

    private fun unbindLocked() {
        connection?.let { runCatching { appContext.unbindService(it) } }
        connection = null
        provider = null
    }

    override fun close() {
        idleJob?.cancel()
        runCatching { if (mutex.tryLock()) try { unbindLocked() } finally { mutex.unlock() } }
    }

    private fun Bundle.pendingIntent(key: String): PendingIntent? =
        if (Build.VERSION.SDK_INT >= 33) getParcelable(key, PendingIntent::class.java)
        else @Suppress("DEPRECATION") getParcelable(key)

    private companion object {
        val BIND_TIMEOUT = 10.seconds
    }
}
