package app.morphe.manager.patcher.logger

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.LogRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JulHandlerScopeTest {
    private class NoOpHandler : Handler() {
        override fun publish(record: LogRecord?) = Unit
        override fun flush() = Unit
        override fun close() = Unit
    }

    private fun isolatedLogger(suffix: String) =
        java.util.logging.Logger.getLogger("JulHandlerScopeTest.$suffix").apply {
            useParentHandlers = false
            handlers.forEach(::removeHandler)
        }

    @Test
    fun `previous root handlers are restored after success`() = runBlocking {
        val root = isolatedLogger("success")
        val previousHandler = NoOpHandler()
        root.addHandler(previousHandler)
        val previous = root.handlers.toList()
        val session = NoOpHandler()

        JulHandlerScope.withHandler(session, root) {
            assertTrue(root.handlers.any { it === session })
            assertTrue(previous.none { old -> root.handlers.any { it === old } })
        }

        assertTrue(root.handlers.none { it === session })
        assertEquals(previous, root.handlers.toList())
    }

    @Test
    fun `previous root handlers are restored after failure`() = runBlocking {
        val root = isolatedLogger("failure")
        val previousHandler = NoOpHandler()
        root.addHandler(previousHandler)
        val previous = root.handlers.toList()
        val session = NoOpHandler()

        assertFailsWith<IllegalStateException> {
            JulHandlerScope.withHandler(session, root) {
                error("patcher failed")
            }
        }

        assertTrue(root.handlers.none { it === session })
        assertEquals(previous, root.handlers.toList())
    }

    @Test
    fun `concurrent scopes never own root logger at the same time`() = runBlocking {
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)
        val firstEntered = CompletableDeferred<Unit>()

        val root = isolatedLogger("concurrent")

        suspend fun enter(handler: Handler, signal: Boolean = false) {
            JulHandlerScope.withHandler(handler, root) {
                val now = active.incrementAndGet()
                maximum.updateAndGet { old -> maxOf(old, now) }
                if (signal) firstEntered.complete(Unit)
                delay(25)
                active.decrementAndGet()
            }
        }

        val first = async { enter(NoOpHandler(), signal = true) }
        firstEntered.await()
        val second = async { enter(NoOpHandler()) }
        first.await()
        second.await()

        assertEquals(1, maximum.get())
    }
}
