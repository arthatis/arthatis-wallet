# R8 rules for the release build. Most libraries (Room, WorkManager, ML Kit, CameraX, kotlinx.serialization) ship their own consumer rules.

# Records are (de)serialised by name: keep the model classes and their generated serializers.
-keep,includedescriptorclasses class com.arthatis.wallet.core.**$$serializer { *; }
-keepclassmembers class com.arthatis.wallet.core.** { *** Companion; }
-keepclasseswithmembers class com.arthatis.wallet.core.** { kotlinx.serialization.KSerializer serializer(...); }

# BouncyCastle is reached through JCA/provider lookup by name (ChaCha20-Poly1305, ML-KEM), which R8 cannot see.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# Crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
