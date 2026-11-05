#!/bin/sh
# Read-only channel occupancy snapshot from iwinfo scan output.
set -u
if ! command -v iwinfo >/dev/null 2>&1; then
  echo "iwinfo is required; install it using the package manager for your OpenWrt release."
  exit 1
fi
DEV="${1:-}"
if [ -z "$DEV" ]; then
  echo "Usage: $0 <wireless-interface>"
  echo "Available wireless interfaces:"
  iwinfo 2>/dev/null
  exit 2
fi
echo "=== Radio information: $DEV ==="
iwinfo "$DEV" info 2>&1
echo
echo "=== Visible networks (scan): $DEV ==="
iwinfo "$DEV" scan 2>&1
echo
echo "NOTE: This is a Wi-Fi scan snapshot, not a full RF spectrum analysis."
echo "Repeat at different times. Do not choose a channel from network count alone."
