#!/bin/sh
# Read-only regulatory and radio capability snapshot.
set -u
echo "=== OpenWrt release ==="
cat /etc/openwrt_release 2>/dev/null
echo
echo "=== Wireless UCI (review for sensitive identifiers before sharing) ==="
uci show wireless 2>/dev/null | sed -E "s/(key|password|passphrase)='[^']*'/\\1='[REDACTED]'/Ig"
echo
echo "=== iw list capabilities/regulatory data ==="
if command -v iw >/dev/null 2>&1; then
  iw list 2>&1
else
  echo "iw not installed."
fi
echo
echo "=== iwinfo ==="
command -v iwinfo >/dev/null 2>&1 && iwinfo 2>&1
