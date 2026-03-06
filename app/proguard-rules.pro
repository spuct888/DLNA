# ProGuard rules for DLNA Receiver

# Keep DLNA/UPnP classes
-keep class org.fourthline.cling.** { *; }
-keep class org.eclipse.jetty.** { *; }

# Keep AndroidX Media3
-keep class androidx.media3.** { *; }

# Keep our application classes
-keep class com.dlna.receiver.** { *; }

# General Android rules
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Keep annotations
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod
