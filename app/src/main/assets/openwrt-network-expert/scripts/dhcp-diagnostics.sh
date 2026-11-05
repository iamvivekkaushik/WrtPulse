#!/bin/sh
# Read-only DHCP/bridge snapshot. Redact client identifiers before sharing.
set -u
echo "=== DHCP leases (if supported) ==="
ubus call dhcp ipv4leases 2>&1
echo "\n=== Interface addresses ==="
ip -br addr 2>&1
echo "\n=== Bridge links ==="
if command -v bridge >/dev/null 2>&1; then
  bridge link 2>&1
  bridge vlan show 2>&1
else
  echo "bridge utility unavailable."
fi
echo "\n=== DHCP and dnsmasq logs ==="
logread 2>/dev/null | grep -Ei 'dnsmasq|odhcpd|DHCPDISCOVER|DHCPOFFER|DHCPREQUEST|DHCPACK|DHCPNAK|lease' | tail -n 160
