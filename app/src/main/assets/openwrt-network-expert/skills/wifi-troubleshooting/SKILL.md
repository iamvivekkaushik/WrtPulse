---
name: wifi-troubleshooting
description: Diagnose Wi-Fi disconnects, weak signal, authentication failures and unstable clients on OpenWrt.
---
# Wi-Fi Troubleshooting

## الأعراض ومسار الفحص
- لا يظهر SSID: افحص الراديو، `wifi status`, `iwinfo`, وسجلات hostapd.
- فشل كلمة المرور/المصادقة: افحص سجلات hostapd وإعدادات التشفير دون كشف PSK.
- يتصل ولا يوجد إنترنت: اختبر البوابة ثم IP خارجي ثم DNS.
- انقطاع متكرر: راجع deauth/disassoc، RSSI، التداخل، الطاقة، وميزات roaming.
- جهاز واحد فقط متأثر: قارن قدرات العميل وتعريفه وإعداداته مع جهاز سليم.

## أوامر قراءة فقط
```sh
wifi status
iwinfo
iw dev
logread | grep -Ei 'hostapd|deauth|disassoc|wlan|wifi'
dmesg | grep -Ei 'wlan|wifi|firmware|ath|mtk|brcm'
```

لا تنفّذ `wifi reload` أو تغيّر التشفير أو القناة قبل موافقة المستخدم؛ قد يؤدي ذلك إلى قطع الاتصال.
