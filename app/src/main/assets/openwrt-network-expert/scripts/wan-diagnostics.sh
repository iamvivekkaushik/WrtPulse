#!/bin/sh
# Read-only WAN and default-route diagnostic snapshot.
set -u
echo "=== Default route ==="
ip route 2>&1
echo "\n=== Network interfaces (ubus) ==="
ubus call network.interface dump 2>&1
echo "\n=== Addresses and links ==="
ip -br addr 2>&1
ip -br link 2>&1
GW="$(ip route 2>/dev/null | awk '/default/ {print $3; exit}')"
if [ -n "$GW" ]; then
  echo "\n=== Gateway reachability: $GW ==="
  ping -c 4 -W 2 "$GW" 2>&1
else
  echo "\nNo default gateway detected."
fi
echo "\n=== Public IP reachability ==="
ping -c 4 -W 2 1.1.1.1 2>&1
echo "\n=== WAN-related logs ==="
logread 2>/dev/null | grep -Ei 'netifd|udhcpc|pppd|wan|link is|carrier|timeout|no lease' | tail -n 120
