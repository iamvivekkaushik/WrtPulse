# Mesh Troubleshooting Matrix

| العرض | أسباب محتملة | فحوصات أولية |
|---|---|---|
| العقدة لا تنضم | اختلاف القناة/التشفير/المعرّف أو عدم دعم الميزة | `iw list`, `iw dev`, `logread` |
| اتصال متقطع | تداخل، إشارة ضعيفة، إعادة محاولات مرتفعة | `iw station dump`, `iw survey dump` |
| السرعة منخفضة | backhaul لاسلكي مزدحم أو قفزات كثيرة | قياس كل وصلة منفردة |
| DHCP لا يعمل | bridge/VLAN أو DHCP relay/server غير صحيح | `bridge link`, `logread`, `ubus` |
| DNS لا يعمل | DNS upstream أو firewall أو DHCP option | `nslookup`, `logread` |
| BATMAN لا يرى عقداً | واجهة النقل غير مضافة أو module غير محمل | `batctl interface`, `batctl neighbors` |
| تجوال سيئ | sticky clients أو إعدادات 802.11r غير متوافقة | سجلات hostapd وقياسات العميل |

ابدأ دائماً بتحديد نطاق العطل: عميل واحد، نقطة وصول واحدة، وصلة backhaul، أم الشبكة كلها.
