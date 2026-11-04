---
name: channel-optimization
description: Evaluate Wi-Fi channel and channel-width choices on OpenWrt without applying changes automatically.
---
# Channel Optimization

1. اكتشف البلد عبر `uci show wireless` وراجع إعدادات `country`.
2. افحص القنوات المرئية عبر `iwinfo <iface> scan` إن توفر.
3. قارن عدد الشبكات وتداخلها وقوة إشاراتها وعرض قنواتها.
4. راعِ القيود المحلية وDFS في 5GHz.
5. لا توصي بقناة خارج القنوات المسموحة.
6. قدّم خياراً أو خيارين مع سبب الاختيار وقيود القياس.

لا تطبق `channel` أو `htmode` أو `country` تلقائياً. التغيير يتطلب موافقة وقد يقطع Wi-Fi.
