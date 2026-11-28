# Moonlight GameStream (GPL-3.0) protocol module, applied to every APK that links it.
# moonlight-core calls MoonBridge's static bridge* methods and fields by name through JNI.
-keep class com.limelight.nvstream.jni.** { *; }
# The renderer and audio callbacks are invoked from native threads through these interfaces.
-keep class * extends com.limelight.nvstream.av.video.VideoDecoderRenderer { *; }
-keep class * implements com.limelight.nvstream.av.audio.AudioRenderer { *; }
-keep class * implements com.limelight.nvstream.NvConnectionListener { *; }
# Same JCA providers upstream keeps for pairing and AES-GCM session keys.
-keep class org.bouncycastle.jcajce.provider.asymmetric.* { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.util.* { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.* { *; }
-keep class org.bouncycastle.jcajce.provider.digest.** { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.** { *; }
-keep class org.bouncycastle.jcajce.spec.* { *; }
-keep class org.bouncycastle.jce.** { *; }
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.naming.**
-dontwarn java.nio.file.*
