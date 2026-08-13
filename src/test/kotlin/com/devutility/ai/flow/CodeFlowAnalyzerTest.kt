package com.devutility.ai.flow

import com.devutility.ai.model.CodeLocation
import com.devutility.ai.model.FlowNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeFlowAnalyzerTest {

    // ------------------------------------------------------------------ method extraction

    @Test
    fun `extracts an explicit call`() {
        assertEquals("createPayment", CodeFlowAnalyzer.methodNameFrom("How does createPayment() work?"))
    }

    @Test
    fun `extracts a qualified reference`() {
        assertEquals("createPayment", CodeFlowAnalyzer.methodNameFrom("Explain PaymentService#createPayment"))
        assertEquals("save", CodeFlowAnalyzer.methodNameFrom("what does PaymentRepository.save do"))
    }

    @Test
    fun `extracts a camel case identifier without parentheses`() {
        assertEquals("createPayment", CodeFlowAnalyzer.methodNameFrom("how does createPayment work"))
    }

    @Test
    fun `returns null when no method is named`() {
        assertNull(CodeFlowAnalyzer.methodNameFrom("where is the config"))
        assertNull(CodeFlowAnalyzer.methodNameFrom("explain the project"))
    }

    @Test
    fun `an explicit call wins over a stray camel case word`() {
        assertEquals(
            "createPayment",
            CodeFlowAnalyzer.methodNameFrom("In myService, how does createPayment() work?"),
        )
    }

    // ------------------------------------------------------------------ intent

    @Test
    fun `recognises flow questions`() {
        assertTrue(CodeFlowAnalyzer.looksLikeFlowQuestion("show me the call flow of createPayment"))
        assertTrue(CodeFlowAnalyzer.looksLikeFlowQuestion("trace the sequence for save"))
        assertTrue(CodeFlowAnalyzer.looksLikeFlowQuestion("How does createPayment() work?"))
    }

    @Test
    fun `does not claim a general question is a flow question`() {
        assertFalse(CodeFlowAnalyzer.looksLikeFlowQuestion("where is payment validation handled"))
        assertFalse(CodeFlowAnalyzer.looksLikeFlowQuestion("what does this project do"))
    }

    // ------------------------------------------------------------------ prompt

    @Test
    fun `the prompt presents the resolved nodes as fact and forbids additions`() {
        val nodes = listOf(
            FlowNode("PaymentController#create", CodeLocation("/p/C.java", 42, verified = true)),
            FlowNode("PaymentService#create", CodeLocation("/p/S.java", 81, verified = true)),
        )
        val text = CodeFlowAnalyzer.buildPrompt("demo", "create", nodes).userMessage()

        assertTrue(text, text.contains("PaymentController#create"))
        assertTrue(text, text.contains("/p/S.java:81"))
        assertTrue(text, text.contains("known to exist"))
        // The whole point of resolving structure from PSI: the model narrates, never invents.
        assertTrue(text, text.contains("Do not add nodes that are not listed"))
    }

    @Test
    fun `the system prompt for flow forbids inventing locations`() {
        val text = CodeFlowAnalyzer.buildPrompt("demo", "x", emptyList()).build()[0].content
        assertTrue(text, text.contains("Do not invent"))
    }
}
