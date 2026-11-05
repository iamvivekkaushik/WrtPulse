#!/bin/sh
# Lightweight connectivity checks. No configuration changes.
set -u
COUNT="${COUNT:-4}"
GW="$(ip route 2>/dev/null | awk '/default/ {print $3; exit}')"
echo "=== Default route ==="
ip route 2>&1
if [ -n "$GW" ]; then
  echo "\n=== Gateway: $GW ==="
  ping -c "$COUNT" -W 2 "$GW" 2>&1
else
  echo "No default gateway found."
fi
echo "\n=== External IP: 1.1.1.1 ==="
ping -c "$COUNT" -W 2 1.1.1.1 2>&1
echo "\n=== DNS lookup ==="
if command -v nslookup >/dev/null 2>&1; then
  nslookup openwrt.org 2>&1
else
  echo "nslookup unavailable."
fi
