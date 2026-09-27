package com.vivekkaushik.wrtpulse.data

/**
 * A batch as the changes it makes, in order: each path it touches, with what that path holds
 * afterwards — a `set` its value, a `delete` nothing. A list is rewritten as a `delete` and
 * then an `add_list` per item, so an `add_list` joins the change on its path just before it.
 */
internal fun batchChanges(ops: List<String>): List<Pair<String, List<String>>> {
    val out = mutableListOf<Pair<String, MutableList<String>>>()
    ops.forEach { op ->
        val rest = op.substringAfter(' ')
        val path = rest.substringBefore('=')
        val value = rest.substringAfter('=', "").removeSurrounding("'").replace("'\\''", "'")
        when (op.substringBefore(' ')) {
            "set" -> out += path to mutableListOf(value)
            "delete" -> out += path to mutableListOf()
            "add_list" ->
                if (out.lastOrNull()?.first == path) out.last().second += value
                else out += path to mutableListOf(value)
            else -> error("not a batch line: $op")
        }
    }
    return out
}

/**
 * A store's review lines as the same changes: `- path='old'` then `+ path='new'` is an edit, a
 * `-` line alone is a removal, and a list's `-` lines then its `+` lines are one rewrite.
 */
internal fun reviewChanges(diff: List<Pair<String, Boolean>>): List<Pair<String, List<String>>> {
    val out = mutableListOf<Pair<String, MutableList<String>>>()
    diff.forEach { (line, added) ->
        val body = line.drop(2)
        val path = body.substringBefore('=')
        if (out.lastOrNull()?.first != path) out += path to mutableListOf()
        if (added) out.last().second += body.substringAfter('=').removeSurrounding("'")
    }
    return out
}
