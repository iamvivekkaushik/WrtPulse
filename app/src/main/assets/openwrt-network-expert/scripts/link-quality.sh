#!/bin/sh
# Read-only per-station link statistics.
set -u
DEV="${1:-}"
if [ -z "$DEV" ]; then
  echo "Usage: $0 <wireless-interface>"
  exit 2
fi
echo "=== iwinfo station list ==="
if command -v iwinfo >/dev/null 2>&1; then
  iwinfo "$DEV" assoclist 2>&1
fi
echo
echo "=== nl80211 station dump (if supported) ==="
if command -v iw >/dev/null 2>&1; then
  iw dev "$DEV" station dump 2>&1
else
  echo "iw not installed."
fi
echo
echo "Review MAC addresses before sharing this output."
