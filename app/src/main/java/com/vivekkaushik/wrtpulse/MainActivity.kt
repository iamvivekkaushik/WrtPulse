package com.vivekkaushik.wrtpulse

import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.vivekkaushik.wrtpulse.data.BackupStore
import com.vivekkaushik.wrtpulse.data.FirewallStore
import com.vivekkaushik.wrtpulse.data.RouteStore
import com.vivekkaushik.wrtpulse.data.GuestStore
import com.vivekkaushik.wrtpulse.data.FirmwareStore
import com.vivekkaushik.wrtpulse.data.Inventory
import com.vivekkaushik.wrtpulse.data.LanStore
import com.vivekkaushik.wrtpulse.data.WanStore
import com.vivekkaushik.wrtpulse.data.LiveLogs
import com.vivekkaushik.wrtpulse.data.LiveTicker
import com.vivekkaushik.wrtpulse.data.PackageStore
import com.vivekkaushik.wrtpulse.data.ResetStore
import com.vivekkaushik.wrtpulse.data.RouterOps
import com.vivekkaushik.wrtpulse.data.RouterStatus
import com.vivekkaushik.wrtpulse.data.LedStore
import com.vivekkaushik.wrtpulse.data.ServiceStore
import com.vivekkaushik.wrtpulse.data.SshKeyStore
import com.vivekkaushik.wrtpulse.data.Telemetry
import com.vivekkaushik.wrtpulse.data.TerminalSessions
import com.vivekkaushik.wrtpulse.data.WifiStore
import com.vivekkaushik.wrtpulse.db.RouterEntity
import com.vivekkaushik.wrtpulse.net.SshKeys
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.WrtRuntime
import com.vivekkaushik.wrtpulse.ui.MainTab
import com.vivekkaushik.wrtpulse.ui.WrtBottomNav
import com.vivekkaushik.wrtpulse.ui.WrtBottomNavHeight
import com.vivekkaushik.wrtpulse.ui.screens.BackupScreen
import com.vivekkaushik.wrtpulse.ui.screens.ClientsScreen
import com.vivekkaushik.wrtpulse.ui.screens.CountryScreen
import com.vivekkaushik.wrtpulse.ui.screens.DashboardScreen
import com.vivekkaushik.wrtpulse.ui.screens.DiffSheetContent
import com.vivekkaushik.wrtpulse.ui.screens.FactoryResetScreen
import com.vivekkaushik.wrtpulse.ui.screens.FirmwareScreen
import com.vivekkaushik.wrtpulse.ui.screens.HostKeyScreen
import com.vivekkaushik.wrtpulse.ui.screens.AboutScreen
import com.vivekkaushik.wrtpulse.ui.screens.LogsScreen
import com.vivekkaushik.wrtpulse.ui.screens.OnboardingConnectScreen
import com.vivekkaushik.wrtpulse.ui.screens.OnboardingFingerprintScreen
import com.vivekkaushik.wrtpulse.ui.screens.OnboardingFlow
import com.vivekkaushik.wrtpulse.ui.screens.OnboardingSshKeyScreen
import com.vivekkaushik.wrtpulse.ui.screens.PackagesScreen
import com.vivekkaushik.wrtpulse.ui.screens.RouterListScreen
import com.vivekkaushik.wrtpulse.ui.screens.LedsScreen
import com.vivekkaushik.wrtpulse.ui.screens.ServicesScreen
import com.vivekkaushik.wrtpulse.ui.screens.SheetHost
import com.vivekkaushik.wrtpulse.ui.screens.SshKeysScreen
import com.vivekkaushik.wrtpulse.ui.screens.SwitcherSheetContent
import com.vivekkaushik.wrtpulse.ui.screens.SystemScreen
import com.vivekkaushik.wrtpulse.ui.screens.TermLine
import com.vivekkaushik.wrtpulse.ui.screens.TerminalRoomyHeight
import com.vivekkaushik.wrtpulse.ui.screens.TerminalScreen
import com.vivekkaushik.wrtpulse.ui.screens.WifiSection
import com.vivekkaushik.wrtpulse.ui.screens.initialTerminalLines
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import com.vivekkaushik.wrtpulse.ui.theme.WrtPulseTheme
import kotlinx.coroutines.launch
import java.io.File
import android.graphics.Color as AndroidColor

private enum class Dest { Boot, Onboarding1, Onboarding2, Onboarding3, RouterList, Main, HostKey }

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent {
            WrtPulseTheme {
                WrtPulseApp()
            }
        }
    }
}

@Composable
private fun WrtPulseApp() {
    val ticker = remember { LiveTicker() }
    LaunchedEffect(ticker) { ticker.run(1000L) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val flow = remember {
        WrtRuntime.init(context.applicationContext)
        OnboardingFlow(scope, WrtRuntime.client, WrtRuntime.hostKeys)
    }
    LaunchedEffect(Unit) { flow.gateway = WrtRuntime.defaultGateway(context) }

    var dest by remember { mutableStateOf(Dest.Boot) }
    // The wireless add/edit steps take the whole screen, tab bar included.
    var networkFullScreen by remember { mutableStateOf(false) }
    var currentRouter by remember { mutableStateOf("home.gw") }

    // Saved routers drive the start destination: returning users land on their list.
    val savedRouters by remember { WrtRuntime.db.routers().all() }.collectAsState(initial = null)
    LaunchedEffect(savedRouters) {
        if (dest == Dest.Boot && savedRouters != null) {
            dest = if (savedRouters!!.isEmpty()) Dest.Onboarding1 else Dest.RouterList
        }
    }
    // Whether onboarding has a router list behind it to go back to.
    val hasSavedRouters = !savedRouters.isNullOrEmpty()
    // Set when the list or the add page is opened from a live dashboard (the switcher sheet),
    // so back returns there instead of stranding the user on the list.
    var returnToMain by remember { mutableStateOf(false) }
    val canReturnToMain = returnToMain && WrtRuntime.session?.isConnected == true

    // The Keystore blob is only opened after the user passes the screen-lock gate, once per launch.
    var unlocked by remember { mutableStateOf(false) }
    var connectingIdentity by remember { mutableStateOf<String?>(null) }
    // The primary the join in progress targets, so the persisted row can name it.
    var joiningPrimary by remember { mutableStateOf<String?>(null) }
    // Set by the add-a-node guide: once the app connects to the next router, its Network tab
    // opens the join for this primary by itself.
    var pendingJoinPrimary by remember { mutableStateOf<String?>(null) }
    val prefs = remember { context.getSharedPreferences("wrtpulse", android.content.Context.MODE_PRIVATE) }
    var biometricEnabled by remember { mutableStateOf(prefs.getBoolean("biometric_gate", true)) }

    // Live feeds exist only while the main scaffold is on screen and a session is live.
    val session = if (dest == Dest.Main) WrtRuntime.session else null
    val telemetry = remember(session) { session?.let { Telemetry(it) } }
    val inventory = remember(session) { session?.let { Inventory(it) } }
    val wifiStore = remember(session) { session?.let { WifiStore(it) } }
    val lanStore = remember(session) { session?.let { LanStore(it) } }
    val wanStore = remember(session) { session?.let { WanStore(it) } }
    val termSessions = remember(session) { session?.let { TerminalSessions(it, scope) } }
    val routerOps = remember(session, telemetry) { session?.let { RouterOps(it, telemetry) } }
    val guestStore = remember(session) { session?.let { GuestStore(it) } }
    val iotStore = remember(session) { session?.let { GuestStore(it, com.vivekkaushik.wrtpulse.data.NetworkKind.IOT) } }
    val liveLogs = remember(session) { session?.let { LiveLogs(it) } }
    // Read on entry rather than on a tick — the installed list only changes when
    // somebody changes it, and reading it sweeps the whole package database.
    val packageStore = remember(session) { session?.let { PackageStore(it) } }
    // Same bargain for the init scripts: listing them walks /etc/init.d, so it waits
    // until the Services screen is actually opened.
    val serviceStore = remember(session) { session?.let { ServiceStore(it) } }
    // LEDs: a sysfs walk plus the device tree, read when the LED screen opens.
    val ledStore = remember(session) { session?.let { LedStore(it) } }
    val firmwareStore = remember(session) { session?.let { FirmwareStore(it) } }
    // Read when the section opens — it is a full `uci show firewall` plus the lease table.
    val firewallStore = remember(session) { session?.let { FirewallStore(it) } }
    // Static routes: read when the section opens, then kept live for the kernel table.
    val routeStore = remember(session) { session?.let { RouteStore(it) } }
    // Backups live in app-private storage. The store lists them on creation so the System
    // row can say when the last one was taken without the screen being opened.
    val backupStore = remember(session) { session?.let { BackupStore(it, File(context.filesDir, "backups")) } }
    // Reads only until the hold; the screen loads it, since walking the config to say what
    // a reset erases is not worth doing until someone is looking at the red zone.
    val resetStore = remember(session) {
        session?.let { ResetStore(it, File(context.filesDir, "backups")) }
    }
    // The mesh page reads the router like the wireless screen does, plus a ping per node. Read
    // when the page opens; its profile is sealed into the saved row after every read.
    val meshStore = remember(session) { session?.let { com.vivekkaushik.wrtpulse.data.MeshStore(it) } }
    // The mesh store needs to know its nodes wherever a Wi-Fi apply happens, not only on its page.
    LaunchedEffect(meshStore, savedRouters) {
        val me = savedRouters?.firstOrNull { it.identity == WrtRuntime.session?.target?.identity }
        meshStore?.nodeEntities = savedRouters.orEmpty().filter { it.meshPrimary == me?.identity }
    }
    LaunchedEffect(backupStore) {
        backupStore?.refreshLocal()
        // "Snapshot before every Apply" (design screen 38). The preference outlives the
        // store, which is rebuilt on every router switch; the hook is what the three staging
        // stores call before their batch, and a failed snapshot stops the apply.
        backupStore?.autoBackup = prefs.getBoolean("auto_backup", false)
        val hook: (suspend () -> Unit)? = backupStore?.let { b -> { b.autoSnapshot() } }
        wifiStore?.beforeApply = hook
        lanStore?.beforeApply = hook
        wanStore?.beforeApply = hook
        routeStore?.beforeApply = hook
    }
    // The app's own public key, so the keys screen can recognise the entry it is signed in
    // with and refuse to delete it. Derived from the sealed private key, keyed on the saved
    // row so a routine lastSeen touch does not re-open the Keystore.
    val savedEntity = savedRouters?.firstOrNull { it.identity == WrtRuntime.session?.target?.identity }
    val appPublicLine = remember(savedEntity?.id, savedEntity?.privateKey?.size) {
        runCatching { savedEntity?.privateKey?.let { WrtRuntime.vault.open(it) } }.getOrNull()
            ?.let { SshKeys.publicLineFrom(it) }
    }
    val sshKeyStore = remember(session, appPublicLine) {
        session?.let { SshKeyStore(it, appPublicLine) }
    }
    LaunchedEffect(meshStore, savedEntity?.id) {
        val identity = savedEntity?.identity ?: return@LaunchedEffect
        // Written only when something in it changed: the store re-reads every few seconds,
        // and a fresh sealed blob each time would re-emit the saved list just as often.
        var lastProfile: String? = null
        meshStore?.profileSink = { profile ->
            val key = profile.copy(capturedEpoch = 0).toJson()
            if (key != lastProfile) {
                // A node's row never carries a profile: it is not a primary, whatever its SSIDs
                // say. Read fresh, since the row in hand may predate the join that made it a node.
                val dao = WrtRuntime.db.routers()
                val row = runCatching { dao.byIdentity(identity) }.getOrNull()
                if (row != null && row.meshPrimary == null) {
                    val sealed = WrtRuntime.vault.seal(profile.toJson().toByteArray(Charsets.UTF_8))
                    if (runCatching { dao.setMeshProfile(row.id, sealed) }.isSuccess) lastProfile = key
                }
            }
        }
    }
    var logsStarted by remember(session) { mutableStateOf(false) }
    // Polling pauses while the app is in the background; the terminal shell stays attached.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(telemetry) { telemetry?.let { t -> lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { t.run() } } }
    LaunchedEffect(inventory) { inventory?.let { inv -> lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { inv.run() } } }
    // User renames flow into the merge as overrides.
    LaunchedEffect(inventory) {
        if (inventory != null) {
            WrtRuntime.db.clientNames().all().collect { names ->
                inventory.nameOverrides = names.associate { it.mac to it.name }
                inventory.remerge()
            }
        }
    }
    LaunchedEffect(wifiStore) { wifiStore?.load() }
    // The log stream opens lazily, the first time its screen appears, then stays alive
    // across tab switches. Terminal shells are owned by TerminalSessions.
    // Switching routers replaces the holder; its shells must not outlive it.
    DisposableEffect(termSessions) { onDispose { termSessions?.closeAll() } }
    LaunchedEffect(liveLogs, logsStarted) {
        if (logsStarted) liveLogs?.let { l -> lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { l.run() } }
    }
    var hostKeyRouter by remember { mutableStateOf("bpi-r3-lab") }

    // Main-scaffold state survives tab switches
    var tab by remember { mutableStateOf(MainTab.Dashboard) }
    var showSwitcher by remember { mutableStateOf(false) }
    var showDiff by remember { mutableStateOf(false) }
    var snippetsOpen by remember { mutableStateOf(false) }
    var logsOpen by remember { mutableStateOf(false) }
    var packagesOpen by remember { mutableStateOf(false) }
    var servicesOpen by remember { mutableStateOf(false) }
    var ledsOpen by remember { mutableStateOf(false) }
    var firmwareOpen by remember { mutableStateOf(false) }
    var resetOpen by remember { mutableStateOf(false) }
    var countryOpen by remember { mutableStateOf(false) }
    var sshKeysOpen by remember { mutableStateOf(false) }
    var backupOpen by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    // About opened from the router list, before any connection. Kept apart from aboutOpen so
    // closing one never touches what the System tab is showing.
    var aboutFromList by remember { mutableStateOf(false) }
    var pendingChanges by remember { mutableIntStateOf(3) }
    val termLines = remember { mutableStateListOf<TermLine>().apply { addAll(initialTerminalLines()) } }
    var termPending by remember { mutableStateOf("") }

    val activity = LocalActivity.current as FragmentActivity

    fun doConnect(entity: RouterEntity) {
        if (entity.host.contains('/')) {
            // A row written with a prefix on its address by an earlier build: mend it and knock on the address.
            val fixed = entity.copy(host = entity.host.substringBefore('/'))
            scope.launch { runCatching { WrtRuntime.db.routers().rehost(entity.id, fixed.host, entity.port) } }
            doConnect(fixed)
            return
        }
        val keyPem = runCatching { entity.privateKey?.let { WrtRuntime.vault.open(it) } }.getOrNull()
        val secret = runCatching { entity.credential?.let { WrtRuntime.vault.open(it) } }.getOrNull()
        flow.startSaved(entity)
        if (keyPem == null && secret == null) {
            // No stored credential (or the Keystore key changed): fall back to onboarding, prefilled.
            flow.password = ""
            flow.keyPem = null
            dest = Dest.Onboarding1
            return
        }
        flow.keyPem = keyPem
        flow.password = secret?.let { String(it, Charsets.UTF_8) } ?: ""
        connectingIdentity = entity.identity
        flow.connect(
            onFirstContact = { connectingIdentity = null; dest = Dest.Onboarding2 },
            onConnected = {
                connectingIdentity = null
                currentRouter = flow.routerName
                tab = if (pendingJoinPrimary != null) MainTab.Network else MainTab.Dashboard
                dest = Dest.Main
                scope.launch {
                    runCatching { WrtRuntime.db.routers().touch(entity.id, System.currentTimeMillis() / 1000) }
                }
            },
            onKeyChanged = { connectingIdentity = null; hostKeyRouter = entity.host; dest = Dest.HostKey },
        )
    }

    /**
     * A second, short-lived session to a saved router on its own credentials — how the mesh
     * page reaches a node, or a node reaches its primary, without touching the session the
     * app is driving. One dial, a short timeout, and the caller closes it. Null when the row
     * has nothing to sign in with.
     */
    fun sideSession(entity: RouterEntity): RouterSession? {
        val keyPem = runCatching { entity.privateKey?.let { WrtRuntime.vault.open(it) } }.getOrNull()
        val secret = runCatching { entity.credential?.let { WrtRuntime.vault.open(it) } }.getOrNull()
        val auth: (suspend () -> com.vivekkaushik.wrtpulse.net.SshAuth) = when {
            keyPem != null -> ({ com.vivekkaushik.wrtpulse.net.SshAuth.PrivateKey(keyPem.copyOf()) })
            secret != null -> ({ com.vivekkaushik.wrtpulse.net.SshAuth.Password(String(secret, Charsets.UTF_8).toCharArray()) })
            else -> return null
        }
        return RouterSession(entity.sshTarget, WrtRuntime.client, auth, maxAttempts = 1, connectTimeoutMs = 6_000)
    }

    fun connectSaved(entity: RouterEntity) {
        if (unlocked || !biometricEnabled || (entity.credential == null && entity.privateKey == null)) {
            doConnect(entity)
        } else {
            biometricUnlock(activity) { unlocked = true; doConnect(entity) }
        }
    }

    val backdrop = when {
        dest == Dest.HostKey -> Wrt.DangerBg
        dest == Dest.Main && tab == MainTab.Terminal -> Wrt.TermBarBg
        dest == Dest.Main -> Wrt.BgBar
        else -> Wrt.BgScreen
    }

    Box(Modifier.fillMaxSize().background(backdrop)) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            when (dest) {
                Dest.Onboarding1 -> OnboardingConnectScreen(
                    flow = flow,
                    onFirstContact = { dest = Dest.Onboarding2 },
                    onConnected = { dest = Dest.Onboarding3 },
                    onKeyChanged = { hostKeyRouter = flow.target.host; dest = Dest.HostKey },
                    // A first-run user has nowhere to go back to; one adding another router does —
                    // the dashboard they came from, or the list.
                    onBack = when {
                        canReturnToMain -> { { dest = Dest.Main } }
                        hasSavedRouters -> { { dest = Dest.RouterList } }
                        else -> null
                    },
                )
                Dest.Onboarding2 -> OnboardingFingerprintScreen(
                    flow = flow,
                    onConfirm = {
                        flow.confirmFirstContact(
                            onConnected = { dest = Dest.Onboarding3 },
                            onFailed = { dest = Dest.Onboarding1 },
                        )
                    },
                    onBack = { flow.rejectFirstContact(); dest = Dest.Onboarding1 },
                )
                Dest.Onboarding3 -> OnboardingSshKeyScreen(
                    flow = flow,
                    routerSummary = flow.board?.let { b ->
                        listOf(flow.routerName, b.summary).filter { it.isNotBlank() }.joinToString(" · ")
                    },
                    onFinish = {
                        currentRouter = flow.routerName
                        tab = if (pendingJoinPrimary != null) MainTab.Network else MainTab.Dashboard
                        dest = Dest.Main
                    },
                )
                Dest.Boot -> Box(Modifier.fillMaxSize().background(Wrt.BgScreen))
                Dest.RouterList -> if (aboutFromList) {
                    AboutScreen(onBack = { aboutFromList = false })
                } else RouterListScreen(
                    onAbout = { aboutFromList = true },
                    saved = savedRouters,
                    connectedIdentity = if (WrtRuntime.session?.isConnected == true) WrtRuntime.session?.target?.identity else null,
                    connectingIdentity = if (flow.busy) connectingIdentity else null,
                    error = flow.error,
                    onOpenRouter = { r ->
                        when (r.status) {
                            RouterStatus.Online -> { currentRouter = r.name; tab = MainTab.Dashboard; dest = Dest.Main }
                            RouterStatus.Reconnecting -> { hostKeyRouter = r.name; dest = Dest.HostKey }
                            RouterStatus.Offline, RouterStatus.Saved -> {}
                        }
                    },
                    onOpenSaved = { e ->
                        // A tap on the list is the user's own choice of router, not the guide's.
                        pendingJoinPrimary = null
                        if (WrtRuntime.session?.isConnected == true && WrtRuntime.session?.target?.identity == e.identity) {
                            currentRouter = e.name
                            tab = MainTab.Dashboard
                            dest = Dest.Main
                        } else {
                            connectSaved(e)
                        }
                    },
                    onAdd = { pendingJoinPrimary = null; flow.startNew(); dest = Dest.Onboarding1 },
                    onEdit = { e, name, host, port ->
                        scope.launch {
                            runCatching {
                                val dao = WrtRuntime.db.routers()
                                if (name != e.name) dao.rename(e.id, name)
                                // Local only: this moves where the app knocks. The router's
                                // own address is changed from Network · LAN, which is a
                                // different thing and says so in the dialog.
                                if (host != e.host || port != e.port) dao.rehost(e.id, host, port)
                            }
                        }
                        // The top bar shows the name of the router in hand, and it is not
                        // rebuilt from the saved row, so it has to be told.
                        if (currentRouter == e.name) currentRouter = name
                    },
                    onDelete = { e ->
                        // Local only: the row, its sealed credential and its pinned host key
                        // go; the router is never touched. RouterList spells that out before
                        // confirming.
                        scope.launch {
                            runCatching { WrtRuntime.db.routers().delete(e.id) }
                            runCatching { WrtRuntime.hostKeys.forget(e.sshTarget) }
                        }
                    },
                )
                Dest.HostKey -> {
                    val change = flow.keyChange
                    if (change != null) HostKeyScreen(
                        routerName = change.target.host,
                        savedKey = change.saved.sha256Fingerprint,
                        presentedKey = change.presented.sha256Fingerprint,
                        savedLabel = "SAVED KEY",
                        subtitle = "${change.target.host} presented a different key than the one saved earlier. " +
                            "Either the router was reset or reflashed — or something between you and it is intercepting the connection.",
                        onDisconnect = { flow.dropChangedKey(); dest = Dest.Onboarding1 },
                        onTrust = {
                            flow.trustChangedKey(
                                onConnected = { currentRouter = flow.routerName; tab = MainTab.Dashboard; dest = Dest.Main },
                                onFailed = { dest = Dest.Onboarding1 },
                            )
                        },
                    ) else HostKeyScreen(
                        routerName = hostKeyRouter,
                        onDisconnect = { dest = Dest.RouterList },
                        onTrust = { currentRouter = hostKeyRouter; tab = MainTab.Dashboard; dest = Dest.Main },
                    )
                }
                Dest.Main -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    // In landscape the Terminal's key rows plus the nav bar leave the output
                    // pane nothing, so the nav gives up its row first. Measured before the nav
                    // is dropped, so the two cannot flip each other back and forth.
                    val terminalNeedsTheRow = tab == MainTab.Terminal &&
                        maxHeight - WrtBottomNavHeight < TerminalRoomyHeight
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            when (tab) {
                                MainTab.Dashboard -> DashboardScreen(
                                    ticker = ticker,
                                    live = telemetry,
                                    inventory = inventory,
                                    ops = routerOps,
                                    guest = guestStore,
                                    iot = iotStore,
                                    board = flow.board,
                                    nodeOf = savedEntity?.meshPrimary?.let { id ->
                                        val primary = savedRouters?.firstOrNull { it.identity == id }
                                        com.vivekkaushik.wrtpulse.ui.screens.NodeOf(
                                            primaryName = primary?.name ?: "its primary",
                                            openPrimary = primary?.let { p -> { connectSaved(p) } },
                                        )
                                    },
                                    routerName = currentRouter,
                                    onRouterTap = { showSwitcher = true },
                                )
                                MainTab.Network -> WifiSection(
                                    ticker = ticker,
                                    store = wifiStore,
                                    lan = lanStore,
                                    wan = wanStore,
                                    firewall = firewallStore,
                                    routes = routeStore,
                                    live = telemetry,
                                    liveLatencyMs = telemetry?.latencyMs,
                                    routerName = currentRouter,
                                    pendingCount = wifiStore?.pendingCount ?: pendingChanges,
                                    onRouterTap = { showSwitcher = true },
                                    onReviewApply = { showDiff = true },
                                    onRevert = { wifiStore?.revert() ?: run { pendingChanges = 0 } },
                                    // A subnet change took the session with it. The saved
                                    // entry follows the router to its new address, and the
                                    // user goes back to the list to reconnect there — the
                                    // app cannot reach the old address any more. The pinned
                                    // host key belongs to the entry, not the address, so it
                                    // follows too and reconnecting is not a first contact.
                                    onLanMoved = { host ->
                                        val entity = savedRouters?.firstOrNull {
                                            it.identity == WrtRuntime.session?.target?.identity
                                        }
                                        scope.launch {
                                            entity?.let {
                                                runCatching { WrtRuntime.db.routers().rehost(it.id, host, it.port) }
                                            }
                                            runCatching { WrtRuntime.session?.disconnect() }
                                            WrtRuntime.session = null
                                            networkFullScreen = false
                                            returnToMain = false
                                            tab = MainTab.Dashboard
                                            dest = Dest.RouterList
                                        }
                                    },
                                    onFullScreen = { networkFullScreen = it },
                                    meshHooks = com.vivekkaushik.wrtpulse.ui.screens.MeshHooks(
                                        store = meshStore,
                                        backup = backupStore,
                                        saved = savedRouters.orEmpty(),
                                        current = savedEntity,
                                        newJoin = { primary ->
                                            joiningPrimary = primary.identity
                                            val live = WrtRuntime.session
                                            val row = savedEntity
                                            val profile = primary.meshProfile?.let { blob ->
                                                runCatching { String(WrtRuntime.vault.open(blob), Charsets.UTF_8) }.getOrNull()
                                            }?.let { com.vivekkaushik.wrtpulse.ops.MeshProfile.fromJson(it) }
                                            if (live == null || row == null || profile == null) null
                                            else com.vivekkaushik.wrtpulse.data.MeshJoin(
                                                session = live,
                                                client = WrtRuntime.client,
                                                initialProfile = profile,
                                                entity = row,
                                                backups = File(context.filesDir, "backups"),
                                                existingKeyPem = runCatching { row.privateKey?.let { WrtRuntime.vault.open(it) } }.getOrNull(),
                                                existingNodes = savedRouters.orEmpty().count { it.meshPrimary == primary.identity },
                                            )
                                        },
                                        // The row follows the node: its new address, its mesh
                                        // membership, and the key the join installed. A rollback
                                        // moves it back; the key stays, since authorized_keys is
                                        // not part of what the node restores.
                                        persist = { outcome ->
                                            val row = savedEntity ?: return@MeshHooks
                                            val dao = WrtRuntime.db.routers()
                                            runCatching {
                                                dao.rehost(row.id, outcome.host, row.port)
                                                outcome.installedKeyPem?.let { dao.setPrivateKey(row.id, WrtRuntime.vault.seal(it)) }
                                                if (outcome.joined) {
                                                    dao.setMesh(row.id, joiningPrimary, outcome.backhaul.uci, outcome.snapshot, outcome.meshMac)
                                                } else {
                                                    dao.setMesh(row.id, null, null, null, null)
                                                }
                                            }
                                        },
                                        // The node answers at its new address now. The old
                                        // session died with the reload; the row already points
                                        // at the new place, so the ordinary reconnect finds it.
                                        onJoined = { row ->
                                            scope.launch {
                                                runCatching { WrtRuntime.session?.disconnect() }
                                                WrtRuntime.session = null
                                                networkFullScreen = false
                                                tab = MainTab.Dashboard
                                                val fresh = runCatching { WrtRuntime.db.routers().byIdentity(row.identity) }.getOrNull() ?: row
                                                dest = Dest.RouterList
                                                connectSaved(fresh)
                                            }
                                        },
                                        // Like the LAN move: the router is rebooting to its old
                                        // config at its old address, so the entry follows and
                                        // the user reconnects from the list.
                                        onLeft = { lan ->
                                            val row = savedEntity
                                            scope.launch {
                                                row?.let {
                                                    val dao = WrtRuntime.db.routers()
                                                    runCatching { dao.setMesh(it.id, null, null, null, null) }
                                                    if (lan != null) runCatching { dao.rehost(it.id, lan.substringBefore('/'), it.port) }
                                                }
                                                if (lan != null) {
                                                    runCatching { WrtRuntime.session?.disconnect() }
                                                    WrtRuntime.session = null
                                                    networkFullScreen = false
                                                    returnToMain = false
                                                    tab = MainTab.Dashboard
                                                    dest = Dest.RouterList
                                                }
                                            }
                                        },
                                        onOpenRouter = { e ->
                                            if (WrtRuntime.session?.target?.identity != e.identity) {
                                                networkFullScreen = false
                                                connectSaved(e)
                                            }
                                        },
                                        onConnectToJoin = { node, primary ->
                                            pendingJoinPrimary = primary.identity
                                            networkFullScreen = false
                                            dest = Dest.RouterList
                                            connectSaved(node)
                                        },
                                        onAddRouterToJoin = { primary ->
                                            pendingJoinPrimary = primary.identity
                                            networkFullScreen = false
                                            returnToMain = true
                                            flow.startNew()
                                            dest = Dest.Onboarding1
                                        },
                                        // Only a router other than the primary itself can join it.
                                        pendingJoin = pendingJoinPrimary
                                            ?.takeIf { it != savedEntity?.identity }
                                            ?.let { id -> savedRouters?.firstOrNull { it.identity == id } },
                                        consumePendingJoin = { pendingJoinPrimary = null },
                                        newSetup = {
                                            WrtRuntime.session?.let { com.vivekkaushik.wrtpulse.data.NodeSetup(it, WrtRuntime.hostKeys) }
                                        },
                                        // A router with no row yet: the join gets a stand-in
                                        // entity carrying the identity the setup chose, and the
                                        // profile straight from this primary, live.
                                        newJoinVia = { setup ->
                                            val jump = setup.jump
                                            val ms = meshStore
                                            val profile = ms?.profile ?: ms?.takeIf { it.loaded }?.profileFrom()
                                            if (jump == null || profile == null) null
                                            else com.vivekkaushik.wrtpulse.data.MeshJoin(
                                                session = jump,
                                                client = WrtRuntime.client,
                                                initialProfile = profile,
                                                entity = RouterEntity(
                                                    name = "Node", host = jump.target.host, port = 22, username = "root",
                                                    model = setup.board?.model.orEmpty(), summary = setup.board?.summary.orEmpty(),
                                                    credential = null, lastSeenEpoch = System.currentTimeMillis() / 1000,
                                                    identity = setup.identity,
                                                ),
                                                backups = File(context.filesDir, "backups"),
                                                existingKeyPem = null,
                                                existingNodes = savedRouters.orEmpty().count { it.meshPrimary == savedEntity?.identity },
                                            )
                                        },
                                        persistVia = { setup, join, outcome ->
                                            val dao = WrtRuntime.db.routers()
                                            val primaryId = savedEntity?.identity
                                            runCatching {
                                                val existing = dao.byIdentity(setup.identity)
                                                if (!outcome.joined) {
                                                    // Rolled back: it is a factory router on a cable again, not a saved one.
                                                    existing?.let { dao.delete(it.id) }
                                                    WrtRuntime.hostKeys.forget(com.vivekkaushik.wrtpulse.net.SshTarget("x", 22, "root", setup.identity))
                                                    return@runCatching
                                                }
                                                val id = existing?.id ?: dao.upsert(
                                                    RouterEntity(
                                                        name = join.name.trim().ifEmpty { "Node" }, host = outcome.host, port = 22, username = "root",
                                                        model = setup.board?.model.orEmpty(), summary = setup.board?.summary.orEmpty(),
                                                        credential = null, lastSeenEpoch = System.currentTimeMillis() / 1000,
                                                        identity = setup.identity,
                                                    )
                                                )
                                                dao.rehost(id, outcome.host, 22)
                                                outcome.installedKeyPem?.let { dao.setPrivateKey(id, WrtRuntime.vault.seal(it)) }
                                                dao.setMesh(id, primaryId, outcome.backhaul.uci, outcome.snapshot, outcome.meshMac)
                                            }
                                        },
                                        // The snapshot Leave mesh would restore: the newest
                                        // archive of this router on the phone, which for a join
                                        // this app ran is the one taken just before it.
                                        markNode = { node, primary, backhaul, mac ->
                                            val tag = com.vivekkaushik.wrtpulse.data.ConfigArchive.tag(node.sshTarget)
                                            val snapshot = backupStore?.local?.firstOrNull { it.tag == tag }?.file?.name
                                            val dao = WrtRuntime.db.routers()
                                            runCatching {
                                                dao.setMesh(node.id, primary.identity, backhaul.uci, snapshot, mac)
                                                dao.setMeshProfile(node.id, null)
                                            }
                                        },
                                        adoptPeer = { node, mac ->
                                            runCatching {
                                                WrtRuntime.db.routers().setMesh(node.id, node.meshPrimary, node.meshBackhaul, node.meshSnapshot, mac)
                                            }
                                        },
                                        // A short-lived session to the primary, on its own saved
                                        // credentials, with one dial and a short timeout: from
                                        // behind the node's WAN cable it answers; otherwise this
                                        // gives up in seconds and the stored copy stands.
                                        openNode = { node -> sideSession(node) },
                                        refreshProfile = { primary ->
                                            val probe = sideSession(primary)
                                            if (probe == null) null else {
                                                var got: com.vivekkaushik.wrtpulse.ops.MeshProfile? = null
                                                val reader = com.vivekkaushik.wrtpulse.data.MeshStore(probe)
                                                reader.nodeEntities = savedRouters.orEmpty().filter { it.meshPrimary == primary.identity }
                                                reader.profileSink = { got = it }
                                                runCatching { reader.load() }
                                                runCatching { probe.disconnect() }
                                                got?.let { fresh ->
                                                    val sealed = WrtRuntime.vault.seal(fresh.toJson().toByteArray(Charsets.UTF_8))
                                                    runCatching { WrtRuntime.db.routers().setMeshProfile(primary.id, sealed) }
                                                }
                                                got
                                            }
                                        },
                                    ),
                                )
                                MainTab.Clients -> ClientsScreen(
                                    ticker = ticker,
                                    live = inventory,
                                    liveLatencyMs = telemetry?.latencyMs,
                                    routerName = currentRouter,
                                    onRouterTap = { showSwitcher = true },
                                    onRename = { mac, name ->
                                        scope.launch {
                                            runCatching {
                                                WrtRuntime.db.clientNames()
                                                    .upsert(com.vivekkaushik.wrtpulse.db.ClientName(mac, name))
                                            }
                                        }
                                    },
                                )
                                MainTab.Terminal -> {
                                    LaunchedEffect(termSessions) { termSessions?.openIfEmpty() }
                                    TerminalScreen(
                                        sessions = termSessions,
                                        routerName = currentRouter,
                                        lines = termLines,
                                        pendingCommand = termPending,
                                        snippetsOpen = snippetsOpen,
                                        onToggleSnippets = { snippetsOpen = it },
                                        onInsertSnippet = { cmd ->
                                            val shell = termSessions?.current
                                            if (shell != null) scope.launch { shell.send(cmd) } else termPending = cmd
                                            snippetsOpen = false
                                        },
                                    )
                                }
                                MainTab.System -> if (aboutOpen) {
                                    AboutScreen(onBack = { aboutOpen = false })
                                } else if (sshKeysOpen) {
                                    SshKeysScreen(
                                        store = sshKeyStore,
                                        latencyMs = telemetry?.latencyMs ?: ticker.latencyMs,
                                        onBack = { sshKeysOpen = false },
                                    )
                                } else if (backupOpen) {
                                    BackupScreen(
                                        store = backupStore,
                                        latencyMs = telemetry?.latencyMs ?: ticker.latencyMs,
                                        onBack = { backupOpen = false },
                                        onAutoBackup = { on ->
                                            backupStore?.autoBackup = on
                                            prefs.edit().putBoolean("auto_backup", on).apply()
                                        },
                                    )
                                } else if (countryOpen) {
                                    CountryScreen(
                                        store = wifiStore,
                                        onBack = { countryOpen = false },
                                    )
                                } else if (resetOpen) {
                                    FactoryResetScreen(
                                        store = resetStore,
                                        onBack = { resetOpen = false },
                                    )
                                } else if (firmwareOpen) {
                                    FirmwareScreen(
                                        store = firmwareStore,
                                        latencyMs = telemetry?.latencyMs ?: ticker.latencyMs,
                                        onBack = { firmwareOpen = false },
                                    )
                                } else if (servicesOpen) {
                                    ServicesScreen(
                                        store = serviceStore,
                                        latencyMs = telemetry?.latencyMs ?: ticker.latencyMs,
                                        onBack = { servicesOpen = false },
                                    )
                                } else if (ledsOpen) {
                                    LedsScreen(
                                        store = ledStore,
                                        latencyMs = telemetry?.latencyMs ?: ticker.latencyMs,
                                        onBack = { ledsOpen = false },
                                    )
                                } else if (packagesOpen) {
                                    PackagesScreen(
                                        store = packageStore,
                                        live = telemetry,
                                        latencyMs = telemetry?.latencyMs ?: ticker.latencyMs,
                                        onBack = { packagesOpen = false },
                                    )
                                } else if (logsOpen) {
                                    LaunchedEffect(Unit) { logsStarted = true }
                                    LogsScreen(
                                        ticker = ticker,
                                        live = liveLogs,
                                        liveLatencyMs = telemetry?.latencyMs,
                                        routerName = currentRouter,
                                        onRouterTap = { showSwitcher = true },
                                    )
                                } else {
                                    SystemScreen(
                                        ticker = ticker,
                                        live = telemetry,
                                        board = flow.board,
                                        country = wifiStore?.radios?.firstOrNull { it.country.isNotBlank() }?.country,
                                        sshKeyInstalled = if (telemetry != null) {
                                            savedRouters?.firstOrNull { it.identity == WrtRuntime.session?.target?.identity }
                                                ?.privateKey != null
                                        } else null,
                                        biometricEnabled = if (telemetry != null) biometricEnabled else null,
                                        onBiometricToggle = { on ->
                                            biometricEnabled = on
                                            prefs.edit().putBoolean("biometric_gate", on).apply()
                                        },
                                        packages = packageStore,
                                        services = serviceStore,
                                        leds = ledStore,
                                        backups = backupStore,
                                        routerName = currentRouter,
                                        onRouterTap = { showSwitcher = true },
                                        onOpenLogs = { logsOpen = true },
                                        onOpenPackages = { packagesOpen = true },
                                        onOpenServices = { servicesOpen = true },
                                        onOpenLeds = { ledsOpen = true },
                                        onOpenFirmware = { firmwareOpen = true },
                                        onOpenReset = { resetOpen = true },
                                        onOpenCountry = { countryOpen = true },
                                        onOpenSshKeys = { sshKeysOpen = true },
                                        onOpenBackup = { backupOpen = true },
                                        onOpenAbout = { aboutOpen = true },
                                    )
                                }
                            }
                        }
                        if (!(tab == MainTab.Network && networkFullScreen) && !terminalNeedsTheRow) {
                            WrtBottomNav(current = tab) { picked ->
                                if (picked != MainTab.System) {
                                    logsOpen = false; packagesOpen = false
                                    servicesOpen = false; ledsOpen = false; firmwareOpen = false; resetOpen = false
                                    countryOpen = false; sshKeysOpen = false; backupOpen = false
                                    aboutOpen = false
                                }
                                tab = picked
                            }
                        }
                    }
                }
            }
        }
        // Overlays cover the full screen, including behind the system bars.
        if (dest == Dest.Main) {
            SheetHost(visible = showSwitcher, onDismiss = { showSwitcher = false }) {
                SwitcherSheetContent(
                    ticker = ticker,
                    currentRouter = currentRouter,
                    saved = savedRouters,
                    connectedIdentity = if (WrtRuntime.session?.isConnected == true) WrtRuntime.session?.target?.identity else null,
                    liveLatencyMs = telemetry?.latencyMs,
                    onPickSaved = { e ->
                        showSwitcher = false
                        if (WrtRuntime.session?.target?.identity != e.identity || WrtRuntime.session?.isConnected != true) {
                            dest = Dest.RouterList
                            connectSaved(e)
                        }
                    },
                    onPick = { r ->
                        if (r.status == RouterStatus.Online) {
                            currentRouter = r.name
                            showSwitcher = false
                        } else if (r.status == RouterStatus.Reconnecting) {
                            showSwitcher = false
                            hostKeyRouter = r.name
                            dest = Dest.HostKey
                        }
                    },
                    onManage = { showSwitcher = false; returnToMain = true; dest = Dest.RouterList },
                    onAdd = {
                        showSwitcher = false
                        flow.startNew()
                        returnToMain = true
                        dest = Dest.Onboarding1
                    },
                )
            }
            SheetHost(visible = showDiff, onDismiss = { showDiff = false }) {
                val nodeCount = savedRouters.orEmpty().count { it.meshPrimary == savedEntity?.identity }
                DiffSheetContent(
                    store = wifiStore,
                    routerName = currentRouter,
                    clientCount = inventory?.clients?.size?.takeIf { it > 0 },
                    meshNote = nodeCount.takeIf { it > 0 }?.let {
                        "$it mesh node${if (it == 1) "" else "s"} carr${if (it == 1) "ies" else "y"} this router's Wi-Fi. " +
                            "${if (it == 1) "It gets" else "They get"} these changes first, then this router applies them, " +
                            "so a channel change keeps the mesh link."
                    },
                    onApply = {
                        if (wifiStore != null) {
                            // Read before applying: apply() clears the pending set.
                            val touched = if (wifiStore.networkOps().isEmpty()) "wireless"
                                else "wireless, network, firewall"
                            scope.launch {
                                // A primary's nodes get the change FIRST, shaped from what this
                                // router is about to become. A channel change on the mesh radio
                                // takes the backhaul with it the moment it applies here, and a
                                // node not told beforehand is stranded on the old channel; told
                                // first, it moves, waits a few seconds, and the link re-forms.
                                val mesh = meshStore
                                val nodes = savedRouters.orEmpty().filter { it.meshPrimary == savedEntity?.identity }
                                if (mesh != null && nodes.isNotEmpty()) {
                                    mesh.nodeEntities = nodes
                                    if (!mesh.loaded) runCatching { mesh.load() }
                                    val future = mesh.profileWith(wifiStore.effectiveRadios(), wifiStore.effectiveNetworks())
                                    mesh.pushNodes({ sideSession(it) }, profileToPush = future, all = true)
                                    mesh.syncNotice?.let { termLines.add(TermLine(AnnotatedString("mesh: $it"), false)) }
                                }
                                if (wifiStore.apply()) {
                                    showDiff = false
                                    termLines.add(TermLine(AnnotatedString("uci: committed $touched · wifi reloading…"), false))
                                }
                            }
                        } else {
                            showDiff = false
                            pendingChanges = 0
                            termLines.add(TermLine(AnnotatedString("uci: committed wireless · wifi reloading…"), false))
                        }
                    },
                    onRevertAll = {
                        showDiff = false
                        wifiStore?.revert() ?: run { pendingChanges = 0 }
                    },
                )
            }
        }
    }

    // Back leaves the app only from the router list, or from onboarding when there is no list
    // to return to. Everywhere else it steps one level up.
    BackHandler(
        enabled = dest != Dest.Boot &&
            !(dest == Dest.RouterList && !canReturnToMain && !aboutFromList) &&
            !(dest == Dest.Onboarding1 && !hasSavedRouters && !canReturnToMain),
    ) {
        when {
            dest == Dest.RouterList && aboutFromList -> aboutFromList = false
            dest == Dest.RouterList -> dest = Dest.Main
            dest == Dest.Onboarding1 -> dest = if (canReturnToMain) Dest.Main else Dest.RouterList
            dest == Dest.Onboarding2 -> dest = Dest.Onboarding1
            dest == Dest.Onboarding3 -> dest = Dest.Onboarding2
            dest == Dest.HostKey -> dest = if (flow.keyChange != null) {
                flow.dropChangedKey(); Dest.Onboarding1
            } else Dest.RouterList
            dest == Dest.Main && showDiff -> showDiff = false
            dest == Dest.Main && showSwitcher -> showSwitcher = false
            dest == Dest.Main && snippetsOpen -> snippetsOpen = false
            dest == Dest.Main && tab == MainTab.System && aboutOpen -> aboutOpen = false
            dest == Dest.Main && tab == MainTab.System && sshKeysOpen -> sshKeysOpen = false
            dest == Dest.Main && tab == MainTab.System && backupOpen -> backupOpen = false
            dest == Dest.Main && tab == MainTab.System && countryOpen -> countryOpen = false
            dest == Dest.Main && tab == MainTab.System && resetOpen -> resetOpen = false
            dest == Dest.Main && tab == MainTab.System && firmwareOpen -> firmwareOpen = false
            dest == Dest.Main && tab == MainTab.System && servicesOpen -> servicesOpen = false
            dest == Dest.Main && tab == MainTab.System && ledsOpen -> ledsOpen = false
            dest == Dest.Main && tab == MainTab.System && packagesOpen -> packagesOpen = false
            dest == Dest.Main && tab == MainTab.System && logsOpen -> logsOpen = false
            dest == Dest.Main && tab != MainTab.Dashboard -> tab = MainTab.Dashboard
            dest == Dest.Main -> { returnToMain = false; dest = Dest.RouterList }
        }
    }
}

/**
 * Gates the first credential unseal of the launch behind the screen lock. If the device has
 * no lock configured there is nothing to gate with, so the caller proceeds directly.
 */
private fun biometricUnlock(activity: FragmentActivity, onSuccess: () -> Unit) {
    val authenticators =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (BiometricManager.from(activity).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
        onSuccess()
        return
    }
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock WrtPulse")
            .setSubtitle("Your screen lock protects saved router credentials")
            .setAllowedAuthenticators(authenticators)
            .build()
    )
}
