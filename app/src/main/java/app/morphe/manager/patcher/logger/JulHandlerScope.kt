package app.morphe.manager.patcher.logger

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.logging.Handler

/**
 * Temporarily routes root JUL records to one patching logger.
 *
 * JUL root handlers are process-global. Patching sessions in the coroutine runtime therefore must
 * not replace them concurrently. Existing handlers are detached, never closed, and restored after
 * the patcher phase even when it fails.
 */
internal object JulHandlerScope {
    private val mutex = Mutex()

    suspend fun <T> withHandler(handler: Handler, block: suspend () -> T): T =
        mutex.withLock {
            val root = java.util.logging.Logger.getLogger("")
            val previous = root.handlers.toList()

            previous.forEach(root::removeHandler)
            root.addHandler(handler)
            try {
                block()
            } finally {
                root.removeHandler(handler)
                previous.forEach { previousHandler ->
                    if (root.handlers.none { it === previousHandler }) {
                        root.addHandler(previousHandler)
                    }
                }
            }
        }
}
