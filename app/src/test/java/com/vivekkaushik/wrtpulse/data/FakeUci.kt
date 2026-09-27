package com.vivekkaushik.wrtpulse.data

/**
 * One uci package in memory, to run a store's batch the way `uci batch` does.
 *
 * It knows what the stores emit — `set pkg.section=type`, `set pkg.section.option='value'`,
 * `delete pkg.section` and `delete pkg.section.option` — and resolves `@type[i]` as uci does:
 * the i-th section of that type, named sections counted too, looked up when each command runs,
 * so a delete renumbers every later section of its type. What uci would refuse — an index past
 * the end, an option on a section that is not there, a delete of nothing — throws, naming the
 * command, instead of being skipped.
 */
internal class FakeUci(private val pkg: String, sections: List<Section>) {

    /** A section as a test writes it: [id] is the test's own handle, [name] is null for an anonymous one. */
    data class Section(
        val id: String,
        val type: String,
        val name: String? = null,
        val options: Map<String, String> = emptyMap(),
    )

    private class Live(val id: String, var type: String, val name: String?, val options: MutableMap<String, String>)

    private val start = sections.map { it.id }
    private val live = sections.map { Live(it.id, it.type, it.name, LinkedHashMap(it.options)) }.toMutableList()

    init {
        require(start.distinct().size == start.size) { "section ids must be unique" }
    }

    /** `uci show <pkg>` of the package as it stands — what a store's load parses. */
    fun show(): String = buildString {
        live.forEach { s ->
            val ref = ref(s)
            append("$pkg.$ref=${s.type}\n")
            s.options.forEach { (k, v) -> append("$pkg.$ref.$k='${v.replace("'", "'\\''")}'\n") }
        }
    }

    /** Runs [ops] in order, as one batch. A section the batch creates takes its name as its id. */
    fun run(ops: List<String>): FakeUci = apply { ops.forEach(::exec) }

    /** Every section now, by id, in file order. */
    val ids: List<String> get() = live.map { it.id }

    /** The starting sections the batch deleted. */
    val deleted: Set<String> get() = start.toSet() - live.map { it.id }.toSet()

    /** A section's options now, or null once it is gone. */
    fun options(id: String): Map<String, String>? = live.firstOrNull { it.id == id }?.options?.toMap()

    private fun ref(s: Live): String =
        s.name ?: "@${s.type}[${live.takeWhile { it !== s }.count { it.type == s.type }}]"

    private fun resolve(section: String): Live? {
        val m = ANONYMOUS.matchEntire(section)
        return if (m != null) {
            live.filter { it.type == m.groupValues[1] }.getOrNull(m.groupValues[2].toInt())
        } else {
            live.firstOrNull { it.name == section }
        }
    }

    private fun exec(op: String) {
        val verb = op.substringBefore(' ')
        val rest = op.substringAfter(' ')
        val parts = rest.substringBefore('=').split('.')
        require(parts.size in 2..3 && parts[0] == pkg) { "`$op`: not a $pkg path" }
        val section = parts[1]
        val option = parts.getOrNull(2)
        val target = resolve(section)
        when (verb) {
            "set" -> {
                require('=' in rest) { "`$op`: set without a value" }
                val value = unquote(rest.substringAfter('='))
                when {
                    option != null -> (target ?: error("`$op`: no section $section")).options[option] = value
                    target != null -> target.type = value
                    ANONYMOUS.matches(section) -> error("`$op`: no section $section")
                    else -> live += Live(section, value, section, LinkedHashMap())
                }
            }
            "delete" -> {
                val s = target ?: error("`$op`: no section $section")
                when {
                    option == null -> live.remove(s)
                    s.options.remove(option) == null -> error("`$op`: no option $option")
                }
            }
            else -> error("`$op`: FakeUci does not do $verb")
        }
    }

    /** A value as the batch parser reads it: `'…'` taken literally, `\x` outside quotes as x. */
    private fun unquote(raw: String): String = buildString {
        var i = 0
        while (i < raw.length) {
            when (val c = raw[i]) {
                '\'' -> {
                    val end = raw.indexOf('\'', i + 1)
                    require(end >= 0) { "unterminated quote in $raw" }
                    append(raw, i + 1, end)
                    i = end + 1
                }
                '\\' -> {
                    require(i + 1 < raw.length) { "dangling backslash in $raw" }
                    append(raw[i + 1])
                    i += 2
                }
                else -> {
                    append(c)
                    i++
                }
            }
        }
    }

    private companion object {
        val ANONYMOUS = Regex("""@([^\[\]]+)\[(\d+)]""")
    }
}
