# IEEE 802.11s Mesh

## متى يستخدم؟
لإنشاء وصلات Mesh على مستوى Wi-Fi عندما يدعمها chipset وتعريف mac80211 والـ firmware.

## فحوصات
```sh
iw list | sed -n '/Supported interface modes:/,/Band /p'
iw dev
iw dev <mesh-interface> station dump
iw dev <mesh-interface> mpath dump
logread | grep -Ei 'mesh|80211s|wpa|hostapd|nl80211'
```

## نقاط التصميم
- تأكد من دعم `mesh point` في قدرات الراديو.
- اتفق على التردد والقناة وعرض القناة ومعرّف Mesh ID وإعدادات الأمان على جميع العقد.
- لا تفترض أن كل حزم hostapd أو إصدارات OpenWrt تتصرف بالطريقة نفسها.
- افصل شبكة الإدارة عن شبكة العملاء متى أمكن.
- راجع مسارات mesh وpeer links وحالة البوابة.

## عند تعطل الربط
افحص توافق القناة وعرضها، Mesh ID، التشفير، جودة الإشارة، توافق البرامج الثابتة، ثم راجع سجلات hostapd/kernel.
لا تغيّر إعدادات اللاسلكي عن بُعد دون وصول احتياطي.
