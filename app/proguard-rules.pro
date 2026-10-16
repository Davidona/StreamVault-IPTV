# StreamVault ProGuard Rules
# ============================================================

# ── Kotlin ──────────────────────────────────────────────────
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }

# ── Hilt / Dagger ───────────────────────────────────────────
-keepclasseswithmembers class * {
    @dagger.* <methods>;
}
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# ── Retrofit / OkHttp ───────────────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
# SEC-H02: Only keep what Retrofit actually requires — interface methods & response types.
-keep,allowobfuscation interface retrofit2.Call
-keep,allowobfuscation interface retrofit2.Callback
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-keepclassmembers,allowobfuscation class * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepnames class okhttp3.internal.platform.**
-keepnames class okhttp3.internal.tls.**

# ── GSON ────────────────────────────────────────────────────
-keepattributes Signature
-keepattributes EnclosingMethod
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
# Keep fields that GSON accesses via reflection
-keepclassmembers class com.streamvault.data.remote.** { <fields>; }
# OAuth records must retain their schema and reflective constructors across releases.
-keep class com.streamvault.data.trakt.TraktTokens { *; }
-keep class com.streamvault.data.trakt.TraktPendingLogin { *; }
-keep class com.streamvault.data.trakt.TraktStoredSession { *; }

# ── Room ────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ── Domain models (serialized by GSON / passed across modules) ──
-keep class com.streamvault.domain.model.** { *; }
-keep class com.streamvault.domain.manager.BackupData { *; }
-keep class com.streamvault.domain.manager.ProviderBackupSnapshot { *; }
-keep class com.streamvault.domain.manager.PortableProviderPreferencesBackup { *; }
-keep class com.streamvault.domain.manager.PortablePlaybackQualityPreferenceBackup { *; }
-keep class com.streamvault.domain.manager.BackupProviderReference { *; }
-keep class com.streamvault.domain.manager.PortableCategoryReference { *; }
-keep class com.streamvault.domain.manager.PortableVirtualGroupReference { *; }
-keep class com.streamvault.domain.manager.PortableChannelReference { *; }
-keep class com.streamvault.domain.manager.ProtectedCategoryBackup { *; }
-keep class com.streamvault.domain.manager.ScheduledRecordingBackup { *; }
-keep class com.streamvault.data.local.entity.** { *; }
-keep class com.streamvault.data.remote.xtream.model.** { *; }

# ── Security / TLS ──────────────────────────────────────────
-keepnames class com.streamvault.data.security.CredentialCrypto
-keepclassmembers class com.streamvault.data.security.CredentialCrypto {
    <fields>;
    <methods>;
}

# ── Media3 / ExoPlayer ─────────────────────────────────────
# SEC-H01: Media3 ships its own consumer-proguard-rules.pro inside the AAR — no need
# for a broad keep here. Only explicitly keep classes loaded via reflection that the
# AAR rules don't cover (e.g., extension renderers resolved at runtime).
-keep class androidx.media3.exoplayer.DefaultRenderersFactory { *; }
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-dontwarn androidx.media3.decoder.ffmpeg.**
-dontwarn androidx.media3.**

# ── Coroutines ──────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ── Compose ─────────────────────────────────────────────────
-dontwarn androidx.compose.**

# ── Coil (image loading) ───────────────────────────────────
-dontwarn coil3.**

# ── DataStore ───────────────────────────────────────────────
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}

# ── General ─────────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable       # Better crash reports
-renamesourcefileattribute SourceFile
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
