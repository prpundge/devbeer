package com.devutility.ai.error

import com.devutility.ai.model.ErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorParserTest {

    @Test
    fun `parses a java exception with a stack trace`() {
        val raw = """
            java.lang.NullPointerException: Cannot invoke "com.example.Payment.getAmount()" because "payment" is null
            	at com.example.PaymentService.create(PaymentService.java:81)
            	at com.example.PaymentController.createPayment(PaymentController.java:42)
            	at java.base/java.lang.reflect.Method.invoke(Method.java:568)
        """.trimIndent()

        val parsed = ErrorParser.parse(raw)

        assertEquals(ErrorKind.JAVA_EXCEPTION, parsed.kind)
        assertEquals("java.lang.NullPointerException", parsed.exceptionType)
        assertTrue(parsed.message!!, parsed.message!!.contains("because \"payment\" is null"))
        assertEquals(3, parsed.frames.size)
        assertEquals("PaymentService.java", parsed.fileName)
        assertEquals(81, parsed.line)
    }

    @Test
    fun `identifies the first application frame and skips library frames`() {
        val raw = """
            java.lang.IllegalStateException: boom
            	at java.base/java.util.Objects.requireNonNull(Objects.java:233)
            	at org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean(AbstractBeanFactory.java:333)
            	at com.example.OrderService.place(OrderService.java:57)
        """.trimIndent()

        val parsed = ErrorParser.parse(raw)
        val frame = parsed.firstApplicationFrame

        assertNotNull(frame)
        assertEquals("com.example.OrderService", frame!!.declaringClass)
        assertEquals(57, frame.line)
        assertEquals("OrderService.java", parsed.fileName)
    }

    @Test
    fun `uses the last caused by as the proximate cause`() {
        val raw = """
            org.springframework.beans.factory.BeanCreationException: Error creating bean 'paymentService'
            	at com.example.App.main(App.java:10)
            Caused by: java.lang.IllegalArgumentException: currency must not be null
            	at com.example.Money.<init>(Money.java:22)
        """.trimIndent()

        val parsed = ErrorParser.parse(raw)
        assertEquals("java.lang.IllegalArgumentException", parsed.exceptionType)
        assertTrue(parsed.message!!, parsed.message!!.contains("currency must not be null"))
    }

    @Test
    fun `classifies a spring boot startup failure`() {
        val raw = """
            ***************************
            APPLICATION FAILED TO START
            ***************************

            Description:

            Parameter 0 of constructor in com.example.PaymentController required a bean of type
            'com.example.PaymentService' that could not be found.
        """.trimIndent()

        val parsed = ErrorParser.parse(raw)
        assertEquals(ErrorKind.SPRING_STARTUP, parsed.kind)
    }

    @Test
    fun `classifies a no such bean exception as a spring failure`() {
        val raw = "org.springframework.beans.factory.NoSuchBeanDefinitionException: No qualifying bean of type 'X'"
        assertEquals(ErrorKind.SPRING_STARTUP, ErrorParser.parse(raw).kind)
    }

    @Test
    fun `parses a javac diagnostic`() {
        val raw = "/home/dev/app/src/main/java/com/example/Foo.java:42: error: cannot find symbol"
        val parsed = ErrorParser.parse(raw)

        assertEquals(ErrorKind.COMPILATION, parsed.kind)
        assertEquals(42, parsed.line)
        assertTrue(parsed.fileName!!, parsed.fileName!!.endsWith("Foo.java"))
        assertEquals("cannot find symbol", parsed.message)
    }

    @Test
    fun `parses a gradle bracket diagnostic with a column`() {
        val raw = "/app/src/main/java/Foo.java:[42,17] cannot find symbol: class Payment"
        val parsed = ErrorParser.parse(raw)

        assertEquals(ErrorKind.COMPILATION, parsed.kind)
        assertEquals(42, parsed.line)
        assertEquals(17, parsed.column)
    }

    @Test
    fun `parses a kotlinc diagnostic`() {
        val raw = "/app/src/main/kotlin/Foo.kt:12:5: error: unresolved reference: bar"
        val parsed = ErrorParser.parse(raw)

        assertEquals(ErrorKind.COMPILATION, parsed.kind)
        assertEquals(12, parsed.line)
        assertEquals(5, parsed.column)
        assertEquals("unresolved reference: bar", parsed.message)
    }

    @Test
    fun `classifies a test failure`() {
        val raw = """
            org.opentest4j.AssertionFailedError: expected: <3> but was: <2>
            	at com.example.PaymentTest.shouldTotal(PaymentTest.java:31)
        """.trimIndent()
        assertEquals(ErrorKind.TEST_FAILURE, ErrorParser.parse(raw).kind)
    }

    @Test
    fun `classifies a build failure`() {
        val raw = """
            FAILURE: Build failed with an exception.
            * What went wrong:
            Execution failed for task ':compileJava'.
        """.trimIndent()
        assertEquals(ErrorKind.BUILD_FAILURE, ErrorParser.parse(raw).kind)
    }

    @Test
    fun `unknown text still yields a parsed error rather than throwing`() {
        val parsed = ErrorParser.parse("something went wrong, I think")
        assertEquals(ErrorKind.UNKNOWN, parsed.kind)
        assertFalse(parsed.hasLocation)
        assertEquals("something went wrong, I think", parsed.message)
    }

    @Test
    fun `blank input is handled`() {
        val parsed = ErrorParser.parse("")
        assertEquals(ErrorKind.UNKNOWN, parsed.kind)
        assertTrue(parsed.frames.isEmpty())
    }

    @Test
    fun `stack trace length is capped`() {
        val raw = buildString {
            appendLine("java.lang.RuntimeException: deep")
            repeat(500) { appendLine("\tat com.example.Cls$it.method(Cls$it.java:${it + 1})") }
        }
        val parsed = ErrorParser.parse(raw, maxStackTraceLines = 100)
        assertEquals(100, parsed.frames.size)
    }

    @Test
    fun `handles frames without a source file`() {
        val raw = """
            java.lang.RuntimeException: x
            	at com.example.Foo.bar(Native Method)
            	at com.example.Foo.baz(Unknown Source)
        """.trimIndent()
        val parsed = ErrorParser.parse(raw)
        assertEquals(2, parsed.frames.size)
        assertEquals(0, parsed.frames[0].line)
    }
}
