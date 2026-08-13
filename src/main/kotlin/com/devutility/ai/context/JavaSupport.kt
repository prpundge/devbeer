package com.devutility.ai.context

import com.devutility.ai.model.FlowNode
import com.intellij.openapi.project.Project

/**
 * Optional Java/Spring enrichment.
 *
 * DevBeer declares `com.intellij.modules.java` as an **optional** dependency so the compare,
 * folder, JSON and string modules work in WebStorm and PyCharm. The AI layer must hold to
 * that: repository search works anywhere, and Java PSI only sharpens it where present.
 *
 * The isolation has to be at *class* granularity. A class whose method signatures mention
 * `PsiClass` cannot even be loaded when the Java module is absent — it throws
 * `NoClassDefFoundError` at link time, not at call time. So every Java PSI reference lives in
 * [JavaSymbolEnricher], which is instantiated reflectively and only after [isJavaAvailable]
 * confirms the classes are there.
 */
interface SymbolEnricher {

    /** Precise symbol matches for [terms]: classes, methods, Spring components. */
    fun enrich(project: Project, terms: List<String>, limit: Int): List<ContextCandidate>

    /** Call flow for a method named [methodName], resolved from the project model. */
    fun resolveFlow(project: Project, methodName: String, limit: Int): List<FlowNode>

    val available: Boolean

    companion object {

        fun isJavaAvailable(): Boolean = try {
            Class.forName(
                "com.intellij.psi.JavaPsiFacade",
                false,
                SymbolEnricher::class.java.classLoader,
            )
            true
        } catch (e: Throwable) {
            false
        }

        /** Cached: the answer cannot change within a session. */
        private val instance: SymbolEnricher by lazy {
            if (!isJavaAvailable()) {
                NoopSymbolEnricher
            } else {
                try {
                    Class.forName("com.devutility.ai.context.JavaSymbolEnricher")
                        .getDeclaredConstructor()
                        .newInstance() as SymbolEnricher
                } catch (e: Throwable) {
                    // Any linkage problem degrades to text-only search rather than breaking
                    // the feature — §38, the AI layer must never take the plugin down.
                    NoopSymbolEnricher
                }
            }
        }

        fun get(): SymbolEnricher = instance
    }
}

/** Used where Java support is absent. Text search still works; it is just less precise. */
object NoopSymbolEnricher : SymbolEnricher {
    override val available: Boolean = false
    override fun enrich(project: Project, terms: List<String>, limit: Int): List<ContextCandidate> = emptyList()
    override fun resolveFlow(project: Project, methodName: String, limit: Int): List<FlowNode> = emptyList()
}
