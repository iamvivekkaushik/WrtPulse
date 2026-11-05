---
name: network-performance
description: Measure latency, packet loss, DNS response and throughput bottlenecks on OpenWrt.
---
# Network Performance

## القياس
- Ping للبوابة ثم عنوان IP خارجي ثم اسم نطاق.
- قِس في وقت خمول الشبكة ثم أثناء تنزيل/رفع لمعرفة bufferbloat.
- استخدم `iperf3` بين جهازين داخل LAN إذا كان مثبتاً؛ لا تخلط نتيجة LAN بسرعة الإنترنت.
- استخدم SQM فقط بعد قياس سرعة WAN الفعلية وتحديد واجهة WAN الصحيحة.

## تجنب
- لا تدّعِ أن تغيير DNS يزيد سعة الخط.
- لا تشغّل اختبارات تحميل كثيفة دون موافقة.
- لا تثبّت حزم أو تغيّر SQM/Firewall دون موافقة.
- كرر القياس عدة مرات وسجل المتوسط والتذبذب.
