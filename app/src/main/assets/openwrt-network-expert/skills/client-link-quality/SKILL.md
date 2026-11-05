---
name: client-link-quality
description: Assess per-client Wi-Fi link quality, retries, rates, roaming and sticky-client symptoms on OpenWrt.
---
# Client Link Quality

## جمع المعلومات
- `iwinfo <iface> assoclist`
- `iw dev <iface> station dump`
- `logread | grep -Ei 'deauth|disassoc|auth|assoc|roam'`
- سجّل RSSI وTX/RX bitrate وpacket counters وretries إذا كانت متاحة.

## التفسير
- PHY bitrate ليس سرعة تطبيق أو إنترنت.
- ارتفاع retries مع RSSI جيد قد يشير إلى ازدحام أو تداخل أو مشكلة عميل.
- RSSI ضعيف مع انقطاع متكرر قد يشير إلى تغطية غير كافية أو عوائق.
- sticky client: عميل يبقى متصلاً بنقطة بعيدة؛ لا تفترض أن الراوتر وحده يستطيع إجباره على roaming.
- قارن أكثر من عميل وفي الموقع نفسه لتحديد هل المشكلة عامة أم خاصة بجهاز.

## التقرير
اعرض بيانات العميل بعد إخفاء MAC عند مشاركة التقرير خارجياً. لا تطرد العملاء أو تغيّر إعدادات roaming تلقائياً.
