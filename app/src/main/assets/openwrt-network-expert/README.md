# OpenWrt Network Expert Skills

مجموعة مهارات وأدوات مساعدة لتحليل Wi‑Fi وتشخيص الشبكات على OpenWrt.

## المحتويات
- `SKILL.md`: تعليمات عامة لوكيل الذكاء الاصطناعي.
- `skills/wifi-optimization/SKILL.md`: تحليل وتحسين Wi‑Fi.
- `skills/wifi-troubleshooting/SKILL.md`: تشخيص مشاكل الاتصال اللاسلكي.
- `skills/network-diagnostics/SKILL.md`: تشخيص LAN/WAN وDNS وDHCP.
- `skills/signal-analysis/SKILL.md`: تحليل الإشارة والعملاء.
- `skills/channel-optimization/SKILL.md`: اختيار القنوات وعرض النطاق.
- `skills/network-performance/SKILL.md`: latency وpacket loss والأداء.
- `skills/automatic-troubleshooting/SKILL.md`: تشخيص منهجي لأعطال WAN وDNS وDHCP والسجلات.
- `scripts/`: أدوات فحص للقراءة فقط، تشمل فحوصات WAN وDNS وDHCP وتحليل السجلات.
- `docs/`: التثبيت والاستخدام والسلامة.

## المتطلبات
- OpenWrt مع BusyBox/ash.
- يفضّل `iwinfo` و`ubus` و`ip` و`ping`.
- بعض الاختبارات اختيارية وتعتمد على الحزم المثبتة.

## التثبيت
انسخ المجلد إلى جهازك أو إلى بيئة Agent Skills التي تستخدمها. لتشغيل الأدوات على الراوتر:

```sh
scp -r scripts root@192.168.1.1:/root/openwrt-network-expert/
ssh root@192.168.1.1
chmod +x /root/openwrt-network-expert/*.sh
```

ثم شغّل مثلاً:

```sh
/root/openwrt-network-expert/wifi-scan.sh
/root/openwrt-network-expert/signal-monitor.sh
/root/openwrt-network-expert/network-test.sh
/root/openwrt-network-expert/diagnostics.sh
```

عدّل عنوان الراوتر في أوامر SSH حسب شبكتك.

## مبدأ السلامة
كل السكربتات المرفقة للفحص وجمع المعلومات فقط. لا تغيّر القنوات أو القدرة أو إعدادات الشبكة تلقائياً. خذ نسخة احتياطية قبل أي تغيير يدوي:

```sh
sysupgrade -b /tmp/openwrt-backup.tar.gz
```

لا تشارك ملفات النسخ الاحتياطي أو مخرجات التشخيص علناً قبل مراجعتها؛ قد تحتوي على أسماء الشبكات وعناوين IP وMAC ومعلومات حساسة.


## الإضافات المتقدمة
تمت إضافة:
- `interference-analysis`: تحليل التداخل والازدحام المرئي.
- `advanced-channel-planning`: تخطيط القنوات وفق البلد والعتاد وDFS.
- `radio-tuning`: ضبط الراديو بصورة محافظة وبقياسات قبل/بعد.
- `client-link-quality`: تحليل جودة اتصال كل عميل والتجوال.
- `channel-report.sh`: تقرير مسح القنوات.
- `link-quality.sh`: إحصائيات جودة ارتباط العملاء.
- `radio-capabilities.sh`: قدرات الراديو والنطاق التنظيمي.

تشغيل الأدوات الجديدة:
```sh
/root/openwrt-network-expert/channel-report.sh wlan0
/root/openwrt-network-expert/link-quality.sh wlan0
/root/openwrt-network-expert/radio-capabilities.sh
```
قد تختلف أسماء الواجهات عن `wlan0`؛ استخدم `iwinfo` لاكتشاف الاسم الصحيح.

## التشخيص التلقائي المتقدم

```sh
/root/openwrt-network-expert/wan-diagnostics.sh
/root/openwrt-network-expert/dns-diagnostics.sh
/root/openwrt-network-expert/dhcp-diagnostics.sh
/root/openwrt-network-expert/log-analyzer.sh
```

كل هذه الأدوات تجمع معلومات فقط ولا تغيّر إعدادات الشبكة.


## Mesh Networking (added)
تتضمن الحزمة الآن وحدات 802.11s وBATMAN-ADV وWDS وMulti-AP roaming، مع أدوات اكتشاف وتشخيص للقراءة فقط:
- `scripts/mesh-discovery.sh`
- `scripts/mesh-link-check.sh`
- `skills/mesh-overview/`
- `skills/mesh-80211s/`
- `skills/mesh-batman-adv/`
- `skills/mesh-wds/`
- `skills/mesh-ap-roaming/`
- `docs/MESH-DEPLOYMENT-CHECKLIST.md`
- `docs/MESH-TROUBLESHOOTING.md`

الأدوات لا تغيّر إعدادات الراوتر. راجع ملفات المهارات قبل أي تعديل، وخذ نسخة احتياطية ووفّر وسيلة رجوع.
