---
name: network-diagnostics
description: Diagnose OpenWrt LAN, WAN, routing, DHCP, firewall and DNS problems.
---
# Network Diagnostics

## التسلسل
1. `ip -br addr` و`ip route`
2. `ubus call network.interface dump`
3. `logread | grep -Ei 'netifd|udhcpc|dnsmasq|odhcp|firewall'`
4. `ping -c 4 <gateway>`
5. `ping -c 4 1.1.1.1`
6. `nslookup openwrt.org` أو `nslookup openwrt.org 1.1.1.1`
7. افحص `nft list ruleset` أو `iptables -S` بحسب إصدار النظام.

## تفسير سريع
- فشل الوصول إلى البوابة: غالباً Wi-Fi/LAN أو DHCP أو VLAN.
- البوابة تعمل وIP خارجي يفشل: افحص WAN والتوجيه والجدار الناري ومزود الخدمة.
- IP خارجي يعمل واسم النطاق يفشل: افحص DNS.
- كل الاختبارات تعمل لكن التطبيقات بطيئة: افحص latency تحت الحمل، MTU، ازدحام WAN، وSQM.

لا تطبع كلمات مرور PPPoE أو مفاتيح VPN أو بيانات اعتماد.
