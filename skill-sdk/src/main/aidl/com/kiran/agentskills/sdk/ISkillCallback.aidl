package com.kiran.agentskills.sdk;

import android.os.Bundle;

/** Receives the outcome of one ISkillProvider.invoke() call. */
oneway interface ISkillCallback {
    /** A short human-readable status while the tool runs, e.g. "Compiling shader". */
    void onProgress(String requestId, String status);

    /**
     * The tool's result as JSON (see SkillProtocol). [artifacts] maps artifact ids listed in the
     * JSON to PendingIntents that open the provider's own UI.
     */
    void onResult(String requestId, String resultJson, in Bundle artifacts);
}
