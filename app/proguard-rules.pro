# Keep GPL payload integrity: reflectively-touched classes (none today) and
# enums used by valueOf in error mapping.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keepclassmembers enum com.ventoydroid.app.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Google Play Services Ads / UMP ship consumer rules, but their optional
# dependencies (webview assets, cronet) trip R8 warnings — silence them.
-dontwarn com.google.android.gms.**
-dontwarn com.google.android.ump.**
-dontwarn javax.annotation.**
# Ads SDK loads adapter classes reflectively at runtime.
-keep class com.google.android.gms.ads.** { *; }
-keep class com.google.android.ump.** { *; }
