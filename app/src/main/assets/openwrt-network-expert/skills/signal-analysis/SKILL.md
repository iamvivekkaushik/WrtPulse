---
name: signal-analysis
description: Interpret OpenWrt client signal, noise, SNR and link-rate measurements.
---
# Signal Analysis

اجمع `iwinfo <iface> info` و`iwinfo <iface> assoclist` أو `iw station dump` إذا كان مدعوماً.

## التفسير
- RSSI أقرب إلى الصفر عادةً أفضل؛ القيم تختلف حسب الجهاز والبيئة.
- SNR = RSSI - noise عندما تتوفر القيمتان وبالوحدة نفسها.
- PHY rate ليس سرعة الإنترنت الفعلية؛ throughput أقل عادةً بسبب البروتوكول والتنافس وإعادة الإرسال.
- قيّم الاتجاهين: قد يسمع العميل نقطة الوصول جيداً لكن نقطة الوصول لا تسمع العميل جيداً.
- لا تصنف الرابط من RSSI وحده؛ راقب retries وpacket loss وlatency.

اطلب قياسات من موقع العميل الفعلي، وليس من الراوتر فقط.
