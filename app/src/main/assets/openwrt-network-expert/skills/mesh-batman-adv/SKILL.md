# BATMAN-ADV

## المفهوم
BATMAN-ADV بروتوكول توجيه Layer 2 يستخدم واجهة batman مثل `bat0` فوق واجهة/واجهات نقل. يمكن أن يعمل فوق 802.11s أو Ethernet أو وسائل نقل مدعومة أخرى.

## فحوصات آمنة
```sh
command -v batctl
batctl -v
ip -d link show
batctl meshif bat0 interface
batctl meshif bat0 originators
batctl meshif bat0 neighbors
batctl meshif bat0 gateways
batctl meshif bat0 statistics
logread | grep -Ei 'batman|batadv'
```

## التشخيص
- تحقق من تحميل `batman_adv` ووجود `bat0`.
- تحقق من إضافة واجهة النقل الصحيحة إلى batman.
- افحص originators وneighbors وجودة TQ.
- افحص اختيار البوابة ومساراتها.
- راجع MTU عند وجود تغليف أو VLAN.
- تحقق من عدم وجود حلقة أو جسر مكرر.

## تنبيه
أوامر إنشاء الواجهات أو ضمها إلى batman تغيّر الشبكة وقد تقطع الاتصال؛ لا تنفذها دون موافقة صريحة وخطة رجوع.
