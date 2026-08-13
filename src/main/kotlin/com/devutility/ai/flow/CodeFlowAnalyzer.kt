package com.devutility.ai.flow

import com.devutility.ai.context.SymbolEnricher
import com.devutility.ai.error.SourceLocationResolver
import com.devutility.ai.model.AiResultType
import com.devutility.ai.model.FlowNode
import com.devutility.ai.prompt.PromptBuilder
import com.devutility.ai.settings.ContextBudget
import com.intellij.openapi.project.Project

/**
 * Builds a readable call flow for one method.
 *
 * The division of labour is the point (§14): **the structure comes from IntelliJ**, by walking
 * resolved call expressions, and the model only narrates it. That way every node in the flow
 * is a place that genuinely exists and can be opened, rather than a plausible-looking chain
 * the model assembled from the method name.
 *
 * Without Java support the flow is empty and the caller falls back to a normal repository
 * question — no Java PSI is referenced from this class.
 */
object CodeFlowAnalyzer {

    /** Words that suggest the user wants a flow rather than a general answer. */
    private val FLOW_INTENT = Regex(
        """\b(flow|call\s*chain|call\s*stack|sequence|walk\s*through|step\s*by\s*step|trace)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** `createPayment()` or `PaymentService#createPayment`. */
    private val EXPLICIT_CALL = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*\(\s*\)""")
    private val QUALIFIED = Regex("""[A-Za-z_][A-Za-z0-9_]*[#.]([a-z][A-Za-z0-9_]*)""")

    /**
     * The method a question is about, or null when it is not asking about one.
     *
     * Prefers an explicit `name()` or `Type#name`, then a camelCase identifier — the shape of
     * a Java method name — so "How does createPayment work?" resolves without parentheses.
     */
    fun methodNameFrom(query: String): String? {
        EXPLICIT_CALL.find(query)?.let { return it.groupValues[1] }
        QUALIFIED.find(query)?.let { return it.groupValues[1] }

        // A lowercase-initial identifier containing an internal capital: createPayment, doWork.
        return Regex("""\b([a-z][a-z0-9_]*[A-Z][A-Za-z0-9_]*)\b""")
            .find(query)
            ?.groupValues?.get(1)
    }

    /** True when the question reads like a request for a call flow. */
    fun looksLikeFlowQuestion(query: String): Boolean =
        FLOW_INTENT.containsMatchIn(query) ||
            (EXPLICIT_CALL.containsMatchIn(query) && Regex("""\bhow\b""", RegexOption.IGNORE_CASE).containsMatchIn(query))

    /**
     * Resolves the flow from the project model. Must be called off the EDT.
     *
     * Every returned node carries a location the enricher took from PSI, so it is already
     * verified — but [verified] re-checks the file still exists, because nothing downstream
     * should have to trust an upstream flag.
     */
    fun resolve(project: Project, methodName: String, budget: ContextBudget): List<FlowNode> {
        val nodes = try {
            SymbolEnricher.get().resolveFlow(project, methodName, budget.maxMethods)
        } catch (e: Exception) {
            emptyList()
        }
        return nodes.map { node ->
            val location = node.location ?: return@map node
            // Delegate to the resolver rather than re-implementing lookup here. An earlier
            // version used LocalFileSystem directly, which fails for any non-local VFS and so
            // *downgraded* locations the enricher had already verified from PSI.
            node.copy(location = SourceLocationResolver.verify(project, location).location)
        }
    }

    /**
     * Prompt for narrating a resolved flow. The nodes are given to the model as fact; it is
     * asked to explain them, not to invent them.
     */
    fun buildPrompt(projectName: String, methodName: String, nodes: List<FlowNode>): PromptBuilder {
        val builder = PromptBuilder(AiResultType.CODE_FLOW).projectContext(projectName)

        val resolved = buildString {
            append("These nodes were resolved from the IDE's own call graph and are known to exist:\n\n")
            nodes.forEachIndexed { index, node ->
                append(index + 1).append(". ").append(node.symbol)
                node.location?.let { append("   ").append(it.filePath).append(':').append(it.line) }
                node.description?.let { append("   (").append(it).append(')') }
                append('\n')
            }
        }
        builder.note(resolved)
        builder.task(
            "Explain how $methodName works, following the nodes above in order. " +
                "Return them in the nodes array using exactly the file paths and line numbers given. " +
                "Do not add nodes that are not listed.",
        )
        return builder
    }
}
