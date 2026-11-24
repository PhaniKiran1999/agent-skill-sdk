package com.kiran.agentskills.sdk;

import com.kiran.agentskills.sdk.ISkillCallback;

/** A skill provider: an app that offers tools to an on-device agent. */
interface ISkillProvider {
    /** The provider's SkillDescriptor as JSON: protocol version, instructions and tool schemas. */
    String describe();

    /** Runs [tool] with JSON [argsJson]. The result arrives on [callback]. */
    oneway void invoke(String requestId, String tool, String argsJson, ISkillCallback callback);

    /** Best-effort cancellation of a running invoke(). */
    oneway void cancel(String requestId);

    /**
     * Reports what became of an artifact a tool returned earlier (e.g. a draft the user later
     * saved or discarded), with a fresh PendingIntent when it can still be opened. Lets a host
     * show artifacts from old conversations. Added last so existing transaction codes are unchanged.
     */
    oneway void resolveArtifact(String requestId, String artifactId, ISkillCallback callback);
}
