package com.vivekkaushik.wrtpulse.ops

/** The two transparent-bridge roles supported by the Phicomm K2 profile. */
enum class PhicommK2WdsRole(val uciMode: String) {
    ApWds("ap"),
    ClientWds("sta"),
}

/**
 * Safe, explicit UCI plan for Phicomm K2 V22.4 / OpenWrt 23.05.5.
 *
 * The K2 is a legacy ramips/swconfig-era board, so the default LAN member is eth0.1. It is
 * configurable because a vendor image may expose the switch CPU VLAN under another name. The
 * plan deliberately uses a static management address and disables the local DHCP server: in a
 * transparent WDS bridge the upstream router must be the only DHCP authority.
 */
data class PhicommK2Profile(
    val role: PhicommK2WdsRole,
    val radio: String = "radio0",
    val ssid: String,
    val key: String,
    val managementIp: String,
    val netmask: String = "255.255.255.0",
    val gateway: String = "",
    val dns: List<String> = emptyList(),
    val lanPorts: List<String> = listOf("eth0.1"),
    val bridgeName: String = "br-lan",
    val lanInterface: String = "lan",
    val wdsInterface: String = "wrtpulse_wds",
    val disableLocalServices: Boolean = true,
) {
    /** Human-readable warnings shown before a caller applies the plan. */
    fun problems(): List<String> = buildList {
        if (ssid.isBlank()) add("WDS SSID is required.")
        if (key.length !in 8..63) add("WDS WPA2 key must be 8–63 characters.")
        if (managementIp.isBlank()) add("A unique static management IP is required.")
        if (lanPorts.isEmpty()) add("At least one LAN bridge port is required.")
        if (bridgeName.isBlank() || lanInterface.isBlank() || wdsInterface.isBlank()) {
            add("Bridge, LAN and WDS section names cannot be empty.")
        }
    }

    /** UCI operations for network, wireless and DHCP. */
    fun uciOps(): List<String> = buildList {
        add("set network.$bridgeName=device")
        add("set network.$bridgeName.name='${quote(bridgeName)}'")
        add("set network.$bridgeName.type='bridge'")
        add("delete network.$bridgeName.ports")
        lanPorts.forEach { add("add_list network.$bridgeName.ports='${quote(it)}'") }
        // Linux bridge STP is the loop-prevention layer; IGMP snooping is safe for bridged LANs.
        add("set network.$bridgeName.stp='1'")
        add("set network.$bridgeName.igmp_snooping='1'")

        add("set network.$lanInterface=interface")
        add("set network.$lanInterface.device='$bridgeName'")
        add("set network.$lanInterface.proto='static'")
        add("set network.$lanInterface.ipaddr='${quote(managementIp)}'")
        add("set network.$lanInterface.netmask='${quote(netmask)}'")
        if (gateway.isNotBlank()) add("set network.$lanInterface.gateway='${quote(gateway)}'")
        if (dns.isNotEmpty()) {
            add("delete network.$lanInterface.dns")
            dns.forEach { add("add_list network.$lanInterface.dns='${quote(it)}'") }
        }

        add("set wireless.$wdsInterface=wifi-iface")
        add("set wireless.$wdsInterface.device='${quote(radio)}'")
        add("set wireless.$wdsInterface.mode='${role.uciMode}'")
        add("set wireless.$wdsInterface.network='$lanInterface'")
        add("set wireless.$wdsInterface.wds='1'")
        add("set wireless.$wdsInterface.ssid='${quote(ssid)}'")
        add("set wireless.$wdsInterface.encryption='psk2'")
        add("set wireless.$wdsInterface.key='${quote(key)}'")

        add("set dhcp.$lanInterface=dhcp")
        add("set dhcp.$lanInterface.interface='$lanInterface'")
        add("set dhcp.$lanInterface.ignore='1'")
    }

    /** Exact command preview used by the app's review step. */
    fun applyCommand(): String = Commands.uciBatch(
        operations = uciOps(),
        commitPackages = listOf("network", "wireless", "dhcp"),
        reload = buildString {
            append("/etc/init.d/network reload; wifi reload")
            if (disableLocalServices) {
                append("; /etc/init.d/dnsmasq disable; /etc/init.d/dnsmasq stop")
                append("; /etc/init.d/odhcpd disable; /etc/init.d/odhcpd stop")
                append("; /etc/init.d/firewall disable; /etc/init.d/firewall stop")
            }
        },
    )

    fun notes(): List<String> = buildList {
        add("Both WDS peers must support four-address bridging and use matching security settings.")
        add("STP is enabled on $bridgeName to reduce the risk of a Layer-2 loop.")
        add("DHCP is disabled on $lanInterface; the upstream router remains the only DHCP server.")
        if (role == PhicommK2WdsRole.ClientWds) {
            add("Client-WDS is transparent bridging, not a routed Wi-Fi uplink; do not attach it to a WAN firewall zone.")
        }
        add("Verify the radio name with `iw dev` and the bridge member with `ip link` before applying.")
    }

    private fun quote(value: String): String = value.replace("'", "'\\''")
}
