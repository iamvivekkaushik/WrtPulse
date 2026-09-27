package com.vivekkaushik.wrtpulse.data

import org.junit.Assert.assertEquals

/**
 * One uci package in memory, to run a store's batch the way `uci batch` does.
 *
 * It knows what the stores emit — `set pkg.section=type`, `set pkg.section.option='value'`,
 * `add_list pkg.section.option='value'`, `delete pkg.section` and `delete pkg.section.option` —
 * and resolves `@type[i]` as uci does: the i-th section of that type, named sections counted too,
 * looked up when each command runs, so a delete renumbers every later section of its type. What
 * uci would refuse — an index past the end, an option on a section that is not there, a delete
 * of nothing — throws, naming the command, instead of being skipped.
 */
internal class FakeUci(val pkg: String, sections: List<Section>) {

    /**
     * A section as a test writes it: [id] is the test's own handle, [name] is null for an
     * anonymous one. [lists] are its `list` options, which follow the plain ones in the file.
     */
    data class Section(
        val id: String,
        val type: String,
        val name: String? = null,
        val options: Map<String, String> = emptyMap(),
        val lists: Map<String, List<String>> = emptyMap(),
    )

    /** One option as uci holds it: a string, or a list's items in order. */
    private class Opt(val items: MutableList<String>, val list: Boolean)

    private class Live(val id: String, var type: String, val name: String?, val options: LinkedHashMap<String, Opt>)

    private val start = sections.map { it.id }
    private val live = sections.map { s ->
        require(s.options.keys.none { it in s.lists } && s.lists.values.none { it.isEmpty() }) {
            "section ${s.id}: an option is a string or a list of at least one item, not both"
        }
        val options = LinkedHashMap<String, Opt>()
        s.options.forEach { (k, v) -> options[k] = Opt(mutableListOf(v), list = false) }
        s.lists.forEach { (k, v) -> options[k] = Opt(v.toMutableList(), list = true) }
        Live(s.id, s.type, s.name, options)
    }.toMutableList()

    init {
        require(start.distinct().size == start.size) { "section ids must be unique" }
    }

    /** `uci show <pkg>` of the package as it stands — what a store's load parses. */
    fun show(): String = buildString {
        live.forEach { s ->
            val ref = ref(s)
            append("$pkg.$ref=${s.type}\n")
            // A list prints as its items quoted one by one: `pkg.s.ports='lan1:u*' 'lan2:u*'`.
            s.options.forEach { (k, v) ->
                val quoted = v.items.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
                append("$pkg.$ref.$k=$quoted\n")
            }
        }
    }

    /** Runs [ops] in order, as one batch. A section the batch creates takes its name as its id. */
    fun run(ops: List<String>): FakeUci = apply { ops.forEach(::exec) }

    /** Every section now, by id, in file order. */
    val ids: List<String> get() = live.map { it.id }

    /** The starting sections the batch deleted. */
    val deleted: Set<String> get() = start.toSet() - live.map { it.id }.toSet()

    /** Every section now, as a test would write it, in file order. */
    val sections: List<Section>
        get() = live.map { s ->
            val (lists, strings) = s.options.entries.partition { it.value.list }
            Section(
                s.id, s.type, s.name,
                strings.associate { it.key to it.value.items.single() },
                lists.associate { it.key to it.value.items.toList() },
            )
        }

    /** A section's string options now, or null once it is gone. */
    fun options(id: String): Map<String, String>? = sections.firstOrNull { it.id == id }?.options

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
                    option != null -> {
                        val s = target ?: error("`$op`: no section $section")
                        // uci deletes an option set to nothing, and one already absent is no
                        // error. A value goes in the option's place, string or list before.
                        if (value.isEmpty()) s.options.remove(option)
                        else s.options[option] = Opt(mutableListOf(value), list = false)
                    }
                    target != null -> target.type = value
                    ANONYMOUS.matches(section) -> error("`$op`: no section $section")
                    else -> live += Live(section, value, section, LinkedHashMap())
                }
            }
            "add_list" -> {
                require(option != null && '=' in rest) { "`$op`: add_list needs an option and a value" }
                val s = target ?: error("`$op`: no section $section")
                // Onto a string it makes a list of the two, where the string was; onto nothing, a
                // new list at the end of the section.
                val items = s.options[option]?.items ?: mutableListOf()
                s.options[option] = Opt(items.apply { add(unquote(rest.substringAfter('='))) }, list = true)
            }
            "delete" -> {
                // `delete pkg.section.option='value'` takes one item out of a list; no store sends it.
                require('=' !in rest) { "`$op`: FakeUci does not delete list items" }
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

/**
 * After a batch against [start]: exactly [deleted] gone; every other section as it was apart
 * from [edited] — per section, the options that changed, null for one removed — and
 * [editedLists], per section, the lists rewritten; and [added] new at the end, in that order.
 * The deletes are checked on their own first: a wrong section going is what these tests catch.
 */
internal fun FakeUci.assertLeaves(
    start: List<FakeUci.Section>,
    deleted: Set<String> = emptySet(),
    edited: Map<String, Map<String, String?>> = emptyMap(),
    editedLists: Map<String, Map<String, List<String>>> = emptyMap(),
    added: List<FakeUci.Section> = emptyList(),
) {
    assertEquals("$pkg: deleted", deleted, this.deleted)
    val kept = start.filterNot { it.id in deleted }.map { s ->
        val changes = edited[s.id].orEmpty()
        s.copy(
            options = s.options - changes.keys + changes.mapNotNull { (k, v) -> v?.let { k to it } },
            lists = s.lists + editedLists[s.id].orEmpty(),
        )
    }
    assertEquals("$pkg: sections", kept + added, sections)
}
