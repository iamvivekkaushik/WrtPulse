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
# tables and instantiates them through their no-arg constructors, so R8 can't see
# the references. Only the names and those constructors must survive; everything
# the instances do is reached through ordinary interface calls, so method bodies
# stay eligible for R8 optimization (Play's DEX code optimization metric).
-keep,allowoptimization class com.jcraft.jsch.** { <init>(); }
-dontwarn com.jcraft.jsch.**

# jsch uses BouncyCastle's lightweight API for curve25519/ed25519 on Android
# (its JDK15+ classes live in META-INF/versions and are stripped by D8). The BC
# classes are referenced statically from jsch's bc.* adapters, so R8 traces them
# without keep rules.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
