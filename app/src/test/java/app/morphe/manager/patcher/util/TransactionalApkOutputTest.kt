package app.morphe.manager.patcher.util

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertTrue

class TransactionalApkOutputTest {
    @Test
    fun `recovery discards orphan pending output when no committed artifact exists`() {
        val dir = createTempDirectory("morphe-transaction-").toFile()
        val output = File(dir, "patched.apk")
        val pending = TransactionalApkOutput.pending(output)
        try {
            pending.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))

            TransactionalApkOutput.recover(output)

            assertFalse(output.exists())
            assertFalse(pending.exists())
            assertFalse(TransactionalApkOutput.previous(output).exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `restores previous output when final output is missing`() {
        val dir = createTempDirectory("morphe-transaction-").toFile()
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
    fun `recovery replaces truncated final output with previous known good APK`() {
        val dir = createTempDirectory("morphe-transaction-").toFile()
        val output = File(dir, "patched.apk")
        val previous = TransactionalApkOutput.previous(output)
        try {
            output.createNewFile()
            previous.writeText("known-good")

            TransactionalApkOutput.recover(output)

            assertEquals("known-good", output.readText())
            assertFalse(previous.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `recovery discards empty previous backup instead of publishing it`() {
        val dir = createTempDirectory("morphe-transaction-").toFile()
        val output = File(dir, "patched.apk")
        val previous = TransactionalApkOutput.previous(output)
        try {
            previous.createNewFile()

            TransactionalApkOutput.recover(output)

            assertFalse(output.exists())
            assertFalse(previous.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `recovery with truncated final and empty previous leaves no published APK`() {
        val dir = createTempDirectory("morphe-transaction-").toFile()
        val output = File(dir, "patched.apk")
        val previous = TransactionalApkOutput.previous(output)
        try {
            output.createNewFile()
            previous.createNewFile()

            TransactionalApkOutput.recover(output)

            assertFalse(output.exists())
            assertFalse(previous.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `keeps final output and removes stale transaction files`() {
        val dir = createTempDirectory("morphe-transaction-").toFile()
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
    fun `invalid existing output is rejected without deleting previous backup`() {
        val dir = createTempDirectory("morphe-transaction-").toFile()
        val output = File(dir, "patched.apk")
        val pending = TransactionalApkOutput.pending(output)
        val previous = TransactionalApkOutput.previous(output)
        try {
            output.mkdirs()
            pending.writeText("new")
            previous.writeText("known-good")

            assertFails { TransactionalApkOutput.commit(output, pending) }

            assertTrue(output.isDirectory)
            assertEquals("known-good", previous.readText())
            assertEquals("new", pending.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `commit replaces output and removes backup`() {
        val dir = createTempDirectory("morphe-transaction-").toFile()
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
    fun `empty pending output is rejected before known good output is moved`() {
        val dir = createTempDirectory("morphe-transaction-").toFile()
        val output = File(dir, "patched.apk")
        val pending = TransactionalApkOutput.pending(output)
        try {
            output.writeText("known-good")
            pending.createNewFile()

            assertFails { TransactionalApkOutput.commit(output, pending) }

            assertEquals("known-good", output.readText())
            assertTrue(pending.exists())
            assertFalse(TransactionalApkOutput.previous(output).exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `commit creates output when no previous artifact exists`() {
        val dir = createTempDirectory("morphe-transaction-").toFile()
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
