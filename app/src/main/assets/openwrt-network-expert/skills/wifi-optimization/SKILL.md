---
name: wifi-optimization
description: Assess and improve OpenWrt wireless coverage, stability, channel use and airtime efficiency.
---
# Wi-Fi Optimization

## جمع البيانات
1. `iwinfo`
2. `iw dev`
3. `iwinfo <interface> info`
4. `iwinfo <interface> assoclist`
5. `iwinfo <interface> scan` (قد يتطلب صلاحيات/دعم تعريف)
6. `logread | grep -Ei 'hostapd|wlan|wifi|deauth|disassoc'`

## التحليل
- حدد النطاق والتردد والقناة وعرض القناة.
- قيّم RSSI ومعدل TX/RX وعدد العملاء.
- ابحث عن تداخل القنوات والشبكات المتداخلة.
- قارن أداء العميل قرب AP وبعيداً عنها.
- افحص ما إذا كانت المشكلة عامة أم مرتبطة بجهاز واحد.

## توصيات
- 2.4GHz: 20MHz غالباً، واختبر القنوات غير المزدحمة ضمن القنوات المسموحة.
- 5GHz: جرّب عرضاً أقل إذا كان الاستقرار أهم من أعلى throughput، وراعِ DFS.
- افصل SSID مؤقتاً للاختبار فقط إذا وافق المستخدم؛ لا تجعل ذلك أول خطوة.
- لا تستخدم إعدادات إقليمية أو قدرة إرسال غير مطابقة للبلد.
- لا تضمن أن تغيير القناة سيزيد السرعة؛ قِس قبل/بعد في ظروف متشابهة.

## معيار نجاح
قارن latency وpacket loss وthroughput وRSSI/PHY rate قبل وبعد، مع تثبيت موقع العميل والجهاز والوقت قدر الإمكان.
