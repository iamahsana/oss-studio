package com.iamahsana.ossstudio.logging

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogSnapshotTest {
    @Test
    fun copiesRotatedLogBeforeCurrentLog() {
        val directory = Files.createTempDirectory("oss-diagnostics-test").toFile()
        try {
            val previous = File(directory, "previous.jsonl").apply { writeText("older\n") }
            val current = File(directory, "current.jsonl").apply { writeText("newer\n") }
            val output = ByteArrayOutputStream()

            assertTrue(DiagnosticLogSnapshot.copy(previous, current, output))
            assertEquals("older\nnewer\n", output.toString("UTF-8"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun returnsFalseWhenNoLogFilesExist() {
        val directory = Files.createTempDirectory("oss-diagnostics-empty").toFile()
        try {
            val output = ByteArrayOutputStream()
            assertFalse(
                DiagnosticLogSnapshot.copy(
                    File(directory, "missing-previous.jsonl"),
                    File(directory, "missing-current.jsonl"),
                    output
                )
            )
            assertEquals("", output.toString("UTF-8"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
