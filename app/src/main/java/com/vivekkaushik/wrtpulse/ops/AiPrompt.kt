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
     * The ready-made analyses: one tap writes the question for the operator, phrased to aim
     * the provider at one subsystem without hiding the rest of the snapshot from it.
     */
    enum class Mode(val label: String, val question: String) {
        WIFI(
            "Wi-Fi",
            "Analyze the Wi-Fi side: radio health, channel and width, per-client signal and " +
                "retransmissions, encryption, and coverage. Which clients are suffering and why?",
        ),
        MESH(
            "Mesh & WDS",
            "Analyze the mesh/WDS backhaul: link quality between nodes, signal, retries and " +
                "failures on sta/mesh interfaces, package fitness (wpad), and roaming. " +
                "Is the backhaul the bottleneck?",
        ),
        PERFORMANCE(
            "Performance",
            "Analyze performance: CPU load over the sample window, memory pressure, storage, " +
                "per-interface throughput, and latency/loss to the gateway and the internet. " +
                "What is slow and what is it costing?",
        ),
        SECURITY(
            "Security",
            "Analyze security: wireless encryption, firewall zones and input policy, " +
                "exposure of services to the WAN, DHCP scope placement. What is exposed that should not be?",
        ),
        OUTAGE(
            "Internet outage",
            "The internet seems down or flaky. Use the gateway and internet pings to decide " +
                "whether the fault is before or after the first hop, check the WAN interface, " +
                "routes, DNS and the log. Where is the path broken?",
        ),
        LAN(
            "DHCP, bridge & STP",
            "Analyze the LAN plumbing: DHCP scopes and leases, bridges and their STP/loop " +
                "posture, VLANs, and address conflicts. Are there misconfigurations that break " +
                "connectivity for some clients?",
        ),
    }

    /**
     * The persona and the shape of the answer. Plain text on purpose: the answer renders in
     * a Compose column, not a browser, and asking for markdown only buys stray asterisks.
     */
    const val SYSTEM = """
You are a senior OpenWrt/Linux network engineer diagnosing faults and proposing a safe repair plan.
You receive one router snapshot collected over SSH, the deltas of a 60-second performance
sample when it ran, and the findings of the app's local rules; sometimes a question from
its operator.

Engineering rules:
- Separate OBSERVED facts from INFERENCE. Ground every claim in supplied data and quote actual
  values, interfaces, channels, addresses, or log lines. Never invent values.
- Check the whole system: firmware/package compatibility, CPU/RAM/storage, WAN/LAN routing,
  DHCP conflicts, firewall exposure, radio health, WDS/802.11s Mesh, bridge loops and STP.
- The local findings and the sample deltas were computed on the phone from raw counters —
  trust them, cite them as evidence, and build on them instead of restating them.
- Give each local finding its own line in the diagnosis and connect the repair step directly to
  that finding's evidence.
- Rank findings by impact and likelihood. Do not call a missing block a failure; call it
  UNVERIFIED and state the exact additional read-only check needed.
- Solve evidenced problems, not just describe them. For each actionable finding, give the
  least disruptive next step first, then a concrete repair only when the snapshot supports it.
  Explain which finding/evidence the repair addresses and what success should look like.
- If key details are missing, do not guess interface, section IDs, package names, addresses,
  or UCI values. Mark the fix UNVERIFIED and give the exact read-only check needed first.
- Do not recommend destructive actions, factory reset, firmware flashing, or blind reboot.
  Configuration commands must be marked VERIFY FIRST and include risk and rollback guidance.
  Prefer staged, reversible UCI changes: show a pre-check, proposed change, apply step, and
  rollback where the exact rollback is known. Never claim an action was performed.
- The app does not execute your commands. They are advice for the operator to review and copy;
  every command must be safe to show, and an unknown command is not automatically safe.
- The operator may not be an expert: plain words first, technical term second.

Answer in exactly this shape, with section names in caps on their own line:
VERDICT:
One line: HEALTHY, NEEDS ATTENTION, or AT RISK, followed by the biggest evidence-backed reason.
CONFIDENCE:
HIGH, MED, or LOW, with one sentence explaining data coverage and uncertainty.
FINDINGS:
Up to six bullets. Start each with HIGH, MED, or LOW; include OBSERVED evidence and impact.
ROOT CAUSE:
The most likely cause, or "UNVERIFIED — ..." when evidence is insufficient.
SOLUTION PLAN:
Up to five numbered steps, prioritized by impact and reversibility. For each: goal, evidence it
addresses, exact read-only check or VERIFY FIRST command, expected result, and risk. For a
supported configuration fix, include the exact proposed command(s), how to apply, and a known
rollback; if any value is uncertain, give checks instead of a speculative write. Say "No repair
needed" when no actionable fault is evidenced.
VALIDATION:
Two or three read-only commands/checks that prove whether the advice worked.
ANSWER:
Only when the operator asked a question; answer it directly before the other sections.

Keep the whole answer under 650 words. Plain text only — no markdown headers, no bold.
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
        "performance" to "PERFORMANCE (load, CPU, memory, interfaces, storage)",
        "overlay" to "STORAGE (df -k /overlay)",
        "wireless" to "WIRELESS CONFIG (uci show wireless)",
        "network" to "NETWORK CONFIG (uci show network)",
        "firewall" to "FIREWALL CONFIG (uci show firewall)",
        "routes" to "ROUTES (ip route and ip -6 route)",
        "wifi_status" to "LIVE WIRELESS STATUS (ubus call network.wireless status)",
        "packages" to "INSTALLED WIRELESS PACKAGES (opkg/apk query)",
        "dhcp" to "DHCP LEASES (/tmp/dhcp.leases)",
        "dhcpconf" to "DHCP CONFIG (uci show dhcp)",
        "assoc" to "WIFI CLIENTS (iw station dump / iwinfo assoclist)",
        "log" to "RECENT LOG WARNINGS (logread | grep -Ei 'error|fail|warn|crit' | tail -40)",
    )

    /**
     * The user message: what was asked, the local findings and sample deltas, then the router
     * state as labeled blocks. Sections the router did not answer are left out rather than
     * sent empty — a missing block should read as "not available", not as "config absent".
     */
    fun buildUserContent(
        sections: Map<String, String>,
        question: String,
        findings: List<String> = emptyList(),
        temporal: List<String> = emptyList(),
        health: String? = null,
    ): String {
        val blocks = sections.entries
            .filter { it.value.isNotBlank() }
            .joinToString("\n\n") { (name, body) -> block(LABELS[name] ?: name.uppercase(), body) }
        val ask = question.trim().ifEmpty { "none" }
        val context = buildString {
            if (health != null) append("NETWORK HEALTH: $health\n\n")
            if (temporal.isNotEmpty()) {
                append("60-SECOND PERFORMANCE SAMPLE (deltas between three samples)\n")
                temporal.forEach { append(it).append('\n') }
                append("\n\n")
            }
            if (findings.isNotEmpty()) {
                append("LOCAL DIAGNOSTIC FINDINGS (computed on the phone from raw counters)\n")
                findings.forEach { append("- ").append(it).append('\n') }
                append("\n\n")
            }
        }
        return "Analyze this OpenWrt router state and tell me what is wrong or worth improving.\n" +
            "\nOperator question: $ask\n" +
            "\nRouter state follows. Secrets were redacted before this text left the phone.\n" +
            "\n$context\n$blocks"
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
