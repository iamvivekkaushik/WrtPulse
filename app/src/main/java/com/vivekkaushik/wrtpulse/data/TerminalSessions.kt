package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import com.vivekkaushik.wrtpulse.net.RouterSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The terminal's open shells. Each tab is its own SSH channel on the shared connection, so
 * one can sit in a long-running command while another is used for something else.
 */
class TerminalSessions(
    private val session: RouterSession,
    private val scope: CoroutineScope,
) {
    val tabs = mutableStateListOf<TermEngine>()

    var selected by mutableIntStateOf(0)
        private set

    private val jobs = mutableMapOf<TermEngine, Job>()

    val current: TermEngine? get() = tabs.getOrNull(selected)

    /** Opens the first shell the moment the terminal is first shown. */
    fun openIfEmpty() {
        if (tabs.isEmpty()) open()
    }

    fun open() {
        val engine = TermEngine(session)
        tabs.add(engine)
        selected = tabs.lastIndex
        jobs[engine] = scope.launch { engine.run() }
    }

    fun select(index: Int) {
        if (index in tabs.indices) selected = index
    }

    /** Closes one shell. Closing the last one leaves a fresh shell rather than a dead screen. */
    fun close(index: Int) {
        val engine = tabs.getOrNull(index) ?: return
        jobs.remove(engine)?.cancel()   // cancelling run() closes the channel
        tabs.removeAt(index)
        selected = nextSelection(tabs.size, index, selected)
        if (tabs.isEmpty()) open()
    }

    /** Cancels every shell — the router switched, or the terminal is going away. */
    fun closeAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        tabs.clear()
        selected = 0
    }

    companion object {
        /**
         * Which tab to show after closing one: stay put when a tab to the right took its
         * place, otherwise step left.
         */
        fun nextSelection(remaining: Int, closed: Int, selected: Int): Int = when {
            remaining <= 0 -> 0
            closed < selected -> (selected - 1).coerceIn(0, remaining - 1)
            else -> selected.coerceIn(0, remaining - 1)
        }
    }
}
