package com.vivekkaushik.wrtpulse.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import org.json.JSONObject
import java.security.MessageDigest

/** One .ipk inside an offline bundle, in dependency order. */
data class OfflinePackage(
    val name: String,
    val version: String,
    val file: String,
    val bytes: Long,
    val description: String,
    val root: Boolean,
)

/**
 * The wireless .ipk set the APK itself carries, for routers with no internet. Bundles are
 * built per release and target from the official feeds with their full dependency closure,
 * so installing them needs nothing from the router's feed — only opkg, which is part of
 * every 23.05 image.
 */
data class OfflineBundle(
    val release: String,
    val target: String,
    val arch: String,
    val totalBytes: Long,
    val packages: List<OfflinePackage>,
) {
    val roots: List<OfflinePackage> get() = packages.filter { it.root }
}

/** What /etc/openwrt_release says about the router in hand. */
data class RouterId(val release: String, val target: String, val arch: String) {
    val summary: String
        get() = listOf("OpenWrt $release", target).filter { it.isNotBlank() }.joinToString(" · ")
}

enum class OfflinePhase(val label: String) {
    REMOVING("removing the old wpad"),
    UPLOADING("uploading"),
    INSTALLING("installing"),
}

data class OfflineProgress(
    val phase: OfflinePhase,
    val done: Int,
    val total: Int,
    val current: String,
)

/**
 * Offline wireless bundles, read from the APK's own assets and installed onto the router
 * with its own opkg — the router never needs a working feed, which is the point: a box
 * with no uplink still becomes configurable.
 *
 * Assets are trusted only as far as opkg checks them: files are streamed to /tmp, the
 * md5 the router computed over the received bytes is compared before anything is
 * installed, and /tmp is emptied again whether the install succeeded or not.
 */
class OfflineBundles(private val context: Context, private val session: RouterSession) {

    var bundles by mutableStateOf(emptyList<OfflineBundle>()); private set
    var loaded by mutableStateOf(false); private set

    /** Null until [probe] has run; stays null when the router is not an OpenWrt release this knows. */
    var routerId by mutableStateOf<RouterId?>(null); private set
    var probed by mutableStateOf(false); private set

    var progress by mutableStateOf<OfflineProgress?>(null); private set

    /** The bundle built for exactly this release and target. */
    val exactMatch: OfflineBundle? get() = matchExact(bundles, routerId)

    /**
     * Same architecture, a newer point release than the router's. The closure is userspace-only —
     * no kernel modules — so a near match usually works, but opkg is still left to be the judge:
     * it refuses any dependency whose installed version does not satisfy the pin. Older bundles
     * are never offered: downgrading packages under a newer image is not a thing opkg supports.
     */
    val nearMatch: OfflineBundle? get() = matchNear(bundles, routerId)

    /** Reads the manifest; safe to call repeatedly, and never throws — assets are ours. */
    fun load() {
        if (loaded) return
        runCatching {
            val json = context.assets.open(MANIFEST_ASSET).bufferedReader().use { it.readText() }
            bundles = parseManifest(json)
        }
        loaded = true
    }

    /** One cheap read of /etc/openwrt_release — the file every OpenWrt image ships. */
    suspend fun probe() {
        if (probed) return
        try {
            val out = session.exec(PROBE, timeoutMs = 15_000)
            val fields = out.stdout.lines().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i > 0) line.take(i).trim() to line.substring(i + 1).trim() else null
            }.toMap()
            routerId = RouterId(
                release = fields["release"].orEmpty(),
                target = fields["target"].orEmpty(),
                arch = fields["arch"].orEmpty(),
            ).takeIf { it.release.isNotBlank() }
        } catch (_: SshException) {
            routerId = null
        } finally {
            probed = true
        }
    }

    /**
     * Installs one bundle end to end: what is already installed is skipped, a conflicting
     * wpad/hostapd variant is removed first, every .ipk is streamed to /tmp and md5-checked,
     * then one opkg command installs the lot in dependency order and /tmp is cleaned either
     * way. The returned string is the toast; [progress] is live while it runs.
     */
    suspend fun installBundle(store: PackageStore): String {
        val bundle = exactMatch ?: nearMatch ?: return "Failed: no offline bundle for this router."
        if (store.manager != "opkg") {
            return "Failed: offline bundles are .ipk files — this router uses ${store.manager}."
        }
        val install = resolveInstallSet(bundle, store.installedNames)
        if (install.isEmpty()) return "Everything in the offline bundle is already installed."

        // Only when wpad itself is going in does anything have to come out: installing a
        // second wpad/hostapd variant over a live one is the one conflict opkg will not
        // resolve on its own.
        val removing = if (install.any { it.name == "wpad-mesh-mbedtls" }) {
            conflictingWpad(store.installedNames)
        } else {
            emptyList()
        }

        progress = OfflineProgress(OfflinePhase.REMOVING, 0, removing.size, removing.joinToString(" "))
        return try {
            if (removing.isNotEmpty()) {
                val out = session.exec("opkg remove ${removing.joinToString(" ")}", timeoutMs = 120_000)
                if (!out.ok) {
                    return "Failed: could not remove ${removing.joinToString(", ")} — " +
                        lastLine(out.stderr, out.stdout)
                }
            }

            session.exec("mkdir -p $REMOTE_DIR && rm -f $REMOTE_DIR/*.ipk", timeoutMs = 15_000)
            var done = 0
            for (pkg in install) {
                progress = OfflineProgress(OfflinePhase.UPLOADING, done, install.size, pkg.name)
                val bytes = runCatching {
                    context.assets.open("offline/${bundle.release}/${bundle.arch}/${pkg.file}")
                        .use { it.readBytes() }
                }.getOrNull() ?: return "Failed: asset ${pkg.file} missing from the app."
                val md5 = MessageDigest.getInstance("MD5").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                val out = session.execWithInput(
                    "cat > $REMOTE_DIR/${pkg.file} && md5sum $REMOTE_DIR/${pkg.file}",
                    bytes,
                    timeoutMs = 120_000,
                )
                if (!out.ok) return "Failed: upload ${pkg.name} — ${lastLine(out.stderr, out.stdout)}"
                val remote = out.stdout.trim().substringBefore(' ').lowercase()
                if (remote != md5) {
                    return "Failed: ${pkg.name} arrived damaged (md5 mismatch) — nothing was installed."
                }
                done++
            }

            progress = OfflineProgress(OfflinePhase.INSTALLING, install.size, install.size, "")
            val files = install.joinToString(" ") { "$REMOTE_DIR/${it.file}" }
            val out = session.exec(
                "opkg install $files; rc=\$?; rm -rf $REMOTE_DIR; exit \$rc",
                timeoutMs = 300_000,
            )
            if (out.ok) {
                store.load()
                val skipped = bundle.packages.size - install.size
                "Installed ${install.size} offline packages" +
                    if (skipped > 0) " ($skipped were already present)" else ""
            } else {
                "Failed: ${lastLine(out.stderr, out.stdout)}"
            }
        } catch (e: SshException) {
            "Failed: ${e.message}"
        } finally {
            progress = null
        }
    }

    companion object {

        private const val MANIFEST_ASSET = "offline/manifest.json"
        private const val REMOTE_DIR = "/tmp/wrtpulse-off"

        /** `. /etc/openwrt_release` — the authoritative release/target/arch triple. */
        private const val PROBE =
            ". /etc/openwrt_release 2>/dev/null; echo release=\$DISTRIB_RELEASE; " +
                "echo target=\$DISTRIB_TARGET; echo arch=\$DISTRIB_ARCH"

        fun parseManifest(json: String): List<OfflineBundle> {
            val root = JSONObject(json)
            return root.getJSONArray("bundles").let { arr ->
                (0 until arr.length()).map { i ->
                    val b = arr.getJSONObject(i)
                    val pkgs = b.getJSONArray("packages").let { pa ->
                        (0 until pa.length()).map { j ->
                            val p = pa.getJSONObject(j)
                            OfflinePackage(
                                name = p.getString("name"),
                                version = p.optString("version"),
                                file = p.getString("file"),
                                bytes = p.optLong("bytes"),
                                description = p.optString("description"),
                                root = p.optBoolean("root"),
                            )
                        }
                    }
                    OfflineBundle(
                        release = b.getString("release"),
                        target = b.optString("target"),
                        arch = b.getString("arch"),
                        totalBytes = b.optLong("totalBytes"),
                        packages = pkgs,
                    )
                }
            }
        }

        /** The bundle built for exactly this release and this target or arch. */
        fun matchExact(bundles: List<OfflineBundle>, id: RouterId?): OfflineBundle? =
            bundles.firstOrNull { b ->
                id != null && b.release == id.release && (b.target == id.target || b.arch == id.arch)
            }

        /** Same arch, a strictly newer point release — downgrades are never offered. */
        fun matchNear(bundles: List<OfflineBundle>, id: RouterId?): OfflineBundle? =
            bundles
                .filter { b -> id != null && b.arch == id.arch && b.release > id.release }
                .maxByOrNull { it.release }

        /**
         * What a bundle actually has to install on one router. Installed names are skipped,
         * and `hostapd` is dropped whenever wpad-mesh-mbedtls is or will be providing it —
         * they conflict, and the wpad covers everything hostapd does plus the client and
         * mesh sides. That resolution is why both can sit in one curated list.
         */
        fun resolveInstallSet(bundle: OfflineBundle, installed: Set<String>): List<OfflinePackage> {
            val wpadProvided = "wpad-mesh-mbedtls" in installed ||
                bundle.packages.any { it.name == "wpad-mesh-mbedtls" && it.name !in installed }
            return bundle.packages.filter { pkg ->
                pkg.name !in installed && !(wpadProvided && pkg.name == "hostapd")
            }
        }

        /**
         * Installed wpad/hostapd variants that cannot coexist with wpad-mesh-mbedtls — its own
         * Conflicts list, plus the `wpad-basic-*` the stock images ship, minus `hostapd-common`,
         * which every variant including this one shares and depends on.
         */
        private val WPAD_CONFLICTS = setOf(
            "hostapd", "hostapd-basic", "hostapd-basic-openssl", "hostapd-basic-wolfssl",
            "hostapd-basic-mbedtls", "hostapd-mini", "hostapd-openssl", "hostapd-wolfssl",
            "hostapd-mbedtls", "wpad", "wpad-basic", "wpad-basic-openssl",
            "wpad-basic-wolfssl", "wpad-basic-mbedtls", "wpad-mini", "wpad-openssl",
            "wpad-wolfssl", "wpad-mesh-openssl", "wpad-mesh-wolfssl",
        )

        fun conflictingWpad(installed: Set<String>): List<String> =
            installed.filter { it in WPAD_CONFLICTS }

        private fun lastLine(stderr: String, stdout: String): String =
            (stderr.trim().ifEmpty { stdout.trim() })
                .lines().lastOrNull { it.isNotBlank() } ?: "no output"
    }
}
