package com.vivekkaushik.wrtpulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vivekkaushik.wrtpulse.data.WifiStore
import com.vivekkaushik.wrtpulse.ops.RegDomain
import com.vivekkaushik.wrtpulse.ops.Regulatory
import com.vivekkaushik.wrtpulse.ui.FlexSpacer
import com.vivekkaushik.wrtpulse.ui.GhostButton
import com.vivekkaushik.wrtpulse.ui.MonoTag
import com.vivekkaushik.wrtpulse.ui.PrimaryButton
import com.vivekkaushik.wrtpulse.ui.SectionLabel
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import kotlinx.coroutines.launch

/**
 * The Wi-Fi regulatory domain. OpenWrt stores it per radio, but it describes where the
 * router is standing, so picking one here stages it on every radio at once and the diff
 * shows exactly that.
 */
@Composable
fun CountryScreen(store: WifiStore?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(store) { if (store != null && !store.loaded) store.load() }

    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        FormTopBar("Country", onBack)
        if (store == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Connect to a router to set the domain.", style = sans(12f, 500, Wrt.TextDim))
            }
            return@Column
        }

        val radios = store.radios.toList()
        val saved = Regulatory.current(radios)
        // What each radio would carry once applied — staged value wins over the saved one.
        val effective = radios.map { store.value(it.section, "country", it.country) }
            .filter { it.isNotBlank() }.toSet().singleOrNull()
        val selected = picked ?: effective

        val rows = remember(filter) { Regulatory.search(filter) }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 10.dp),
        ) {
        // The header scrolls with the list rather than sitting above it: with the keyboard
        // up and a pending change open it filled the whole screen, leaving Apply and
        // Discard off the bottom and the country list squeezed to nothing.
        item {
          Column {
            CurrentCard(store, saved, selected)
            Spacer(Modifier.height(10.dp))
            Box {
                FormTextField(filter, { filter = it })
                if (filter.isEmpty()) {
                    Text(
                        "search countries",
                        style = mono(12.5f, 500, Wrt.TextFaint),
                        modifier = Modifier.padding(start = 12.dp, top = 20.dp),
                    )
                }
            }
            if (selected != null && selected != saved) {
                Spacer(Modifier.height(10.dp))
                PendingCard(
                    store = store,
                    selected = selected,
                    onApply = {
                        scope.launch {
                            toast = if (store.apply()) {
                                "Applied · radios now report " +
                                    (Regulatory.current(store.radios) ?: "nothing")
                            } else {
                                "Failed: ${store.error ?: "the router refused the change"}"
                            }
                            picked = null
                        }
                    },
                    onRevert = {
                        radios.forEach { store.stage(it.section, "country", it.country, it.country) }
                        picked = null
                        toast = null
                    },
                )
            }
            toast?.let {
                Text(
                    it,
                    style = mono(10.5f, 500, if (it.startsWith("Failed")) Wrt.Red else Wrt.Accent),
                    modifier = Modifier.padding(top = 8.dp).clickable { toast = null },
                )
            }
            Spacer(Modifier.height(4.dp))
          }
        }
            items(rows, key = { it.code }) { domain ->
                CountryRow(domain, domain.code == selected, domain.code == saved) {
                    picked = domain.code
                    // Stage on every radio: the domain is a property of the place, not of
                    // one radio, and radios disagreeing about it is legal nonsense.
                    radios.forEach { radio ->
                        store.stage(radio.section, "country", radio.country, domain.code)
                    }
                }
            }
        }
    }
}

@Composable
private fun CurrentCard(store: WifiStore, saved: String?, selected: String?) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("ON THE ROUTER NOW", size = 9.5f, tracking = 0.14)
            FlexSpacer()
            MonoTag(
                saved ?: "unset",
                color = if (saved == null) Wrt.Amber else Wrt.Accent,
                border = (if (saved == null) Wrt.Amber else Wrt.Accent).copy(alpha = 0.5f),
            )
        }
        Text(
            saved?.let { Regulatory.nameOf(it) }
                ?: "No domain is set. The driver falls back to the world domain, which is the " +
                "most restrictive one there is.",
            style = sans(12.5f, 600),
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            // Saved values, not staged ones: this card says what the router carries now, and
            // mixing the pending value into it makes the heading a lie.
            store.radios.joinToString(" · ") { radio ->
                "${radio.section} ${radio.country.ifBlank { "—" }}"
            }.ifEmpty { "no radios" },
            style = mono(10f, 500, Wrt.TextDim),
            modifier = Modifier.padding(top = 4.dp),
        )
        if (Regulatory.disagree(store.radios)) {
            Text(
                "The radios do not agree on a domain. One router sits in one country, so this " +
                    "is worth fixing whichever value is right.",
                style = sans(10.5f, 500, Wrt.Amber),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (Regulatory.partiallySet(store.radios)) {
            Text(
                "Some radios have no domain set. Those fall back to the world domain, so they " +
                    "run more restricted than the ones beside them — picking a country here " +
                    "sets all of them.",
                style = sans(10.5f, 500, Wrt.Amber),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (selected != null && selected == saved) {
            Text(
                "Already set to this.",
                style = sans(10.5f, 500, Wrt.TextDim),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** The staged change, the exact uci it becomes, and what it might cost. */
@Composable
private fun PendingCard(
    store: WifiStore,
    selected: String,
    onApply: () -> Unit,
    onRevert: () -> Unit,
) {
    val countryOps = store.ops().filter { it.contains(".country") }
    // apply() commits everything staged, so unrelated wireless edits would ride along.
    val otherOps = store.opCount - countryOps.size
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.Accent.copy(alpha = 0.4f), RoundedCornerShape(13.dp))
            .background(Wrt.Accent.copy(alpha = 0.05f), RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        SectionLabel("WOULD APPLY", size = 9.5f, tracking = 0.14)
        Text(
            "${Regulatory.nameOf(selected)} · $selected",
            style = sans(13f, 650, Wrt.Accent),
            modifier = Modifier.padding(top = 8.dp),
        )
        Box(
            Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .border(1.dp, Wrt.BorderHair, RoundedCornerShape(9.dp))
                .background(Wrt.BgCode, RoundedCornerShape(9.dp))
                .padding(horizontal = 11.dp, vertical = 9.dp)
        ) {
            Text(
                (countryOps + listOf("uci commit wireless", "wifi reload")).joinToString("\n"),
                style = mono(9.5f, 500, Wrt.TextSecondary),
            )
        }
        Regulatory.warnings(selected, store.radios).forEach {
            Text(it, style = sans(10.5f, 500, Wrt.AmberText), modifier = Modifier.padding(top = 9.dp))
        }
        if (otherOps > 0) {
            Text(
                "There are also $otherOps other pending wireless change" +
                    (if (otherOps == 1) "" else "s") +
                    ". Applying here commits those too — review them under Network first if " +
                    "that is not what you want.",
                style = sans(10.5f, 500, Wrt.Amber),
                modifier = Modifier.padding(top = 9.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(if (store.applying) "Applying…" else "Apply") {
            if (!store.applying) onApply()
        }
        Spacer(Modifier.height(6.dp))
        // GhostButton's default border is BorderCard, which measures 1.23:1 against this
        // tinted panel — invisible, so the only way out of a staged change read as a stray
        // line of text under a solid Apply. TextTertiary puts it at 6.6:1.
        GhostButton(
            "Discard this change",
            border = Wrt.TextTertiary,
            onClick = onRevert,
        )
    }
}

@Composable
private fun CountryRow(domain: RegDomain, selected: Boolean, saved: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (selected) Wrt.Accent.copy(alpha = 0.6f) else Wrt.BorderRow,
                RoundedCornerShape(10.dp),
            )
            .background(Wrt.BgCardDim, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            domain.name,
            style = sans(12.5f, if (selected) 650 else 500, if (selected) Wrt.Accent else Wrt.TextPrimary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (saved) MonoTag("CURRENT", Wrt.TextTertiary)
        Text(domain.code, style = mono(10.5f, 600, Wrt.TextDim))
        if (selected) Icon(WrtIcons.Check, null, Modifier.size(13.dp), tint = Wrt.Accent)
    }
}
