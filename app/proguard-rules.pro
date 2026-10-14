# Sill keeps no reflection-based serialization: JSON goes through org.json by hand.

# The QR scanner (play-services-code-scanner) starts ML Kit, which builds its component registrars
# by reflection from manifest metadata; R8's full mode strips their no-argument constructors, and
# ML Kit then logs NoSuchMethodException at launch (seen on a release build, 2026-10-14).
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.mlkit.common.internal.CommonComponentRegistrar { <init>(); }
-keep class com.google.mlkit.vision.common.internal.VisionCommonRegistrar { <init>(); }
-keep class com.google.mlkit.vision.codescanner.internal.** { *; }
