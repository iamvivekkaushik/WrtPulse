# التثبيت والاستخدام

## نسخ الأدوات إلى الراوتر
من جهاز Linux/macOS أو بيئة تدعم scp:

```sh
scp -r scripts root@192.168.1.1:/root/openwrt-network-expert/
ssh root@192.168.1.1
chmod +x /root/openwrt-network-expert/*.sh
```

## تشغيل الفحوصات
```sh
/root/openwrt-network-expert/diagnostics.sh
/root/openwrt-network-expert/wifi-scan.sh
/root/openwrt-network-expert/signal-monitor.sh
/root/openwrt-network-expert/network-test.sh
```

إذا لم يكن `iwinfo` موجوداً، يمكن تثبيته بعد التأكد من إصدار OpenWrt ومساحة التخزين:
```sh
opkg update
opkg install iwinfo
```
في إصدارات OpenWrt الحديثة قد يستخدم النظام `apk` بدلاً من `opkg`؛ تحقق من مدير الحزم المتاح ولا تنفذ التثبيت دون موافقة.

## توافق
السكربتات تستخدم `/bin/sh` وأوامر شائعة، لكن المخرجات تختلف حسب إصدار OpenWrt وتعريف Wi-Fi. بعض أوامر المسح قد لا تعمل على كل الشرائح أو أثناء تشغيل AP.
