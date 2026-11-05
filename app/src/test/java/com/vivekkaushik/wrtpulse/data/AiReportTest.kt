package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.ops.Domain
import com.vivekkaushik.wrtpulse.ops.Finding
import com.vivekkaushik.wrtpulse.ops.HealthScore
import com.vivekkaushik.wrtpulse.ops.Severity
import com.vivekkaushik.wrtpulse.ops.TemporalStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiReportTest {

    private val findings = listOf(
        Finding(
            severity = Severity.HIGH,
            domain = Domain.MESH,
            title = "Mesh/WDS backhaul is weak at -84 dBm",
            evidence = "tx retries +1,875 in 60 s, tx failed +21 — worst link aa:bb:cc:dd:ee:ff on wlan1 at -84 dBm",
            command = "iw dev wlan1 station dump",
            raw = "wlan1 aa:bb:cc:dd:ee:ff: retries +1875, failed +21, -84 dBm",
        ),
    )

    private val temporal = TemporalStats(
        windowSeconds = 60.0,
        sampleCount = 3,
        cpuPct = 18,
        load1 = 0.42,
        loadDelta = 0.1,
        memPct = 52,
        memPctDelta = 1,
        retryDelta = 1875,
        failedDelta = 21,
        worstSignalDbm = -84,
        gateway = com.vivekkaushik.wrtpulse.ops.PingStats(9, 9, 2.1, 4.0),
        internet = com.vivekkaushik.wrtpulse.ops.PingStats(9, 9, 18.5, 44.0),
        stations = emptyList(),
        interfaces = emptyList(),
    )

    private val health = HealthScore.of(78, 82, 64, 91, 76)

    private val parts = ReportParts(
        routerLabel = "Phicomm K2G · 23.05.5 · 192.168.8.1",
        generatedAt = "2026-11-03 22:40",
        health = health,
        temporal = temporal,
        findings = findings,
        question = "why is the mesh slow?",
        answer = "VERDICT:\nNEEDS ATTENTION — the backhaul is retransmitting.",
    )

    @Test
    fun `markdown carries health findings sample and answer`() {
        val md = AiReport.markdown(parts, sanitized = false)
        assertTrue(md.contains("# WrtPulse — Router analysis report"))
        assertTrue(md.contains("**Router:** Phicomm K2G · 23.05.5 · 192.168.8.1"))
        assertTrue(md.contains("**Overall: 78/100 — NEEDS ATTENTION**"))
        assertTrue(md.contains("| 82 | 64 | 91 | 76 |"))
        assertTrue(md.contains("### HIGH — Mesh/WDS backhaul is weak at -84 dBm"))
        assertTrue(md.contains("- Evidence: tx retries +1,875"))
        assertTrue(md.contains("- Verify with: `iw dev wlan1 station dump`"))
        assertTrue(md.contains("## 60-second sample"))
        assertTrue(md.contains("## AI analysis"))
        assertTrue(md.contains("NEEDS ATTENTION — the backhaul is retransmitting"))
        assertTrue(md.contains("**Question:** why is the mesh slow?"))
    }

    @Test
    fun `text report has the same story without markdown syntax`() {
        val txt = AiReport.text(parts, sanitized = false)
        assertTrue(txt.contains("NETWORK HEALTH"))
        assertTrue(txt.contains("Overall: 78/100 — NEEDS ATTENTION"))
        assertTrue(txt.contains("[HIGH] Mesh/WDS backhaul is weak at -84 dBm"))
        assertTrue(txt.contains("AI ANALYSIS"))
        assertFalse(txt.contains("### "))
        assertFalse(txt.contains("**"))
    }

    @Test
    fun `sanitize masks MAC tails and public addresses but keeps the LAN`() {
        val report = AiReport.text(parts, sanitized = true)
        // The MAC keeps its OUI, loses its identity.
        assertTrue(report.contains("aa:bb:cc:XX:XX:XX"))
        assertFalse(report.contains("aa:bb:cc:dd:ee:ff"))
        // The private router address survives — the report is about it.
        assertTrue(report.contains("192.168.8.1"))
        // A public address anywhere in the answer would be masked.
        val withPublic = AiReport.text(
            parts.copy(answer = "The line IP is 85.195.10.20 today."),
            sanitized = true,
        )
        assertFalse(withPublic.contains("85.195.10.20"))
        assertTrue(withPublic.contains("x.x.x.x"))
        // Not sanitized: it stays.
        val honest = AiReport.text(parts.copy(answer = "The line IP is 85.195.10.20 today."), sanitized = false)
        assertTrue(honest.contains("85.195.10.20"))
    }

    @Test
    fun `sanitize leaves private and link-local addresses alone`() {
        val text = "router 10.0.0.1, lab 172.20.1.5, home 192.168.1.1, link fe80::1, ula fd00::5, public 8.8.8.8"
        val out = AiReport.sanitize(text)
        assertTrue(out.contains("10.0.0.1"))
        assertTrue(out.contains("172.20.1.5"))
        assertTrue(out.contains("192.168.1.1"))
        assertTrue(out.contains("fe80::1"))
        assertTrue(out.contains("fd00::5"))
        assertFalse(out.contains("8.8.8.8"))
        assertTrue(out.contains("x.x.x.x"))
    }

    @Test
    fun `an empty findings list says so instead of a blank section`() {
        val empty = AiReport.markdown(parts.copy(findings = emptyList()), sanitized = false)
        assertTrue(empty.contains("None — the phone-side rules found nothing worth flagging"))
    }

    @Test
    fun `severity tags match the finding rows`() {
        assertEquals("HIGH", AiReport.severityTag(Severity.HIGH))
        assertEquals("MED", AiReport.severityTag(Severity.MED))
        assertEquals("LOW", AiReport.severityTag(Severity.LOW))
    }
}
