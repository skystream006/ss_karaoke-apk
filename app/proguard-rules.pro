# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the Android SDK tools.

# Keep the Kotlin Intrinsics
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }

# Keep AndroidX security crypto
-keep class androidx.security.crypto.** { *; }

# Keep JavaScript interface methods (called via reflection from JS)
-keepclassmembers class com.sskaraoke.app.MainActivity$CredentialBridge {
    @android.webkit.JavascriptInterface <methods>;
}
