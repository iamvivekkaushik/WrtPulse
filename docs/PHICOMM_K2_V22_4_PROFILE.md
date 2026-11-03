# Phicomm K2 V22.4 profile

This profile targets the Phicomm K2 V22.4 running OpenWrt 23.05.5 (`ramips/mt7620`). It is a UCI command plan, not a firmware image.

The implementation is `PhicommK2Profile` in `app/src/main/java/com/vivekkaushik/wrtpulse/ops/PhicommK2Profile.kt`.

## Modes

`ApWds` configures a WDS access point. `ClientWds` configures a four-address station. Both modes attach the wireless interface to `lan`, so the link is a transparent Layer-2 bridge rather than a routed WAN connection.

The default legacy K2 LAN member is `eth0.1`. The profile keeps it configurable because vendor images can expose the switch CPU VLAN under a different name. Verify it with `ip link` and `uci show network` before applying.

## Safety defaults

- Creates/updates `br-lan` as a bridge and enables `stp '1'`.
- Enables `igmp_snooping '1'` for bridged multicast traffic.
- Gives the K2 a unique static management address.
- Disables the local `lan` DHCP server so the upstream router remains the only DHCP authority.
- Optionally stops and disables `dnsmasq`, `odhcpd`, and `firewall` after the UCI reload.
- Uses `wds '1'` on both AP and station profiles.
- Uses WPA2-PSK and refuses keys outside 8–63 characters.

## Before applying

The app should show the generated UCI batch and require the user to verify:

1. The upstream peer supports four-address/WDS bridging.
2. The radio name is correct (`iw dev`), and AP/STA concurrency is supported if the same radio is used for both.
3. The management IP is unused and belongs to the upstream subnet.
4. The Ethernet bridge member is correct for this K2 image.
5. A recovery path exists before disabling DHCP and firewall services.

WDS is not 802.11r roaming and ordinary station mode must not be presented as a transparent bridge.
