package app.morphe.manager.patcher.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransactionalApkOutputTest {
    @Test
    fun `restores previous output when final output is missing`() {
        val dir = createTempDir(prefix = "morphe-transaction-")
        val output = File(dir, "patched.apk")
        val previous = TransactionalApkOutput.previous(output)
        try {
            previous.writeText("known-good")
            TransactionalApkOutput.recover(output)
            assertTrue(output.exists())
            assertEquals("known-good", output.readText())
            assertFalse(previous.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `keeps final output and removes stale transaction files`() {
        val dir = createTempDir(prefix = "morphe-transaction-")
        val output = File(dir, "patched.apk")
        val previous = TransactionalApkOutput.previous(output)
        val pending = TransactionalApkOutput.pending(output)
        try {
            output.writeText("current")
            previous.writeText("old")
            pending.writeText("incomplete")
            TransactionalApkOutput.recover(output)
            assertEquals("current", output.readText())
            assertFalse(previous.exists())
            assertFalse(pending.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
    @Test
    fun `commit replaces output and removes backup`() {
        val dir = createTempDir(prefix = "morphe-transaction-")
        val output = File(dir, "patched.apk")
        val pending = TransactionalApkOutput.pending(output)
        try {
            output.writeText("old")
            pending.writeText("new")
            TransactionalApkOutput.commit(output, pending)
            assertEquals("new", output.readText())
            assertFalse(pending.exists())
            assertFalse(TransactionalApkOutput.previous(output).exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `commit creates output when no previous artifact exists`() {
        val dir = createTempDir(prefix = "morphe-transaction-")
        val output = File(dir, "patched.apk")
        val pending = TransactionalApkOutput.pending(output)
        try {
            pending.writeText("new")
            TransactionalApkOutput.commit(output, pending)
            assertEquals("new", output.readText())
            assertFalse(pending.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
