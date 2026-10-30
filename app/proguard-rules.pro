-dontnote android.net.http.*
-dontnote org.apache.http.**
# needafile's own code
-keep class de.needanapp.** { *; }

# SMBJ delivers internal events (closed trees/sessions/connections) through mbassador, which finds
# @Handler methods and instantiates its handler invocations reflectively.
-keep class net.engio.mbassy.** { *; }
-keepclassmembers class com.hierynomus.smbj.** { @net.engio.mbassy.listener.Handler <methods>; }
# optional features whose dependencies don't exist on Android (EL filters, Kerberos, java.rmi in dcerpc)
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn java.rmi.**
# sshj's bundled EdDSA engine references a JDK-internal class that Android doesn't have
-dontwarn sun.security.x509.**

# 7-Zip-JBinding calls back into its Java classes from native code
-keep class net.sf.sevenzipjbinding.** { *; }

# Cryptomator's cryptolib reads and writes the masterkey file with Gson, which fills its fields reflectively
-keep class org.cryptomator.** { *; }
# sshj's full BouncyCastle provider (see SftpManager) finds its algorithms by class name, e.g. AESWrap that unlocking a
# Cryptomator vault needs; without these rules R8 drops them and unlocking crashes after an SFTP connection
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-keepattributes Signature, RuntimeVisibleAnnotations
