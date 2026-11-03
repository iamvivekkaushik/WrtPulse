package com.vivekkaushik.wrtpulse.ops

/**
 * The words sent to the AI provider, and the scrubbing that happens before them.
 *
 * The app's bargain is "show the command"; the AI step keeps it: the analysis screen shows
 * the exact payload before it is sent, and the payload is built from what [redact] left
 * behind — a uci line with a key or password in it never reaches the provider.
 */
object AiPrompt {

    /**
     * The persona and the shape of the answer. Plain text on purpose: the answer renders in
     * a Compose column, not a browser, and asking for markdown only buys stray asterisks.
     */
    const val SYSTEM = """
You are a senior OpenWrt and Linux network engineer. You are given one router's state
collected over SSH, and sometimes a question from its operator.

Rules:
- Ground every claim in the given data and quote the actual numbers. Never invent values.
- When you recommend a change, give the exact command (uci or shell) and one line on what
  it risks or makes irreversible.
- Mark anything you cannot verify from the data alone as UNVERIFIED.
- The operator may not be an expert: plain words first, jargon after.

Answer in exactly this shape, section names in caps on their own line:
VERDICT:
One line — healthy, needs attention, or at risk, and the single biggest reason.
FINDINGS:
Up to six, each starting HIGH, MED or LOW, then what, the evidence, and why it matters.
ADVICE:
Up to five concrete actions, most important first, each with its command.
If the operator asked a question, put ANSWER: first and answer it directly.

Keep the whole answer under 350 words. Plain text only — no markdown headers, no bold.
"""

    /** One section's share of the payload. A router with 200 clients still fits one request. */
    const val MAX_SECTION_LINES = 200
    const val MAX_SECTION_CHARS = 6_000

    /**
     * uci option names whose values are secrets: `wireless.default_radio0.key='passphrase'`,
     * `network.wan.password='pppoe-secret'`. Matched on the whole trimmed line so a value
     * containing "=" cannot smuggle the real key through the cut.
     */
    private val SECRET_OPTION = Regex(
        """^[^=]+\.(keys?|psks?|wpa_psk|passwords?|passwds?|secrets?|tokens?)\s*=.+""",
        RegexOption.IGNORE_CASE,
    )

    /** Replaces every secret-valued uci line's value with a marker, keeping the option name. */
    fun redact(text: String): String =
        text.lineSequence().joinToString("\n") { line ->
            if (SECRET_OPTION.matches(line.trim())) line.substringBefore('=') + "='<redacted>'"
            else line
        }

    /** The labels the provider sees, with the command each block came from. */
    private val LABELS = mapOf(
        "board" to "BOARD (ubus call system board)",
        "info" to "SYSTEM (ubus call system info)",
        "overlay" to "STORAGE (df -k /overlay)",
        "wireless" to "WIRELESS CONFIG (uci show wireless)",
        "network" to "NETWORK CONFIG (uci show network)",
        "dhcp" to "DHCP LEASES (/tmp/dhcp.leases)",
        "assoc" to "WIFI CLIENTS (iw station dump / iwinfo assoclist)",
        "log" to "RECENT LOG WARNINGS (logread | grep -Ei 'error|fail|warn|crit' | tail -40)",
    )

    /**
     * The user message: what was asked, then the router state as labeled blocks. Sections the
     * router did not answer are left out rather than sent empty — a missing block should read
     * as "not available", not as "config absent".
     */
    fun buildUserContent(sections: Map<String, String>, question: String): String {
        val blocks = sections.entries
            .filter { it.value.isNotBlank() }
            .joinToString("\n\n") { (name, body) -> block(LABELS[name] ?: name.uppercase(), body) }
        val ask = question.trim().ifEmpty { "none" }
        return "Analyze this OpenWrt router state and tell me what is wrong or worth improving.\n" +
            "\nOperator question: $ask\n" +
            "\nRouter state follows. Secrets were redacted before this text left the phone.\n" +
            "\n$blocks"
    }

    private fun block(label: String, body: String): String {
        val lines = body.lineSequence().toList()
        val trimmedLines = if (lines.size > MAX_SECTION_LINES) lines.take(MAX_SECTION_LINES) else null
        val note = when {
            trimmedLines != null -> "\n(only the first $MAX_SECTION_LINES lines are included)"
            body.length > MAX_SECTION_CHARS -> "\n(only the first $MAX_SECTION_CHARS characters are included)"
            else -> ""
        }
        val content = (trimmedLines?.joinToString("\n") ?: body).let {
            if (it.length > MAX_SECTION_CHARS) it.take(MAX_SECTION_CHARS) else it
        }
        return "### $label\n$content$note"
    }
}
