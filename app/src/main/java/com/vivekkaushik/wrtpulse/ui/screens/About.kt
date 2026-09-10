package com.vivekkaushik.wrtpulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.vivekkaushik.wrtpulse.BuildConfig
import com.vivekkaushik.wrtpulse.ui.FlexSpacer
import com.vivekkaushik.wrtpulse.ui.MonoTag
import com.vivekkaushik.wrtpulse.ui.SectionLabel
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt

/** One third-party library the app ships with. Versions come from BuildConfig, see [AppLibraries]. */
data class AppLibrary(
    val name: String,
    val version: String,
    val license: String,
    val url: String,
    /** What the app uses it for, in one line. */
    val role: String,
)

/**
 * The libraries compiled into the release, with the versions Gradle actually resolved. Test-only
 * dependencies (JUnit, Espresso, org.json) are not shipped and are not listed.
 */
object AppLibraries {
    const val APACHE = "Apache-2.0"

    val all: List<AppLibrary> = listOf(
        AppLibrary(
            "Kotlin", BuildConfig.LIB_KOTLIN, APACHE,
            "https://kotlinlang.org", "Language and standard library",
        ),
        AppLibrary(
            "kotlinx.coroutines", BuildConfig.LIB_COROUTINES, APACHE,
            "https://github.com/Kotlin/kotlinx.coroutines", "SSH calls and live refresh off the main thread",
        ),
        AppLibrary(
            "Jetpack Compose", "BOM ${BuildConfig.LIB_COMPOSE_BOM}", APACHE,
            "https://developer.android.com/jetpack/compose", "Every screen, including Material 3",
        ),
        AppLibrary(
            "AndroidX Activity Compose", BuildConfig.LIB_ACTIVITY_COMPOSE, APACHE,
            "https://developer.android.com/jetpack/androidx/releases/activity", "Activity host and back handling",
        ),
        AppLibrary(
            "AndroidX Core KTX", BuildConfig.LIB_CORE_KTX, APACHE,
            "https://developer.android.com/jetpack/androidx/releases/core", "Kotlin extensions for the framework",
        ),
        AppLibrary(
            "AndroidX Lifecycle", BuildConfig.LIB_LIFECYCLE, APACHE,
            "https://developer.android.com/jetpack/androidx/releases/lifecycle", "Lifecycle-aware refresh and state",
        ),
        AppLibrary(
            "Room", BuildConfig.LIB_ROOM, APACHE,
            "https://developer.android.com/jetpack/androidx/releases/room", "Saved routers and client names, on this phone",
        ),
        AppLibrary(
            "AndroidX Biometric", BuildConfig.LIB_BIOMETRIC, APACHE,
            "https://developer.android.com/jetpack/androidx/releases/biometric", "Screen-lock gate on saved credentials",
        ),
        AppLibrary(
            "JSch (mwiede fork)", BuildConfig.LIB_JSCH, "BSD-3-Clause",
            "https://github.com/mwiede/jsch", "The SSH client every router command runs over",
        ),
        AppLibrary(
            "Bouncy Castle", BuildConfig.LIB_BOUNCYCASTLE, "MIT",
            "https://www.bouncycastle.org/java.html", "curve25519 and ed25519 for JSch on Android",
        ),
    )
}

object AppLinks {
    const val SOURCE = "https://github.com/iamvivekkaushik/WrtPulse"
    const val WEBSITE = "https://iamvivekkaushik.github.io/WrtPulse/"
    const val PRIVACY = "https://iamvivekkaushik.github.io/WrtPulse/privacy.html"
}

/** What the app is, which build this is, and what it is built from. Works with no router connected. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        FormTopBar("About", onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AboutCard {
                Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("WrtPulse", style = sans(17f, 650))
                        MonoTag("v${BuildConfig.VERSION_NAME}", color = Wrt.Accent, border = Wrt.Accent.copy(alpha = 0.5f))
                        if (BuildConfig.DEBUG) MonoTag(BuildConfig.BUILD_TYPE, color = Wrt.Amber, border = Wrt.Amber.copy(alpha = 0.5f))
                    }
                    Text(
                        "OpenWrt from your phone, over SSH. Nothing runs on the router but the shell " +
                            "commands it shows you first.",
                        style = sans(11.5f, 400, Wrt.TextSecondary),
                    )
                }
                Divider()
                KeyValue("Version", "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
                KeyValue("Package", BuildConfig.APPLICATION_ID)
                KeyValue("Android", "9.0+ (API 28)", last = true)
            }

            SectionLabel("LINKS", tracking = 0.14)
            AboutCard {
                LinkRow("Source code", AppLinks.SOURCE) { uri.openUri(AppLinks.SOURCE) }
                LinkRow("Website", AppLinks.WEBSITE) { uri.openUri(AppLinks.WEBSITE) }
                LinkRow("Privacy policy", AppLinks.PRIVACY, last = true) { uri.openUri(AppLinks.PRIVACY) }
            }

            SectionLabel("OPEN-SOURCE LIBRARIES", tracking = 0.14)
            Text(
                "Versions are the ones this build was compiled against. Tap a library to open its " +
                    "project page.",
                style = sans(10.5f, 400, Wrt.TextDim),
            )
            AboutCard {
                AppLibraries.all.forEachIndexed { i, lib ->
                    LibraryRow(lib, last = i == AppLibraries.all.lastIndex) { uri.openUri(lib.url) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun AboutCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 2.dp)
    ) { content() }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Wrt.BorderHair))
}

@Composable
private fun KeyValue(key: String, value: String, last: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(key, style = sans(13f, 600))
        FlexSpacer()
        Text(value, style = mono(10.5f, 500, Wrt.TextTertiary))
    }
    if (!last) Divider()
}

@Composable
private fun LinkRow(title: String, url: String, last: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = sans(13f, 600))
            Text(
                url.removePrefix("https://"),
                style = mono(10f, 500, Wrt.TextDim),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(WrtIcons.ShareUp, null, Modifier.size(13.dp), tint = Wrt.TextDim)
    }
    if (!last) Divider()
}

@Composable
private fun LibraryRow(lib: AppLibrary, last: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(lib.name, style = sans(13f, 600))
            // The licence sits on the second line: next to the name it fights the version
            // column for width and wraps on the longer names.
            Row(
                Modifier.padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                MonoTag(lib.license)
                Text(lib.role, style = sans(10.5f, 400, Wrt.TextDim))
            }
        }
        Text(lib.version, style = mono(10.5f, 500, Wrt.TextTertiary))
        Icon(WrtIcons.ChevronRight, null, Modifier.size(13.dp), tint = Wrt.TextDim)
    }
    if (!last) Divider()
}
