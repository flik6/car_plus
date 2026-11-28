# Qiri+ Android ProGuard / R8 Rules

# Keep custom View constructors invoked by layout inflation or RemoteViews
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep AppWidget providers, BroadcastReceivers, Services, and Activities
-keep public class com.chery.carplus.widget.** { *; }
-keep public class com.chery.carplus.ble.PassiveEntry* { *; }
-keep public class com.chery.carplus.ui.MainActivity { *; }

# Keep WebView @JavascriptInterface bridge methods (3D Car Scene & Map WebView)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep Cloud / Profile / BLE data models and enum constants used in JSON/serialization
-keep class com.chery.carplus.cloud.** { *; }
-keep class com.chery.carplus.profile.** { *; }
-keep class com.chery.carplus.ble.** { *; }

# Keep Kotlin metadata & coroutines
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**
-dontwarn org.bouncycastle.**
