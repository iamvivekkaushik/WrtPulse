#!/bin/sh
# Read-only wireless and mesh link checks
set -u
echo "=== Mesh Link Quality ==="
iw dev 2>/dev/null
for dev in $(iw dev 2>/dev/null | awk '$1=="Interface"{print $2}'); do
  echo
  echo "--- $dev: link ---"
  iw dev "$dev" link 2>&1
  echo "--- $dev: stations ---"
  iw dev "$dev" station dump 2>&1
  echo "--- $dev: mesh paths ---"
  iw dev "$dev" mpath dump 2>&1
done
if command -v batctl >/dev/null 2>&1; then
  echo
  echo "--- BATMAN originators ---"
  batctl meshif bat0 originators 2>&1
  echo "--- BATMAN neighbors ---"
  batctl meshif bat0 neighbors 2>&1
  echo "--- BATMAN gateways ---"
  batctl meshif bat0 gateways 2>&1
fi
echo
echo "Link check completed. No configuration was changed."
