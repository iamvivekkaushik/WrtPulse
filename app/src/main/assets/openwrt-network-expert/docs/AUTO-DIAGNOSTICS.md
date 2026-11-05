# التشخيص التلقائي لأعطال OpenWrt

## تشغيل الفحوصات
```sh
/root/openwrt-network-expert/wan-diagnostics.sh
/root/openwrt-network-expert/dns-diagnostics.sh
/root/openwrt-network-expert/dhcp-diagnostics.sh
/root/openwrt-network-expert/log-analyzer.sh
```

أو شغّل التقرير العام:
```sh
/root/openwrt-network-expert/diagnostics.sh
```

## تفسير النتائج
- WAN بلا عنوان أو default route: راجع نوع اتصال WAN، DHCP/PPPoE، VLAN، الكابل وسجلات netifd.
- WAN بعنوان صالح لكن البوابة لا تستجيب: افحص الربط المحلي لدى المزود/المودم؛ قد يكون ICMP محجوباً.
- IP خارجي يعمل وDNS الافتراضي يفشل: افحص dnsmasq وupstream resolver.
- عميل LAN لا يحصل على lease: افحص DHCP pool وbridge/VLAN ووجود خادم DHCP آخر.
- الراوتر يتصل والعميل لا يتصل: افحص DHCP/DNS وNAT/firewall وVLAN، ولا توقف firewall عشوائياً.

## خصوصية
قد تظهر في المخرجات عناوين IP وMAC وأسماء أجهزة وSSID واسم مزود الخدمة. راجعها واحجبها قبل مشاركة التقرير.
