
# SmartTube MediaServiceCore is accessed through SmartTubeEngine by reflection so Ambient TV
# can keep the upstream engine replaceable. Keep public names/classes intact in release builds.
-keep class com.liskovsoft.youtubeapi.** { *; }
-keep class com.liskovsoft.youtubeapi.innertube.InnertubeService { *; }
-keep class com.liskovsoft.mediaserviceinterfaces.** { *; }
-keep class com.liskovsoft.sharedutils.prefs.GlobalPreferences { *; }
# Auth/account models (UserCode, AccessToken, ...) and the @JsonPath converter live outside
# youtubeapi.**. Their fields are filled by annotation reflection; renamed fields left the
# device-code response empty ("User code result is empty") in release builds.
-keep class com.liskovsoft.googlecommon.** { *; }
-keep class com.liskovsoft.googleapi.** { *; }
# SmartTube constructs GsonJsonProvider/GsonMappingProvider directly. Keep the
# used JsonPath API and names, but do not root optional Jakarta/Jackson/etc.
# providers that are not on the Android classpath.
-keep,allowshrinking class com.jayway.jsonpath.** { *; }
# UrlEncodedQueryString.Separator overrides toString() per enum constant ("&", ";"). R8 folded
# those calls to the constant names, so stream URLs were joined with "AMPERSAND" and split on
# its letters: every googlevideo request failed with HTTP 403.
-keep class com.liskovsoft.sharedutils.querystringparser.** { *; }
-dontwarn com.jayway.jsonpath.**
# Retrofit interfaces and Gson TypeTokens need generic signatures at runtime.
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleParameterAnnotations

# libvlcjni resolves these wrapper types and members by their Java names during JNI_OnLoad.
# R8 renaming them makes the release process exit before VLC can create a player.
-keep class org.videolan.libvlc.** { *; }
-keep interface org.videolan.libvlc.** { *; }

# dnsjava 3.x: WindowsResolverConfigProvider only creates its JNA-backed
# implementation when os.name contains Windows. JndiContextResolverConfigProvider
# skips its JNDI-backed implementation when java.vendor contains Android.
# Neither optional desktop implementation is used by SmartTube's Android DNS.
# Keep these exceptions narrow; do not suppress dnsjava or all JNA warnings.
-dontwarn com.sun.jna.platform.win32.Guid$GUID
-dontwarn com.sun.jna.platform.win32.Win32Exception
-dontwarn javax.naming.NamingException
-dontwarn javax.naming.directory.DirContext
-dontwarn javax.naming.directory.InitialDirContext
# JNA itself is also optional when the Monogram native AAR is disabled.
-dontwarn com.sun.jna.Library
-dontwarn com.sun.jna.Memory
-dontwarn com.sun.jna.Native
-dontwarn com.sun.jna.Pointer
-dontwarn com.sun.jna.Structure
-dontwarn com.sun.jna.Structure$*
-dontwarn com.sun.jna.WString
-dontwarn com.sun.jna.ptr.IntByReference
-dontwarn com.sun.jna.win32.W32APIOptions
# Lombok's generated-code marker is build-time metadata, not a runtime API.
-dontwarn lombok.Generated
# The Java 18+ InetAddressResolver SPI is not an Android API. Its multi-release
# service descriptor is excluded by Android packaging (see app/build.gradle.kts).
-dontwarn java.net.spi.InetAddressResolverProvider
-dontwarn org.xbill.DNS.spi.DnsjavaInetAddressResolverProvider

# GameArchives rejects Zstandard ZIP methods before Commons Compress can call its optional decoder.
-dontwarn com.github.luben.zstd.ZstdInputStream

# Project-owned JNI bridges use statically exported Java_com_* symbols. Keep their class and
# native method names stable in release/R8 builds even if default Android rules change.
-keep class com.ambient.tv.ui.player.backend.MpvNativeBridge { *; }
-keep class com.ambient.tv.ui.player.backend.VulkanNativeProbe { *; }
-keep class com.ambient.tv.ui.player.backend.SvpNative { *; }
# VLC 3/4 track compatibility and runtime diagnostics access these public methods/fields.
-keepclassmembers class org.videolan.libvlc.MediaPlayer { public *; }
-keepclassmembers class org.videolan.libvlc.Media { public *; }
-keepclassmembers class org.videolan.libvlc.interfaces.IMedia$* { public *; }
-keep class com.ambient.tv.ui.common.projectm.ProjectMNativeBridge { *; }
-keep class dev.rusty.app.NativeBridge { *; }
# Rust calls these static methods by class/name; no Java call makes them reachable for R8.
-keep class dev.rusty.app.SpotifyService { *; }
-keep class dev.rusty.app.SpotifyService$Companion { *; }
# Rusty >= 2.7 audio sink: Rust constructs PcmOutput and calls write/play/pause/release/underrunCount.
-keep class dev.rusty.app.audio.PcmOutput { *; }

# Additional SmartTube reflection targets. These are looked up by fully-qualified class and method
# name from SmartTubeEngine; R8 cannot safely infer the reachability from those strings/proxies.
-keep class com.liskovsoft.sharedutils.locale.LocaleUpdater { *; }
# SmartTube account export splits the stored account list with Helpers.splitArray; without this R8
# drops the method and every settings snapshot fails, so the phone could never send settings.
-keep class com.liskovsoft.sharedutils.helpers.Helpers { *; }
-keep class com.liskovsoft.googlecommon.common.locale.LocaleManager { *; }
-keep class io.reactivex.** { *; }

# SMBJ publishes connection events through MBassador's annotated handlers.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class com.hierynomus.smbj.** {
    @net.engio.mbassy.listener.Handler <methods>;
}
# SMBJ selects NTLM on Android. Its optional desktop Kerberos authenticator is not used.
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.kerberos.**

# MBassador's optional expression-language filters are unused by SMBJ's plain @Handler
# methods. Android has no Java EL; no condition/filter expression is registered here.
-dontwarn javax.el.BeanELResolver
-dontwarn javax.el.ELContext
-dontwarn javax.el.ELResolver
-dontwarn javax.el.ExpressionFactory
-dontwarn javax.el.FunctionMapper
-dontwarn javax.el.ValueExpression
-dontwarn javax.el.VariableMapper

# jcifs-ng (SMB1 sources only): its servlet NTLM filter and Kerberos authenticator are unused.
-dontwarn javax.servlet.**
-dontwarn jcifs.http.**
# jcifs computes the NT hash with MessageDigest.getInstance("MD4", BouncyCastleProvider). The
# provider finds MD4 by class-name strings, so these must keep their names and constructors.
-keep class org.bouncycastle.jcajce.provider.digest.MD4 { *; }
-keep class org.bouncycastle.jcajce.provider.digest.MD4$* { *; }

# SFTP (sshj): SftpAccess registers the full BouncyCastleProvider under "BC"; the provider maps
# algorithm names to class-name strings (ciphers, key agreement, Ed25519, key parsing), so R8 must
# keep them. sshj itself builds its algorithm factories reflectively as well.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.pqc.jcajce.provider.** { *; }
-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.sshj.** { *; }
# sshj's GSSAPI/Kerberos login and optional desktop pieces do not exist on Android and are unused.
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.login.**
-dontwarn sun.security.**
-dontwarn org.slf4j.impl.**

# WireGuard profile is kotlinx-serialized into preferences and settings backups.
-keep class com.ambient.tv.vpn.WireGuardProfile { *; }
-keep class com.ambient.tv.vpn.WireGuardProfile$$serializer { *; }

# Monogram's optional native MTProto adapter is loaded by its fully-qualified class
# name, never by a direct static reference. Keep the constructor for release R8.
# The upstream AAR supplies additional UniFFI/JNA consumer rules.
-keep class com.ambient.tv.data.telegram.MonogramNativeBackend { *; }
