package com.vivekkaushik.wrtpulse

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.vivekkaushik.wrtpulse.db.RouterEntity
import com.vivekkaushik.wrtpulse.data.Demo
import com.vivekkaushik.wrtpulse.data.Inventory
import com.vivekkaushik.wrtpulse.data.LiveTicker
import com.vivekkaushik.wrtpulse.data.LiveLogs
import com.vivekkaushik.wrtpulse.data.Telemetry
import com.vivekkaushik.wrtpulse.data.TermEngine
import com.vivekkaushik.wrtpulse.data.WifiStore
import kotlinx.coroutines.launch
import com.vivekkaushik.wrtpulse.net.WrtRuntime
import com.vivekkaushik.wrtpulse.ui.screens.OnboardingFlow
import com.vivekkaushik.wrtpulse.data.RouterStatus
import com.vivekkaushik.wrtpulse.ui.MainTab
import com.vivekkaushik.wrtpulse.ui.WrtBottomNav
import com.vivekkaushik.wrtpulse.ui.screens.ClientsScreen
import com.vivekkaushik.wrtpulse.ui.screens.DashboardScreen
import com.vivekkaushik.wrtpulse.ui.screens.DiffSheetContent
import com.vivekkaushik.wrtpulse.ui.screens.HostKeyScreen
import com.vivekkaushik.wrtpulse.ui.screens.LogsScreen
import com.vivekkaushik.wrtpulse.ui.screens.OnboardingConnectScreen
import com.vivekkaushik.wrtpulse.ui.screens.OnboardingFingerprintScreen
import com.vivekkaushik.wrtpulse.ui.screens.OnboardingSshKeyScreen
import com.vivekkaushik.wrtpulse.ui.screens.RouterListScreen
import com.vivekkaushik.wrtpulse.ui.screens.SheetHost
import com.vivekkaushik.wrtpulse.ui.screens.SwitcherSheetContent
import com.vivekkaushik.wrtpulse.ui.screens.SystemScreen
import com.vivekkaushik.wrtpulse.ui.screens.TermLine
import com.vivekkaushik.wrtpulse.ui.screens.TerminalScreen
import com.vivekkaushik.wrtpulse.ui.screens.WifiEditorScreen
import com.vivekkaushik.wrtpulse.ui.screens.initialTerminalLines
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import com.vivekkaushik.wrtpulse.ui.theme.WrtPulseTheme

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
    var currentRouter by remember { mutableStateOf("home.gw") }

    // Saved routers drive the start destination: returning users land on their list.
    val savedRouters by remember { WrtRuntime.db.routers().all() }.collectAsState(initial = null)
    LaunchedEffect(savedRouters) {
        if (dest == Dest.Boot && savedRouters != null) {
            dest = if (savedRouters!!.isEmpty()) Dest.Onboarding1 else Dest.RouterList
        }
    }

    // The Keystore blob is only opened after the user passes the screen-lock gate, once per launch.
    var unlocked by remember { mutableStateOf(false) }
    var connectingHost by remember { mutableStateOf<String?>(null) }

    // Live feeds exist only while the main scaffold is on screen and a session is live.
    val session = if (dest == Dest.Main) WrtRuntime.session else null
    val telemetry = remember(session) { session?.let { Telemetry(it) } }
    val inventory = remember(session) { session?.let { Inventory(it) } }
    val wifiStore = remember(session) { session?.let { WifiStore(it) } }
    val termEngine = remember(session) { session?.let { TermEngine(it) } }
    val liveLogs = remember(session) { session?.let { LiveLogs(it) } }
    var termStarted by remember(session) { mutableStateOf(false) }
    var logsStarted by remember(session) { mutableStateOf(false) }
    // Polling pauses while the app is in the background; the terminal shell stays attached.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(telemetry) { telemetry?.let { t -> lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { t.run() } } }
    LaunchedEffect(inventory) { inventory?.let { inv -> lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { inv.run() } } }
    LaunchedEffect(wifiStore) { wifiStore?.load() }
    // The shell and the log stream open lazily, the first time their screens appear,
    // then stay alive across tab switches.
    LaunchedEffect(termEngine, termStarted) { if (termStarted) termEngine?.run() }
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
    var pendingChanges by remember { mutableIntStateOf(3) }
    val termLines = remember { mutableStateListOf<TermLine>().apply { addAll(initialTerminalLines()) } }
    var termPending by remember { mutableStateOf("") }

    val activity = LocalContext.current as FragmentActivity

    fun doConnect(entity: RouterEntity) {
        val secret = runCatching { entity.credential?.let { WrtRuntime.vault.open(it) } }.getOrNull()
        flow.host = entity.host
        flow.port = entity.port.toString()
        flow.username = entity.username
        if (secret == null) {
            // No stored credential (or the Keystore key changed): fall back to onboarding, prefilled.
            flow.password = ""
            dest = Dest.Onboarding1
            return
        }
        flow.password = String(secret, Charsets.UTF_8)
        connectingHost = entity.host
        flow.connect(
            onFirstContact = { connectingHost = null; dest = Dest.Onboarding2 },
            onConnected = {
                connectingHost = null
                currentRouter = flow.routerName
                tab = MainTab.Dashboard
                dest = Dest.Main
                scope.launch {
                    runCatching { WrtRuntime.db.routers().touch(entity.id, System.currentTimeMillis() / 1000) }
                }
            },
            onKeyChanged = { connectingHost = null; hostKeyRouter = entity.host; dest = Dest.HostKey },
        )
    }

    fun connectSaved(entity: RouterEntity) {
        if (unlocked || entity.credential == null) {
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
                    routerSummary = flow.board?.let { b ->
                        listOf(flow.routerName, b.summary).filter { it.isNotBlank() }.joinToString(" · ")
                    },
                    onFinish = { currentRouter = flow.routerName; tab = MainTab.Dashboard; dest = Dest.Main },
                )
                Dest.Boot -> Box(Modifier.fillMaxSize().background(Wrt.BgScreen))
                Dest.RouterList -> RouterListScreen(
                    saved = savedRouters,
                    connectedHost = if (WrtRuntime.session?.isConnected == true) WrtRuntime.session?.target?.host else null,
                    connectingHost = if (flow.busy) connectingHost else null,
                    error = flow.error,
                    onOpenRouter = { r ->
                        when (r.status) {
                            RouterStatus.Online -> { currentRouter = r.name; tab = MainTab.Dashboard; dest = Dest.Main }
                            RouterStatus.Reconnecting -> { hostKeyRouter = r.name; dest = Dest.HostKey }
                            RouterStatus.Offline, RouterStatus.Saved -> {}
                        }
                    },
                    onOpenSaved = { e ->
                        if (WrtRuntime.session?.isConnected == true && WrtRuntime.session?.target?.host == e.host) {
                            currentRouter = e.name
                            tab = MainTab.Dashboard
                            dest = Dest.Main
                        } else {
                            connectSaved(e)
                        }
                    },
                    onAdd = { dest = Dest.Onboarding1 },
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
                Dest.Main -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        when (tab) {
                            MainTab.Dashboard -> DashboardScreen(
                                ticker = ticker,
                                live = telemetry,
                                inventory = inventory,
                                routerName = currentRouter,
                                onRouterTap = { showSwitcher = true },
                                onOpenTerminal = { tab = MainTab.Terminal },
                            )
                            MainTab.Network -> WifiEditorScreen(
                                ticker = ticker,
                                store = wifiStore,
                                routerName = currentRouter,
                                pendingCount = wifiStore?.pendingCount ?: pendingChanges,
                                onRouterTap = { showSwitcher = true },
                                onReviewApply = { showDiff = true },
                                onRevert = { wifiStore?.revert() ?: run { pendingChanges = 0 } },
                            )
                            MainTab.Clients -> ClientsScreen(
                                ticker = ticker,
                                live = inventory,
                                routerName = currentRouter,
                                onRouterTap = { showSwitcher = true },
                            )
                            MainTab.Terminal -> {
                                LaunchedEffect(Unit) { termStarted = true }
                                TerminalScreen(
                                    engine = termEngine,
                                    routerName = currentRouter,
                                    lines = termLines,
                                    pendingCommand = termPending,
                                    snippetsOpen = snippetsOpen,
                                    onToggleSnippets = { snippetsOpen = it },
                                    onInsertSnippet = { cmd ->
                                        if (termEngine != null) scope.launch { termEngine.send(cmd) }
                                        else termPending = cmd
                                        snippetsOpen = false
                                    },
                                )
                            }
                            MainTab.System -> if (logsOpen) {
                                LaunchedEffect(Unit) { logsStarted = true }
                                LogsScreen(ticker = ticker, live = liveLogs, routerName = currentRouter, onRouterTap = { showSwitcher = true })
                            } else {
                                SystemScreen(
                                    ticker = ticker,
                                    routerName = currentRouter,
                                    onRouterTap = { showSwitcher = true },
                                    onOpenLogs = { logsOpen = true },
                                )
                            }
                        }
                    }
                    WrtBottomNav(current = tab) { picked ->
                        if (picked != MainTab.System) logsOpen = false
                        tab = picked
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
                    connectedHost = if (WrtRuntime.session?.isConnected == true) WrtRuntime.session?.target?.host else null,
                    liveLatencyMs = telemetry?.latencyMs,
                    onPickSaved = { e ->
                        showSwitcher = false
                        if (WrtRuntime.session?.target?.host != e.host || WrtRuntime.session?.isConnected != true) {
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
                    onManage = { showSwitcher = false; dest = Dest.RouterList },
                )
            }
            SheetHost(visible = showDiff, onDismiss = { showDiff = false }) {
                DiffSheetContent(
                    store = wifiStore,
                    routerName = currentRouter,
                    clientCount = inventory?.clients?.size?.takeIf { it > 0 },
                    onApply = {
                        if (wifiStore != null) {
                            scope.launch {
                                if (wifiStore.apply()) {
                                    showDiff = false
                                    termLines.add(TermLine(AnnotatedString("uci: committed wireless · wifi reloading…"), false))
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

    BackHandler(enabled = dest != Dest.Onboarding1 && dest != Dest.RouterList && dest != Dest.Boot) {
        when {
            dest == Dest.Onboarding2 -> dest = Dest.Onboarding1
            dest == Dest.Onboarding3 -> dest = Dest.Onboarding2
            dest == Dest.HostKey -> dest = if (flow.keyChange != null) {
                flow.dropChangedKey(); Dest.Onboarding1
            } else Dest.RouterList
            dest == Dest.Main && showDiff -> showDiff = false
            dest == Dest.Main && showSwitcher -> showSwitcher = false
            dest == Dest.Main && snippetsOpen -> snippetsOpen = false
            dest == Dest.Main && tab == MainTab.System && logsOpen -> logsOpen = false
            dest == Dest.Main && tab != MainTab.Dashboard -> tab = MainTab.Dashboard
            dest == Dest.Main -> dest = Dest.RouterList
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
