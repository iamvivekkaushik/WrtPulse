package com.vivekkaushik.wrtpulse.data

/**
 * A store a screen can keep current by re-reading it on a timer.
 *
 * Every Network-tab store used to read the router once on first open and never again, so a
 * WAN pulled out of the wall stayed "Connected" until the app was restarted — while the
 * dashboard, driven by Telemetry's own loop, never went stale. `load()` is safe to repeat:
 * each store's ingest rebuilds only the read-only base (links, leases, neighbours); staged
 * edits are an overlay it never touches.
 */
interface Refreshable {
    val loaded: Boolean

    /** A write is in flight. It re-reads on its own; a timer firing into it would race it. */
    val applying: Boolean

    /** Anything else that should hold a refresh off — a scan that is adding a temp interface. */
    val refreshPaused: Boolean get() = false

    suspend fun load()
}
