package com.vivekkaushik.wrtpulse.ops

/**
 * The guard between the provider's advice and the phone's clipboard.
 *
 * The answer's ADVICE section contains exact shell commands, and one tap copies them. But a
 * copy button that happily hands over `reboot` is a footgun with extra steps, so every command
 * line the screen offers is classified first: read-only commands copy on one tap, anything
 * that changes the router demands a second confirm tap, and anything destructive is never
 * offered at all — it is shown crossed out with the reason.
 *
 * Classification is conservative by design: anything the lists do not recognise is a CHANGE,
 * because an unrecognised command that turns out to be destructive is exactly the failure
 * this guard exists to prevent.
 */
enum class CommandSafety {
    /** Read-only: runs, looks, reports. Copies with one tap. */
    SAFE,

    /** Mutates config or state: `uci set`, `wifi`, service restarts, installs. Confirm tap. */
    CHANGE,

    /** Destructive or lethal to the session: never copyable, shown with the reason. */
    BLOCKED,
}

data class AnswerCommand(
    /** The command text as it would land in the clipboard. */
    val text: String,
    val safety: CommandSafety,
    /** Why a blocked command is blocked, or what a change command will change. */
    val reason: String?,
)

object SafeCommands {

    /** Commands that only read. Anything here must be impossible to make destructive. */
    private val SAFE_FIRST_TOKENS = setOf(
        "cat", "df", "dmesg", "ethtool", "free", "grep", "head", "hostapd_cli", "iw", "iwinfo",
        "logread", "ls", "nslookup", "ping", "ping6", "ps", "swconfig", "tail", "top", "traceroute",
        "ubus", "uci", "uptime", "wl", "opkg", "apk", "ifstatus", "iwlist", "ip", "brctl", "mtr",
        "tcpdump", "md5sum", "sha256sum", "which", "command", "printf", "echo", "date", "id",
        "netstat", "ss", "jsonfilter",
    )

    /**
     * Whole-line commands that must never reach a clipboard from this app, however they are
     * spelled — with arguments, flags, or after a `&&`. The advice prompt already forbids
     * recommending them; this catches the model not listening.
     */
    private val DESTRUCTIVE = Regex(
        """(?<![\w.-])(reboot|firstboot|sysupgrade|poweroff|halt|factory[- ]reset)(?![\w-])""",
        RegexOption.IGNORE_CASE,
    )

    /** Others that destroy state or brick boards when aimed wrong. */
    private val DESTRUCTIVE_TOOLS = Regex(
        """(?<![\w./-])(mtd\s+write|rm\s+-rf|dd\s+[^|]*of=/dev/\S+|mkfs|flashrom)(?![\w-])""",
        RegexOption.IGNORE_CASE,
    )

    /** Subcommands that flip a read-only tool into a changing one. */
    private val CHANGE_MARKERS = Regex(
        """\b(set|add(?:_list)?|del(?:ete|_list)?|rename|revert|commit|import|apply|up|down)\b""",
    )

    private val CHANGE_WORDS = Regex(
        """(?<![\w-])(install|remove|configure|restart|reload|start|stop|enable|disable|kill|killall|pkill|renew|release|flush)(?![\w-])""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Pulls the shell commands out of a plain-text answer. Only standalone command lines
     * qualify — a verb the app knows, alone on its line (after any bullet dash, or after a
     * "label:" prefix) — so prose that mentions `uci` in a sentence is never mangled into a
     * copy button.
     */
    fun extract(answer: String): List<AnswerCommand> {
        val seen = mutableSetOf<String>()
        return answer.lineSequence()
            .map { it.trim() }
            .map { line ->
                when {
                    line.startsWith("- ") -> line.drop(2).trim()
                    line.startsWith("• ") || line.startsWith("* ") -> line.drop(2).trim()
                    else -> line
                }
            }
            .filter { it.isNotBlank() }
            .mapNotNull { line -> normalize(line) ?: afterColon(line) }
            .mapNotNull { candidate ->
                if (candidate in seen) null
                else {
                    seen += candidate
                    AnswerCommand(candidate, classify(candidate), reasonFor(candidate))
                }
            }
            .toList()
    }

    /** "VERIFY FIRST: uci set …" → the command after the last label colon, if it is one. */
    private fun afterColon(line: String): String? {
        val idx = line.lastIndexOf(':')
        if (idx < 0 || idx == line.length - 1) return null
        return normalize(line.substring(idx + 1).trim())
    }

    /** Classifies one command string, whatever its source. */
    fun classify(command: String): CommandSafety {
        val cmd = command.trim()
        if (DESTRUCTIVE.containsMatchIn(cmd) || DESTRUCTIVE_TOOLS.containsMatchIn(cmd)) {
            return CommandSafety.BLOCKED
        }
        val first = firstToken(cmd) ?: return CommandSafety.CHANGE
        val known = first in SAFE_FIRST_TOKENS
        val pathLike = first.startsWith("/") || first.startsWith("./")
        return when {
            // A service action (/etc/init.d/x restart) or install is a change even though the
            // verb is not the first token.
            first.startsWith("/etc/init.d/") -> CommandSafety.CHANGE
            pathLike -> CommandSafety.CHANGE
            first in setOf("wifi", "ifup", "ifdown", "fw4", "fw3", "nft", "iptables") -> CommandSafety.CHANGE
            !known -> CommandSafety.CHANGE
            first == "uci" -> if (CHANGE_MARKERS.containsMatchIn(uciVerb(cmd))) CommandSafety.CHANGE else CommandSafety.SAFE
            first == "opkg" || first == "apk" ->
                if (CHANGE_WORDS.containsMatchIn(cmd)) CommandSafety.CHANGE else CommandSafety.SAFE
            first == "ip" -> if (CHANGE_MARKERS.containsMatchIn(ipVerb(cmd))) CommandSafety.CHANGE else CommandSafety.SAFE
            first == "swconfig" || first == "brctl" || first == "ebtables" ->
                if (CHANGE_MARKERS.containsMatchIn(cmd)) CommandSafety.CHANGE else CommandSafety.SAFE
            else -> CommandSafety.SAFE
        }
    }

    /** One human sentence for the chip beside the copy button. */
    fun reasonFor(command: String): String? {
        val cmd = command.trim()
        return when (classify(cmd)) {
            CommandSafety.BLOCKED -> {
                val m = DESTRUCTIVE.find(cmd) ?: DESTRUCTIVE_TOOLS.find(cmd)
                "refuses to copy '${m?.value ?: cmd}': it can take the router off the network " +
                    "or wipe it, and no clipboard should hand it over by accident"
            }
            CommandSafety.CHANGE -> "changes the router — read it, verify it applies to your setup, then run it yourself"
            CommandSafety.SAFE -> null
        }
    }

    /** First words that make a line a command, whether they read or change. */
    private val COMMAND_VERBS = SAFE_FIRST_TOKENS + setOf(
        "wifi", "ifup", "ifdown", "fw4", "fw3", "nft", "iptables", "ebtables", "conntrack",
        "sleep", "wget", "curl", "scp", "sed", "awk", "vi", "nano",
        // The destructive verbs belong here too: a line carrying one must be extracted so it
        // can be shown and refused — an invisible reboot is the one thing worse than a copied one.
        "reboot", "firstboot", "sysupgrade", "poweroff", "halt", "mtd", "rm", "dd", "mkfs", "flashrom",
    )

    /**
     * A line is a command candidate when its first word is one the router's shell would know,
     * or — for an unknown first word — when the line carries punctuation only a command has
     * (flags, paths, assignments, pipes). Prose that mentions a tool in a sentence is left
     * alone, so "Ping the router from a laptop" never becomes a copy button.
     */
    private fun normalize(line: String): String? {
        var text = line.trim().trim('`')
        text = text.trim()
        if (text.isEmpty()) return null
        if (!text.first().isLetter() && text.first() != '/') return null
        // Prose lines end with punctuation commands never use; strip a trailing period.
        text = text.trimEnd('.', ',')
        val first = firstToken(text) ?: return null
        if (!first.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == '/' }) return null
        if (first.first().isUpperCase()) return null // "Ping the router." is a sentence, not a command
        if (first !in COMMAND_VERBS && !first.startsWith("/etc/init.d/")) {
            // Unknown verb: only a line with command punctuation qualifies.
            val hasPunctuation = text.contains("&&") || text.contains("||") || text.contains('|') ||
                text.split(Regex("\\s+")).any {
                    it.startsWith("-") || it.contains('/') || it.contains('=') || it.contains('.')
                }
            if (!hasPunctuation) return null
        }
        return text
    }

    private fun firstToken(cmd: String): String? =
        cmd.split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() }

    /** `uci show wireless` → "show"; `uci -q show` → "show". */
    private fun uciVerb(cmd: String): String {
        val tokens = cmd.split(Regex("\\s+"))
        val verb = tokens.drop(1).firstOrNull { it !in setOf("-q", "-P", "-d", "-c", "-p") && !it.startsWith("-") }
        return verb.orEmpty()
    }

    /** `ip addr show` → "addr show"; flags skipped. */
    private fun ipVerb(cmd: String): String =
        cmd.split(Regex("\\s+")).drop(1)
            .dropWhile { it.startsWith("-") }
            .take(2)
            .joinToString(" ")
}
