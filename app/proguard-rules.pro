# Keep app model classes used via reflection-free Gson/JSON is not used, so default rules apply.
# Jsoup is plain Java - keep it.
-dontwarn org.jsoup.**
-keepattributes Signature
-keepattributes *Annotation*

# OkHttp / Retrofit
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Google Maps
-keep class com.google.android.gms.maps.** { *; }
-dontwarn com.google.android.gms.**

# Kotlin metadata
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault