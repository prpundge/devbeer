package com.devutility.ai.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiResponseParserTest {

    // ------------------------------------------------------------------ extraction

    @Test
    fun `extracts a bare json object`() {
        assertEquals("""{"a":1}""", AiResponseParser.extractJsonObject("""{"a":1}"""))
    }

    @Test
    fun `extracts json wrapped in prose and fences`() {
        val content = """
            Sure! Here is the analysis:
            ```json
            {"summary":"x","rootCause":"y"}
            ```
            Let me know if you need more.
        """.trimIndent()
        assertEquals("""{"summary":"x","rootCause":"y"}""", AiResponseParser.extractJsonObject(content))
    }

    @Test
    fun `brace counting ignores braces inside strings`() {
        // The realistic failure: generated code in a field contains braces.
        val content = """{"suggestedCode":"if (x) { y(); }","reason":"z"}"""
        assertEquals(content, AiResponseParser.extractJsonObject(content))
    }

    @Test
    fun `brace counting ignores escaped quotes`() {
        val content = """{"a":"he said \"hi\" {","b":1}"""
        assertEquals(content, AiResponseParser.extractJsonObject(content))
    }

    @Test
    fun `returns null when there is no object`() {
        assertNull(AiResponseParser.extractJsonObject("no json here at all"))
    }

    // ------------------------------------------------------------------ error analysis

    @Test
    fun `parses a full error analysis`() {
        val content = """
            {"summary":"Bean missing","rootCause":"PaymentService is not registered",
             "why":"No @Service annotation","confidence":0.94,
             "likelyLocation":{"filePath":"PaymentServiceImpl.java","line":15},
             "relatedLocations":[{"filePath":"PaymentController.java","line":42,"description":"injects it"}],
             "fixes":[{"filePath":"PaymentServiceImpl.java","startLine":15,"startColumn":0,
                       "endLine":15,"endColumn":0,"originalText":"public class",
                       "replacement":"@Service\npublic class","explanation":"register the bean"}]}
        """.trimIndent()

        val outcome = AiResponseParser.errorAnalysis(content)
        val analysis = outcome.value!!

        assertTrue(outcome.structured)
        assertEquals("PaymentService is not registered", analysis.rootCause)
        assertEquals(94, analysis.confidencePercent)
        assertEquals("PaymentServiceImpl.java", analysis.likelyLocation!!.filePath)
        assertEquals(15, analysis.likelyLocation!!.line)
        assertEquals(1, analysis.relatedLocations.size)
        assertEquals(1, analysis.fixes.size)
        assertEquals("register the bean", analysis.fixes[0].explanation)
    }

    @Test
    fun `locations from the model are never marked verified`() {
        // The plugin must confirm a location exists before offering navigation (§13, §16).
        val content = """{"likelyLocation":{"filePath":"Invented.java","line":999},
                          "relatedLocations":[{"filePath":"AlsoInvented.java","line":1}]}"""
        val analysis = AiResponseParser.errorAnalysis(content).value!!

        assertFalse(analysis.likelyLocation!!.verified)
        assertTrue(analysis.relatedLocations.none { it.verified })
    }

    @Test
    fun `accepts confidence expressed as a percentage`() {
        assertEquals(94, AiResponseParser.errorAnalysis("""{"confidence":94}""").value!!.confidencePercent)
        assertEquals(94, AiResponseParser.errorAnalysis("""{"confidence":0.94}""").value!!.confidencePercent)
    }

    @Test
    fun `clamps an out of range confidence`() {
        assertEquals(100, AiResponseParser.errorAnalysis("""{"confidence":150}""").value!!.confidencePercent)
        assertEquals(0, AiResponseParser.errorAnalysis("""{"confidence":-1}""").value!!.confidencePercent)
    }

    @Test
    fun `a small number above one is read as a percentage, not clamped`() {
        // "confidence": 5 means 5%, not 500%. Treating it as out-of-range and clamping to 100%
        // would turn the model's least confident answer into its most confident one.
        assertEquals(5, AiResponseParser.errorAnalysis("""{"confidence":5}""").value!!.confidencePercent)
    }

    @Test
    fun `missing fields degrade to empty rather than throwing`() {
        val analysis = AiResponseParser.errorAnalysis("""{"summary":"only this"}""").value!!
        assertEquals("only this", analysis.summary)
        assertEquals("", analysis.rootCause)
        assertNull(analysis.likelyLocation)
        assertTrue(analysis.fixes.isEmpty())
    }

    @Test
    fun `malformed json falls back to raw text`() {
        val outcome = AiResponseParser.errorAnalysis("I could not determine the cause.")
        assertNull(outcome.value)
        assertFalse(outcome.structured)
        assertEquals("I could not determine the cause.", outcome.rawText)
    }

    @Test
    fun `truncated json falls back rather than half parsing`() {
        val outcome = AiResponseParser.errorAnalysis("""{"summary":"x","rootCause":""")
        assertNull(outcome.value)
        assertFalse(outcome.structured)
    }

    @Test
    fun `insufficient context flag is read`() {
        val analysis = AiResponseParser.errorAnalysis("""{"insufficientContext":true}""").value!!
        assertTrue(analysis.insufficientContext)
    }

    @Test
    fun `a fix without a replacement is discarded`() {
        val content = """{"fixes":[{"filePath":"A.java","startLine":1}]}"""
        assertTrue(AiResponseParser.errorAnalysis(content).value!!.fixes.isEmpty())
    }

    @Test
    fun `alternative field names for a path are accepted`() {
        val analysis = AiResponseParser.errorAnalysis("""{"likelyLocation":{"file":"A.java","line":3}}""").value!!
        assertEquals("A.java", analysis.likelyLocation!!.filePath)
    }

    // ------------------------------------------------------------------ other shapes

    @Test
    fun `parses a repository answer with locations`() {
        val content = """{"summary":"s","detail":"d",
                          "locations":[{"filePath":"A.java","line":1},{"filePath":"B.java","line":2}]}"""
        val answer = AiResponseParser.repositoryAnswer(content).value!!
        assertEquals("s", answer.summary)
        assertEquals(2, answer.locations.size)
    }

    @Test
    fun `parses an explanation`() {
        val content = """{"summary":"s","howItWorks":"h","dependencies":["a","b"],"risks":["r"]}"""
        val explanation = AiResponseParser.explanation(content).value!!
        assertEquals(listOf("a", "b"), explanation.dependencies)
        assertEquals(listOf("r"), explanation.risks)
    }

    @Test
    fun `parses a suggestion`() {
        val content = """{"problem":"p","recommendation":"r","suggestedCode":"int x = 1;","reason":"why"}"""
        val suggestion = AiResponseParser.suggestion(content).value!!
        assertEquals("int x = 1;", suggestion.suggestedCode)
    }

    @Test
    fun `parses a code flow in order`() {
        val content = """
            {"summary":"flow","nodes":[
              {"symbol":"Controller#create","filePath":"C.java","line":42},
              {"symbol":"Service#create","filePath":"S.java","line":81},
              {"symbol":"Repo#save","filePath":"R.java","line":10}]}
        """.trimIndent()
        val flow = AiResponseParser.codeFlow(content).value!!
        assertEquals(3, flow.nodes.size)
        assertEquals("Controller#create", flow.nodes[0].symbol)
        assertEquals("Repo#save", flow.nodes[2].symbol)
        assertNotNull(flow.nodes[0].location)
    }

    @Test
    fun `a flow node without a symbol is dropped`() {
        val content = """{"nodes":[{"filePath":"A.java","line":1},{"symbol":"ok"}]}"""
        val flow = AiResponseParser.codeFlow(content).value!!
        assertEquals(1, flow.nodes.size)
        assertEquals("ok", flow.nodes[0].symbol)
    }
}
