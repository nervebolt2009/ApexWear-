-keep class com.echostream.data.** { *; }
-keep class com.echostream.network.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keepclassmembers class * { @com.squareup.moshi.Json *; }
-keep @com.squareup.moshi.JsonClass class * { *; }
-keep class com.squareup.moshi.** { *; }
-keep class androidx.media3.** { *; }
-keep class androidx.wear.compose.** { *; }
-keepattributes Signature, Exceptions, InnerClasses
-dontwarn okhttp3.**, okio.**, kotlinx.coroutines.**, androidx.media3.**, androidx.wear.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
