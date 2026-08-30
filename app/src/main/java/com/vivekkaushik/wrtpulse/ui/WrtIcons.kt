package com.vivekkaushik.wrtpulse.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// Icon glyphs traced from the design's inline SVGs (24x24 viewBox, round stroked paths).
// All are drawn white and tinted at the call site.

private fun stroked(name: String, strokeWidth: Float, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        for (d in paths) addPath(
            pathData = addPathNodes(d),
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()

private fun filled(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        for (d in paths) addPath(pathData = addPathNodes(d), fill = SolidColor(Color.White))
    }.build()

object WrtIcons {
    // Bottom navigation
    val Dashboard = stroked("dashboard", 1.7f, "M3 13h4l2.5-7 4 12 2.5-7H21")
    val Network = stroked(
        "network", 1.7f,
        "M12 3.5 A8.5 8.5 0 1 1 11.99 3.5 Z",
        "M3.5 12h17",
        "M12 3.5c-5.5 5-5.5 12 0 17",
        "M12 3.5c5.5 5 5.5 12 0 17",
    )
    val Clients = stroked(
        "clients", 1.7f,
        "M4.5 5.5h9.5a1.5 1.5 0 0 1 1.5 1.5v6a1.5 1.5 0 0 1-1.5 1.5H4.5A1.5 1.5 0 0 1 3 13V7a1.5 1.5 0 0 1 1.5-1.5Z",
        "M7 17.5h5.5",
        "M9.7 14.5v3",
        "M18.2 10.5h2.1a1.2 1.2 0 0 1 1.2 1.2v6.1a1.2 1.2 0 0 1-1.2 1.2h-2.1a1.2 1.2 0 0 1-1.2-1.2v-6.1a1.2 1.2 0 0 1 1.2-1.2Z",
    )
    val Terminal = stroked(
        "terminal", 1.7f,
        "M5 4.5h14a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-11a2 2 0 0 1 2-2Z",
        "M7 9.5l3 2.5-3 2.5",
        "M12.5 15H17",
    )
    val System = stroked(
        "system", 1.7f,
        "M4 7.5h16", "M4 12h16", "M4 16.5h16",
        "M9 5.4 A2.1 2.1 0 1 1 8.99 5.4 Z",
        "M15 9.9 A2.1 2.1 0 1 1 14.99 9.9 Z",
        "M7 14.4 A2.1 2.1 0 1 1 6.99 14.4 Z",
    )

    // Common
    val Search = stroked("search", 1.7f, "M11 4 A7 7 0 1 1 10.99 4 Z", "M20 20l-3.2-3.2")
    val MoreVert = filled(
        "more",
        "M12 3.8 A1.7 1.7 0 1 1 11.99 3.8 Z",
        "M12 10.3 A1.7 1.7 0 1 1 11.99 10.3 Z",
        "M12 16.8 A1.7 1.7 0 1 1 11.99 16.8 Z",
    )
    val Plus = stroked("plus", 2f, "M12 5v14", "M5 12h14")
    val ChevronDown = stroked("chevDown", 2.2f, "M6 9l6 6 6-6")
    val ChevronUp = stroked("chevUp", 2.2f, "M6 15l6-6 6 6")
    val ChevronRight = stroked("chevRight", 2f, "M9 6l6 6-6 6")
    val ChevronLeft = stroked("chevLeft", 2f, "M15 6l-6 6 6 6")
    val Check = stroked("check", 2.2f, "M4.5 12.5l5 5 10-11")
    val Close = stroked("close", 2f, "M6 6l12 12", "M18 6L6 18")
    val Eye = stroked(
        "eye", 1.6f,
        "M2 12s3.5-6.5 10-6.5S22 12 22 12s-3.5 6.5-10 6.5S2 12 2 12z",
        "M12 9.4 A2.6 2.6 0 1 1 11.99 9.4 Z",
    )
    val Qr = stroked(
        "qr", 1.6f,
        "M4 4h7v7H4Z", "M13 4h7v7h-7Z", "M4 13h7v7H4Z", "M13 13h3v3h-3z", "M17 17h3v3h-3z",
    )
    val Copy = stroked(
        "copy", 1.7f,
        "M11 9h7a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-7a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2Z",
        "M5 15V6a2 2 0 0 1 2-2h9",
    )
    val Paste = stroked(
        "paste", 1.7f,
        "M9 4h6v3H9Z",
        "M15 5h2a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h2",
        "M9 13h6", "M9 16h4",
    )
    val Pencil = stroked("pencil", 1.8f, "M4 20l4-1L20 7l-3-3L5 16l-1 4z")
    val RadioWaves = stroked(
        "radio", 1.6f,
        "M12 10 A2 2 0 1 1 11.99 10 Z",
        "M16.2 7.8a6 6 0 010 8.4", "M7.8 16.2a6 6 0 010-8.4",
        "M19 5a10 10 0 010 14", "M5 19A10 10 0 015 5",
    )
    val Blocked = stroked("blocked", 1.7f, "M12 3.5 A8.5 8.5 0 1 1 11.99 3.5 Z", "M6 6l12 12")
    val WiredDevice = stroked(
        "wired", 1.6f,
        "M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Z",
        "M8 12h8", "M8 8.5h8", "M8 15.5h4",
    )

    /**
     * An RJ45 plug with its cable — housing, three contact stripes, the latch, and the lead
     * going off the bottom. [WiredDevice] is a panel with three lines on it, which reads as
     * a document long before it reads as a network cable.
     */
    val Ethernet = stroked(
        "ethernet", 1.6f,
        // The jack, landscape — a port really is wider than tall, and the latch slot on top
        // is the cue that says ethernet rather than "some socket".
        "M4.3 8.6h15.4a1.4 1.4 0 0 1 1.4 1.4v5a1.4 1.4 0 0 1-1.4 1.4H4.3a1.4 1.4 0 0 1-1.4-1.4V10a1.4 1.4 0 0 1 1.4-1.4Z",
        // latch slot
        "M10.1 8.6V6.4h3.8v2.2",
        // contacts, hanging up from the mating face
        "M7.2 16.4v-2.6", "M12 16.4v-2.6", "M16.8 16.4v-2.6",
    )
    val Warning = stroked("warning", 1.7f, "M12 3L1.8 20.2h20.4L12 3z", "M12 10v4.5", "M12 17.5v.5")
    val Shield = stroked(
        "shield", 1.4f,
        "M12 2l8 3.5v5.5c0 5-3.4 8.6-8 10.5-4.6-1.9-8-5.5-8-10.5V5.5L12 2z",
        "M12 8v4.5", "M12 15.8v.4",
    )
    val ShareUp = stroked(
        "share", 1.6f,
        "M12 3v12", "M8 7l4-4 4 4",
        "M4 14v5a2 2 0 002 2h12a2 2 0 002-2v-5",
    )
    val Lightning = stroked("lightning", 1.6f, "M13 2L4.5 13.5h6L10 22l8.5-11.5h-6L13 2z")
    val PlayFilled = filled("play", "M7 4.5v15l12-7.5-12-7.5z")

    // Dashboard quick actions
    val Reboot = stroked("reboot", 1.7f, "M12 2v9", "M17.6 5.2a8 8 0 11-11.2 0")
    val GuestWifi = stroked(
        "guestWifi", 1.7f,
        "M4 9.5a12 12 0 0116 0", "M7 13a8 8 0 0110 0", "M10 16.5a4 4 0 014 0",
        "M12 18.3 A1.1 1.1 0 1 1 11.99 18.3 Z",
    )
    val Speedtest = stroked(
        "speedtest", 1.7f,
        "M4 14a8 8 0 0116 0", "M12 14l4-4.5",
        "M12 12.6 A1.4 1.4 0 1 1 11.99 12.6 Z",
    )
    val Prompt = stroked("prompt", 1.7f, "M5 8l3.5 3L5 14", "M11 15h7")

    // System screen rows
    val Backup = stroked(
        "backup", 1.6f,
        "M4.5 4h15a1.5 1.5 0 0 1 1.5 1.5V7.5A1.5 1.5 0 0 1 19.5 9h-15A1.5 1.5 0 0 1 3 7.5V5.5A1.5 1.5 0 0 1 4.5 4Z",
        "M5 9v9a2 2 0 002 2h10a2 2 0 002-2V9", "M10 13h4",
    )
    val Firmware = stroked(
        "firmware", 1.6f,
        "M7 5h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2Z",
        "M9 2v3", "M15 2v3", "M9 19v3", "M15 19v3",
        "M2 9h3", "M2 15h3", "M19 9h3", "M19 15h3",
    )
    val Services = stroked(
        "services", 1.6f,
        "M12 9 A3 3 0 1 1 11.99 9 Z",
        "M12 2v3", "M12 19v3", "M2 12h3", "M19 12h3",
        "M4.9 4.9l2.1 2.1", "M17 17l2.1 2.1", "M19.1 4.9L17 7", "M7 17l-2.1 2.1",
    )
    val Packages = stroked(
        "packages", 1.6f,
        "M21 8l-9-5-9 5 9 5 9-5z", "M3 8v8l9 5 9-5V8", "M12 13v8",
    )
    val Clock = stroked("clock", 1.6f, "M12 3.5 A8.5 8.5 0 1 1 11.99 3.5 Z", "M12 7v5l3.5 2")

    // Logs
    val LiveLogs = stroked(
        "liveLogs", 1.6f,
        "M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1Z",
        "M8 9h8", "M8 12.5h8", "M8 16h5",
    )
}
