package com.vivekkaushik.wrtpulse.net

import android.content.Context
import android.net.ConnectivityManager
import com.vivekkaushik.wrtpulse.db.WrtDb
import java.io.File
import java.net.Inet4Address

/**
 * App-level singletons for the transport layer. Compose screens reach the network only
 * through what's held here; nothing below the UI ever sees an Android Context.
 */
object WrtRuntime {

    lateinit var hostKeys: HostKeyStore
        private set
    lateinit var client: SshClient
        private set
    lateinit var db: WrtDb
        private set

    /** Seals and opens credential blobs with the app's Keystore key. */
    val vault by lazy { KeystoreCrypto() }

    /** The connection the app is currently driving. Replaced on router switch. */
    var session: RouterSession? = null

    fun init(context: Context) {
        if (::hostKeys.isInitialized) return
        hostKeys = HostKeyStore(File(context.filesDir, "known_hosts"))
        client = JschSshClient(hostKeys)
        db = WrtDb.build(context)
    }

    /** IPv4 default gateway of the active network — the onboarding suggestion card. */
    fun defaultGateway(context: Context): String? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        props.routes
            .firstOrNull { it.destination.prefixLength == 0 && it.gateway is Inet4Address }
            ?.gateway?.hostAddress
    }.getOrNull()
}
