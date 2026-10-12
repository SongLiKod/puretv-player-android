# PureTV Player 混淆规则
# Moshi 反射/生成适配器
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keep class com.puretv.player.data.**JsonAdapter { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }

# Kotlin 反射（Moshi Kotlin 适配所需）
-dontwarn org.jetbrains.annotations.**
-keepclassmembers class kotlin.Metadata { public <methods>; }

# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# Media3 / ExoPlayer
-dontwarn androidx.media3.**
-keep class androidx.media3.exoplayer.** { *; }

# Hilt / Dagger
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# 保留数据模型（JSON 序列化）
-keep class com.puretv.player.data.model.** { *; }
-keep class com.puretv.player.data.local.entity.** { *; }
