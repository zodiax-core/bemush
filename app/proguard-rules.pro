# CampusMesh ProGuard / R8 Rules for Android Studio Release APK Export

# Keep Room Database Entities, DAOs, and Migrations
-keep class com.campusmesh.db.** { *; }
-keepclassmembers class com.campusmesh.db.** { *; }
-dontwarn com.campusmesh.db.**

# Keep Data Repositories and Identity Stores
-keep class com.campusmesh.data.** { *; }
-keep class com.campusmesh.identity.** { *; }
-keep class com.campusmesh.profile.** { *; }
-keep class com.campusmesh.transport.** { *; }
-keep class com.campusmesh.ble.** { *; }

# Keep Kotlinx Serialization Models (MeshPacket, PacketProtocol)
-keepclassmembers class com.campusmesh.packet.** { *; }
-keep class com.campusmesh.packet.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep Hilt / Dagger generated components
-keep class * extends dagger.hilt.internal.UnsafeCasts { *; }
-keep class dagger.hilt.** { *; }
-keep class com.campusmesh.di.** { *; }
-dontwarn dagger.hilt.**

# Keep Bluetooth LE and GATT callbacks
-keep class android.bluetooth.** { *; }
-keepclassmembers class android.bluetooth.** { *; }

# Keep Timber logging
-keep class timber.log.** { *; }
-dontwarn timber.log.**

# Keep Android Core & Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
