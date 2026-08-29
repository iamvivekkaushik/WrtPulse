package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.Parsers
import com.vivekkaushik.wrtpulse.ops.WifiNetwork
import com.vivekkaushik.wrtpulse.ops.WifiRadio

/**
 * The wireless config plus everything staged against it. The core promise of the design:
 * edits accumulate here, the diff sheet shows the exact uci ops, and NOTHING reaches the
 * router until the user applies.
 */
class WifiStore(private val session: RouterSession) {

    val radios = mutableStateListOf<WifiRadio>()
    val networks = mutableStateListOf<WifiNetwork>()

    /** "section.option" → (saved value, staged value). */
    val staged = mutableStateMapOf<String, Pair<String, String>>()

    var loaded by mutableStateOf(false); private set
    var applying by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)

    val pendingCount: Int get() = staged.size

    suspend fun load() {
        try {
            val out = session.exec(Commands.WIRELESS_CONFIG, timeoutMs = 10_000)
                .requireOk(Commands.WIRELESS_CONFIG).stdout
            val (r, n) = Parsers.wireless(Parsers.uciShow(out))
            radios.clear(); radios.addAll(r)
            networks.clear(); networks.addAll(n)
            loaded = true
            error = null
        } catch (e: SshException) {
            error = e.message
        }
    }

    /** Stages one option; staging the saved value back un-stages it. */
    fun stage(section: String, option: String, saved: String, value: String) {
        val key = "$section.$option"
        if (value == saved) staged.remove(key) else staged[key] = saved to value
    }

    /** The value the UI should render: staged if present, else saved. */
    fun value(section: String, option: String, saved: String): String =
        staged["$section.$option"]?.second ?: saved

    fun revert() {
        staged.clear()
    }

    /** `- key='old'` / `+ key='new'` pairs for the review sheet, secrets masked. */
    fun diffLines(): List<Pair<String, Boolean>> = staged.entries
        .sortedBy { it.key }
        .flatMap { (key, change) ->
            val (old, new) = change
            val secret = key.endsWith(".key")
            listOf(
                "- $key='${if (secret) "••••••••" else old}'" to false,
                "+ $key='${if (secret) mask(new) else new}'" to true,
            )
        }

    fun ops(): List<String> = staged.entries
        .sortedBy { it.key }
        .map { (key, change) -> "set wireless.$key='${escape(change.second)}'" }

    /** Runs the staged batch, then reloads the config so the UI reflects what the router has. */
    suspend fun apply(): Boolean {
        if (staged.isEmpty() || applying) return true
        applying = true
        error = null
        return try {
            val script = Commands.uciBatch(ops(), commitPackage = "wireless", reload = "wifi reload")
            session.exec(script, timeoutMs = 30_000).requireOk("uci batch")
            staged.clear()
            load()
            true
        } catch (e: SshException) {
            error = e.message
            false
        } finally {
            applying = false
        }
    }

    companion object {
        /** uci values travel single-quoted; a quote inside the value must not break out. */
        fun escape(value: String): String = value.replace("'", "'\\''")

        fun mask(value: String): String =
            if (value.length <= 2) "••" else value.first() + "•".repeat(value.length - 2) + value.last()
    }
}
