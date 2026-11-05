#!/bin/sh
# Read-only diagnostic summary. Review output before sharing publicly.
set -u
section() { echo "\n===== $1 ====="; }
section "System"
ubus call system board 2>&1
cat /etc/openwrt_release 2>/dev/null
section "Interfaces and routes"
ip -br addr 2>&1
ip route 2>&1
section "Network status"
ubus call network.interface dump 2>&1
section "Wireless"
command -v iwinfo >/dev/null 2>&1 && iwinfo 2>&1
command -v iw >/dev/null 2>&1 && iw dev 2>&1
section "Recent relevant logs"
logread 2>/dev/null | grep -Ei 'hostapd|wlan|wifi|dnsmasq|udhcpc|netifd|firewall|deauth|disassoc' | tail -n 100
section "Link counters"
ip -s link 2>&1
echo "\nNOTE: Review and redact public IPs, MAC addresses, SSIDs and identifiers before sharing."
