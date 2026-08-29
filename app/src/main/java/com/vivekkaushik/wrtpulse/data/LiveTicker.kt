package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Direct port of the design's DCLogic ticker: metric values swap in place every second,
 * the WAN chart appends one point per second over a sliding 60 s window, and a new log
 * line lands every other tick.
 */
class LiveTicker {
    private fun seed(n: Int, base: Float, amp: Float): MutableList<Float> =
        MutableList(n) { i ->
            maxOf(2f, base + sin(i / 5.5) .toFloat() * amp * 0.6f + (Random.nextFloat() - 0.5f) * amp * 0.5f)
        }

    val down = mutableStateListOf<Float>().apply { addAll(seed(60, 42f, 26f)) }
    val up = mutableStateListOf<Float>().apply { addAll(seed(60, 9f, 6f)) }
    val logs = mutableStateListOf<LogLine>()

    var tickCount by mutableIntStateOf(0); private set
    var latencyMs by mutableIntStateOf(11); private set
    var cpuPct by mutableIntStateOf(23); private set
    var ramPct by mutableIntStateOf(61); private set
    var load by mutableFloatStateOf(0.42f); private set
    var newLines by mutableIntStateOf(23); private set
    var clientDown by mutableFloatStateOf(4.2f); private set
    var clientUp by mutableFloatStateOf(0.8f); private set

    private var clock = 50527 // seconds since midnight -> 14:02:07

    init {
        repeat(14) { i ->
            clock += 1 + (i % 3)
            logs.add(stamp(Demo.logPool[i % Demo.logPool.size]))
        }
    }

    private fun stamp(t: LogTemplate): LogLine {
        val h = (clock / 3600) % 24
        val m = (clock / 60) % 60
        val s = clock % 60
        fun p(n: Int) = n.toString().padStart(2, '0')
        return LogLine("${p(h)}:${p(m)}:${p(s)}", t.color, t.src, t.msg, t.tok)
    }

    private fun walk(v: Float, amp: Float, lo: Float, hi: Float): Float =
        (v + (Random.nextFloat() - 0.5f) * amp).coerceIn(lo, hi)

    suspend fun run(tickMs: Long = 1000L) {
        while (true) {
            delay(tickMs)
            tick()
        }
    }

    fun tick() {
        down.removeAt(0); down.add(walk(down.last(), 14f, 6f, 86f))
        up.removeAt(0); up.add(walk(up.last(), 4f, 2f, 20f))
        if (tickCount % 2 == 0) {
            clock += 2 + floor(Random.nextFloat() * 3).toInt()
            logs.add(stamp(Demo.logPool[Random.nextInt(Demo.logPool.size)]))
            if (logs.size > 240) repeat(logs.size - 240) { logs.removeAt(0) }
            newLines = if (newLines >= 48) 4 else newLines + 1
        }
        latencyMs = walk(latencyMs.toFloat(), 5f, 7f, 26f).roundToInt()
        cpuPct = walk(cpuPct.toFloat(), 7f, 6f, 88f).roundToInt()
        ramPct = walk(ramPct.toFloat(), 2.5f, 52f, 74f).roundToInt()
        load = walk(load, 0.08f, 0.2f, 1.4f)
        clientDown = walk(clientDown, 1.2f, 0.4f, 9.5f)
        clientUp = walk(clientUp, 0.4f, 0.1f, 3f)
        tickCount++
    }

    fun clearNewLines() { newLines = 0 }
}
