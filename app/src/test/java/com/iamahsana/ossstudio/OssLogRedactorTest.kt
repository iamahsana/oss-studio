package com.iamahsana.ossstudio

import com.iamahsana.ossstudio.logging.OssLogRedactor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OssLogRedactorTest {
    @Test
    fun redactsStreamUrlsAndCredentials() {
        val input = "destination=rtmp://live.example.com/app/secret-stream-key token=abc123 password=hunter2"
        val output = OssLogRedactor.redact(input)

        assertTrue(output.contains("[REDACTED_STREAM_URL]"))
        assertTrue(output.contains("token=[REDACTED]"))
        assertTrue(output.contains("password=[REDACTED]"))
        assertFalse(output.contains("secret-stream-key"))
        assertFalse(output.contains("abc123"))
        assertFalse(output.contains("hunter2"))

        val authOutput = OssLogRedactor.redact("Authorization: Bearer bearer-secret")
        assertTrue(authOutput.contains("Authorization: [REDACTED]"))
        assertFalse(authOutput.contains("bearer-secret"))
    }

    @Test
    fun preservesOrdinaryDiagnosticText() {
        val input = "sourceId=scene-image-01 decoder initialization failed"
        assertTrue(OssLogRedactor.redact(input).contains(input))
    }
}
