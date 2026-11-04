#!/bin/sh
# Read-only Wi-Fi inventory and scan helper for OpenWrt.
set -u
echo "=== Wireless overview ==="
if command -v iwinfo >/dev/null 2>&1; then
  iwinfo
  for dev in $(iwinfo 2>/dev/null | awk '/ ESSID:|          ESSID:/ {print $1}' | sort -u); do
    [ -n "$dev" ] || continue
    echo "\n=== $dev info ==="
    iwinfo "$dev" info 2>&1
    echo "=== $dev scan ==="
    iwinfo "$dev" scan 2>&1
  done
else
  echo "iwinfo not installed. Try: opkg update && opkg install iwinfo (installation not performed)."
fi
echo "\n=== iw devices ==="
command -v iw >/dev/null 2>&1 && iw dev || echo "iw not available"
