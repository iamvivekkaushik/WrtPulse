#!/bin/sh
# Summarize recent likely network-related log messages (read-only).
set -u
N="${1:-250}"
case "$N" in *[!0-9]*|'') echo "Usage: $0 [number-of-lines]"; exit 2;; esac
echo "=== Recent system log (filtered) ==="
logread 2>/dev/null | grep -Ei 'netifd|udhcpc|pppd|dnsmasq|odhcpd|hostapd|wlan|wifi|firewall|wan|dhcp|dns|timeout|deauth|disassoc|carrier' | tail -n "$N"
echo "\n=== Recent kernel log ==="
dmesg 2>/dev/null | grep -Ei 'wifi|wlan|firmware|link|eth|ath|mtk|brcm|timeout|reset|error|fail' | tail -n 100
