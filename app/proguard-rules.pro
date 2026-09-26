# Keep GPL payload integrity: reflectively-touched classes (none today) and
# enums used by valueOf in error mapping.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keepclassmembers enum com.ventoydroid.app.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
