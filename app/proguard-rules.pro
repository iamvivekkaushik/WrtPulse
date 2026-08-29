# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# --- SSH transport ---------------------------------------------------------
# jsch resolves cipher/KEX/signature implementations by class name from its config
# tables, so R8 can't see the references. Keep the implementation packages.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# jsch uses BouncyCastle's lightweight API for curve25519/ed25519 on Android
# (its JDK15+ classes live in META-INF/versions and are stripped by D8).
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.math.** { *; }
-keep class org.bouncycastle.util.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
