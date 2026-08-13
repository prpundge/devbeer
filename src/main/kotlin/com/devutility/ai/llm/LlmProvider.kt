package com.devutility.ai.llm

import com.devutility.ai.model.LlmRequest
import com.devutility.ai.model.LlmResult

/**
 * A source of completions.
 *
 * Feature code depends only on this interface, never on a concrete provider, so a new backend
 * can be added without touching the context engine, the prompts or the UI (§3).
 */
interface LlmProvider {

    val name: String

    /**
     * Performs the completion. Always called from a background thread.
     *
     * [cancelled] is polled during the request so a user-cancelled progress indicator stops
     * the work instead of leaving it running.
     */
    fun complete(request: LlmRequest, cancelled: () -> Boolean = { false }): LlmResult

    /** Cheap reachability check. Must not throw. */
    fun isAvailable(): Boolean
}
