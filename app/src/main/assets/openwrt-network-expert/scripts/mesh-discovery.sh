#!/bin/sh
# Read-only OpenWrt mesh discovery report
set -u
echo "=== OpenWrt Mesh Discovery ==="
date 2>/dev/null
echo
echo "--- System ---"
ubus call system board 2>/dev/null || cat /etc/openwrt_release 2>/dev/null
echo
echo "--- Wireless interfaces ---"
iw dev 2>/dev/null || true
echo
echo "--- Mesh/WDS capabilities (selected) ---"
iw list 2>/dev/null | grep -Ei -A5 -B2 'mesh point|4addr|WDS|Supported interface modes' | head -100
echo
echo "--- Network links ---"
ip -d link show 2>/dev/null || ifconfig -a 2>/dev/null
echo
echo "--- Installed mesh packages ---"
if command -v opkg >/dev/null 2>&1; then
  opkg list-installed 2>/dev/null | grep -Ei 'batman|mesh|wpad|hostapd' || true
elif command -v apk >/dev/null 2>&1; then
  apk list --installed 2>/dev/null | grep -Ei 'batman|mesh|wpad|hostapd' || true
fi
echo
echo "--- Kernel modules ---"
lsmod 2>/dev/null | grep -Ei 'batman|cfg80211|mac80211' || true
echo
echo "--- BATMAN-ADV ---"
if command -v batctl >/dev/null 2>&1; then
  batctl -v 2>&1
  batctl meshif bat0 interface 2>&1
  batctl meshif bat0 originators 2>&1
  batctl meshif bat0 neighbors 2>&1
else
  echo "batctl not installed"
fi
echo
echo "--- Relevant log entries ---"
logread 2>/dev/null | grep -Ei 'batman|mesh|wds|hostapd|nl80211' | tail -80 || true
echo
echo "Report completed. No configuration was changed."
