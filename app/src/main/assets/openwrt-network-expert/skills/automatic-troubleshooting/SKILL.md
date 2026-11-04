---
name: automatic-troubleshooting
description: Run a safe, evidence-based OpenWrt troubleshooting workflow for logs, DNS, DHCP, WAN and routing.
---
# Automatic OpenWrt Troubleshooting

## الهدف
تشخيص أعطال OpenWrt بشكل منهجي مع جمع الأدلة أولاً، ثم تحديد الطبقة المحتمل وجود المشكلة فيها: Wi-Fi/LAN أو DHCP أو DNS أو WAN أو التوجيه أو الجدار الناري.

## قواعد السلامة
- ابدأ بالفحص للقراءة فقط. لا تنفذ `uci set`, `uci commit`, `ifdown`, `ifup`, `wifi reload`, أو إعادة تشغيل الخدمات.
- لا تعرض كلمات مرور PPPoE أو مفاتيح VPN أو PSK أو محتوى ملفات الأسرار.
- لا تفترض أسماء واجهات مثل `wan` أو `br-lan`؛ اكتشفها من `ubus call network.interface dump` و`ip route`.
- لا تعتبر فشل ping وحده دليلاً قاطعاً على انقطاع الإنترنت؛ قد تمنع الشبكة ICMP.
- لا تشغّل اختبارات تحميل ثقيلة أو مسح منافذ.
- اطلب موافقة المستخدم قبل أي إصلاح أو تغيير.

## مراحل التشخيص

### 1. صحة النظام والواجهات
اجمع:
- `ubus call system board`
- `cat /etc/openwrt_release`
- `ip -br link`
- `ip -br addr`
- `ip route`
- `ubus call network.interface dump`

حدد الواجهة الافتراضية والبوابة وحالة الواجهات. انتبه إلى `NO-CARRIER` وواجهات DOWN وعناوين IPv4/IPv6 الغائبة.

### 2. WAN
- حدد اسم واجهة WAN الفعلية من بيانات ubus وواجهة default route.
- افحص حالة الواجهة وعنوان IP والبوابة ومدة الاتصال.
- اختبر البوابة إن كانت معروفة، ثم عنوان IP خارجي.
- افحص سجل netifd وudhcpc/pppd حسب نوع الاتصال.
- إذا لم توجد default route، افحص DHCP/PPPoE/VLAN قبل اقتراح تعديل.

### 3. DNS
اختبر بالترتيب:
- `nslookup openwrt.org`
- إن توفر: `nslookup openwrt.org 1.1.1.1`
- افحص `cat /tmp/resolv.conf.d/resolv.conf.auto` مع إخفاء أي معلومات خاصة.
- افحص سجلات dnsmasq: `logread | grep -i dnsmasq`.

التفسير:
- IP خارجي يعمل وDNS الافتراضي يفشل: احتمال DNS أو dnsmasq أو إعدادات upstream.
- الاستعلام المباشر لخادم عام يعمل والافتراضي يفشل: الخلل غالباً في DNS المحلي/المزود.
- كلاهما يفشل: لا تستنتج أن DNS هو السبب قبل فحص WAN.

### 4. DHCP وLAN
- افحص `logread | grep -Ei 'dnsmasq|odhcpd|DHCP|DHCPDISCOVER|DHCPACK|lease'`.
- افحص `ubus call dhcp ipv4leases` إذا كانت الطريقة مدعومة.
- افحص `ip -br addr` و`bridge link` و`bridge vlan show` إذا توفرت.
- تحقق من وجود عنوان LAN وخادم DHCP والـ leases.
- لا تعرض أسماء العملاء أو MACs كاملة في التقرير العام.

### 5. Firewall وNAT
- اكتشف backend: `nft list ruleset` أو `iptables -S` بحسب المتاح.
- افحص السجلات المتعلقة بـ firewall.
- لا تعدّل القواعد ولا تعطّل الجدار الناري كاختبار.
- لا تنصح بإيقاف firewall كحل.

### 6. السجلات
استخدم:
- `logread | tail -n 250`
- `dmesg | tail -n 150`
- مرشحات: `netifd`, `udhcpc`, `pppd`, `dnsmasq`, `odhcpd`, `hostapd`, `firewall`, `wan`, `link`.

اربط الحدث بالتوقيت والواجهة. لا تفسر رسالة منفردة خارج سياقها.

## قالب التقرير
1. **ملخص الحالة:** سليم / مشكلة مرجحة / غير حاسم.
2. **الطبقة المتأثرة:** Wi-Fi / LAN / DHCP / DNS / WAN / Routing / Firewall.
3. **الأدلة:** أوامر ومقتطفات مختصرة بعد تنقيح الخصوصية.
4. **السبب المحتمل:** مع درجة ثقة (منخفضة/متوسطة/عالية).
5. **الاختبار التالي:** أقل اختبار آمن يميز بين الأسباب.
6. **الإصلاح المقترح:** لا ينفذ إلا بعد موافقة.
7. **خطة التراجع:** استعادة نسخة الإعدادات أو إعادة القيمة السابقة.

## تصنيف سريع
- عميل لا يحصل على IP: DHCP أو VLAN أو bridge أو إعداد العميل.
- عميل يحصل على IP ولا يصل للبوابة: Wi-Fi/LAN أو bridge/VLAN.
- البوابة تعمل ولا يوجد اتصال خارجي: WAN أو route أو مزود الخدمة.
- IP خارجي يعمل واسم النطاق يفشل: DNS.
- الراوتر نفسه يصل للإنترنت والعميل لا يصل: firewall/NAT أو DNS/DHCP أو شبكة العميل.
