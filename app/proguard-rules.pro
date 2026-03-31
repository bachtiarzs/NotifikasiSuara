# Add project specific ProGuard rules here.
-keep class com.bachtiarzs.notifikasisuara.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
