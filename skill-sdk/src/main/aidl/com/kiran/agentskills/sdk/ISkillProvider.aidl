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
}
