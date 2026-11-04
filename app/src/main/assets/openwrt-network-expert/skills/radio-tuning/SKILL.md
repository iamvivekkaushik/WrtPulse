---
name: radio-tuning
description: Tune OpenWrt radio parameters conservatively using measured coverage and client compatibility.
---
# Radio Tuning

## ما يجب فحصه
- band, channel, htmode/channel width
- country/regulatory domain
- txpower الحالي والحدود المتاحة
- beacon interval وDTIM عند وجود مشكلة محددة فقط
- supported rates وlegacy compatibility
- عدد العملاء ومعدلاتهم وأخطاء/إعادات الإرسال إن توفرت

## مبادئ
- لا ترفع قدرة الإرسال كحل افتراضي؛ قد تزيد التداخل ولا تحسن uplink من العميل.
- لا تعطل معدلات قديمة أو ميزات توافق قبل معرفة أجهزة العملاء.
- لا تغيّر عدة إعدادات في وقت واحد.
- قِس latency وpacket loss وthroughput في موقع ثابت قبل وبعد.
- لا تعدّل beacon/DTIM لمجرد “التسريع”؛ اشرح أثرها على استهلاك البطارية والتجوال قبل اقتراحها.

## آلية تغيير آمنة
1. احفظ نسخة إعدادات.
2. سجّل القيم الحالية.
3. اقترح تغييراً واحداً فقط.
4. اطلب موافقة المستخدم.
5. طبّق التغيير في نافذة صيانة مع وسيلة وصول بديلة إن أمكن.
6. تحقق من عودة SSID والاتصال والعملاء.
7. أعد القيم السابقة إذا فشل التحقق.
