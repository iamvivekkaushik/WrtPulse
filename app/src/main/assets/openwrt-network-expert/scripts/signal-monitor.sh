#!/bin/sh
# One-shot signal/client snapshot. Optional argument: wireless interface.
set -u
DEV="${1:-}"
if [ -z "$DEV" ]; then
  if command -v iwinfo >/dev/null 2>&1; then
    DEV="$(iwinfo 2>/dev/null | awk '/ESSID:/ {print $1; exit}')"
  fi
fi
if [ -z "$DEV" ]; then
  echo "Could not detect a wireless interface. Usage: $0 wlan0"
  exit 1
fi
echo "=== Interface: $DEV ==="
if command -v iwinfo >/dev/null 2>&1; then
  iwinfo "$DEV" info
  iwinfo "$DEV" assoclist
elif command -v iw >/dev/null 2>&1; then
  iw dev "$DEV" station dump
else
  echo "Neither iwinfo nor iw is available."
  exit 1
fi
