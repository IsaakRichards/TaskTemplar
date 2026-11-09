package dev.isaakrichards.tasktemplar.engine

import android.content.Context
import android.content.Intent
import dev.isaakrichards.tasktemplar.ui.DialogActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Thrown by an action to end the macro early without counting as a failure. */
class StopMacroException(message: String) : Exception(message)

/** Shows pop-up dialogs from macros and waits for the answer. */
object Dialogs {

    private val pending = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    /**
     * @param kind "message", "options", "confirm" or "input"
     * @return the chosen option / typed text / "yes", or null if dismissed or timed out
     */
    suspend fun ask(
        ctx: Context,
        kind: String,
        title: String,
        message: String,
        options: List<String> = emptyList(),
        timeoutSec: Long = 120,
    ): String? {
        val id = UUID.randomUUID().toString()
        val answer = CompletableDeferred<String?>()
        pending[id] = answer
        try {
            ctx.startActivity(
                Intent(ctx, DialogActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    .putExtra(DialogActivity.EXTRA_ID, id)
                    .putExtra(DialogActivity.EXTRA_KIND, kind)
                    .putExtra(DialogActivity.EXTRA_TITLE, title)
                    .putExtra(DialogActivity.EXTRA_MESSAGE, message)
                    .putExtra(DialogActivity.EXTRA_OPTIONS, options.toTypedArray()),
            )
            return withTimeoutOrNull(timeoutSec.coerceAtLeast(5) * 1000) { answer.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** Called by [DialogActivity] when the user answers or dismisses. */
    fun complete(id: String, result: String?) {
        pending.remove(id)?.complete(result)
    }
}
