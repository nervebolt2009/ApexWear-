# ── Your app packages ────────────────────────────────────────────────────────
-keep class com.echostream.data.**    { *; }
-keep class com.echostream.network.** { *; }
-keep class com.echostream.player.**  { *; }   # <── THIS WAS MISSING (MusicPlaybackService)
-keep class com.echostream.ui.**      { *; }   # keep any ViewModel / composable state holders

# ── Android Service/Activity loaded by system via reflection ──────────────────
-keep public class * extends android.app.Service
-keep public class * extends android.app.Activity
-keep public class * extends androidx.media3.session.MediaSessionService

# ── Media3 / ExoPlayer ───────────────────────────────────────────────────────
-keep class androidx.media3.** { *; }
-keep class androidx.wear.compose.** { *; }
-keepattributes Signature, Exceptions, InnerClasses
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-dontwarn okhttp3.**, okio.**, kotlinx.coroutines.**, androidx.media3.**, androidx.wear.**

-keep class androidx.media3.exoplayer.audio.**      { *; }
-keep class androidx.media3.exoplayer.source.**     { *; }
-keep class androidx.media3.extractor.**            { *; }
-keep class androidx.media3.datasource.**           { *; }
-keep class androidx.media3.datasource.okhttp.**    { *; }
-keep class androidx.media3.session.**              { *; }
-keep class androidx.media3.common.**               { *; }
-keepclassmembers class androidx.media3.exoplayer.** { *; }
-dontwarn androidx.media3.**

# ── OkHttp + Okio ─────────────────────────────────────────────────────────────
-keep class okhttp3.**           { *; }
-keep interface okhttp3.**       { *; }
-keep class okio.**              { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# ── Moshi ─────────────────────────────────────────────────────────────────────
-keepclassmembers class * { `@com.squareup.moshi.Json` *; }
-keep `@com.squareup.moshi.JsonClass` class * { *; }
-keep class com.squareup.moshi.** { *; }

# ── Room ──────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep `@androidx.room.Entity` class * { *; }
-keep `@androidx.room.Dao` interface * { *; }

# ── Kotlin ────────────────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }
-dontwarn kotlin.**

# ── Kotlin Coroutines ─────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ── NewPipe Extractor ─────────────────────────────────────────────────────────
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**

# ── Wear Compose ─────────────────────────────────────────────────────────────
-keep class androidx.wear.compose.** { *; }
-dontwarn androidx.wear.**

# ── Parcelable (used by MediaSession, etc.) ───────────────────────────────────
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ── Enums ─────────────────────────────────────────────────────────────────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Attributes ────────────────────────────────────────────────────────────────
-keepattributes Signature, Exceptions, InnerClasses, EnclosingMethod
