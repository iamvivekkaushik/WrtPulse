#!/bin/sh
# Read-only DNS checks. Does not change resolver settings.
set -u
echo "=== Resolver configuration ==="
for f in /etc/resolv.conf /tmp/resolv.conf.d/resolv.conf.auto; do
  if [ -r "$f" ]; then
    echo "--- $f ---"
    sed -E 's/(nameserver[[:space:]]+).*/\1[REDACTED_OR_REVIEW]/' "$f"
  fi
done
echo "\n=== Default resolver lookup ==="
if command -v nslookup >/dev/null 2>&1; then
  nslookup openwrt.org 2>&1
  echo "\n=== Public resolver lookup (1.1.1.1) ==="
  nslookup openwrt.org 1.1.1.1 2>&1
else
  echo "nslookup is unavailable."
fi
echo "\n=== DNS service and logs ==="
logread 2>/dev/null | grep -Ei 'dnsmasq|odhcpd|dns|resolv' | tail -n 120
