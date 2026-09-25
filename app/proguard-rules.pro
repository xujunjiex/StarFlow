# ============================================================
# StarFlow ProGuard/R8 Rules
# ============================================================

# --- 基础保留 ---
-keepattributes SourceFile,LineNumberTable        # 保留行号（崩溃堆栈可读）
-renamesourcefileattribute SourceFile               # 隐藏源文件名
-keepattributes *Annotation*                       # 保留注解
-keepattributes Signature                          # 保留泛型签名
-keepattributes Exceptions                         # 保留异常声明

# --- Android 四大组件 ---
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# --- Fragment ---
-keep public class * extends androidx.fragment.app.Fragment
-keep public class * extends android.app.Fragment

# --- Application ---
-keep public class * extends android.app.Application

# --- JNI Native 方法 ---
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- JNI 回调接口方法名（native 硬编码 GetMethodID 按名查找，混淆会导致 ART abort）---
# HyMt2StreamCallback 的 onToken/onPhase 由 hymt2_bridge.cpp 通过 GetMethodID 查找，
# R8 混淆改名后找不到 → art::ThrowNewExceptionF → abort（release-only 崩溃）
-keep class translationapi.hymt2translation.HyMt2StreamCallback { *; }
# LlamaCppStreamCallback 同理（llamacpp_bridge.cpp 通过 GetMethodID 查找 onToken/onPhase）
-keep class translationapi.llamacpp.LlamaCppStreamCallback { *; }

# --- Serializable / Parcelable ---
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# --- 枚举 ---
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- R 类 ---
-keepclassmembers class **.R$* {
    public static <fields>;
}

# --- ViewBinding ---
-keep class * implements androidx.viewbinding.ViewBinding {
    public static * inflate(android.view.LayoutInflater);
    public static * inflate(android.view.LayoutInflater, android.view.ViewGroup, boolean);
    public static * bind(android.view.View);
}

# --- Room 数据库 ---
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-keepclassmembers class * {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
}

# --- KeystoreManager (加密相关不能混淆) ---
-keep class com.moe.starflow.utils.KeystoreManager { *; }

# --- 翻译 API 接口 ---
-keep class com.moe.starflow.translate.TranslationTextAPI { *; }
-keep class com.moe.starflow.translate.TranslationPicAPI { *; }
-keep class * implements com.moe.starflow.translate.TranslationTextAPI { *; }
-keep class * implements com.moe.starflow.translate.TranslationPicAPI { *; }

# --- Constants 枚举 ---
-keep class com.moe.starflow.utils.Constants { *; }

# --- ONNX Runtime ---
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# --- ML Kit ---
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# --- OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# --- Glide ---
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { *; }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# --- JTS (几何库) ---
-keep class org.locationtech.jts.** { *; }
-dontwarn org.locationtech.jts.**

# --- Guava ---
-dontwarn com.google.common.**
-dontwarn com.google.errorprone.**
-keep class com.google.common.** { *; }

# --- Xerces XML ---
-dontwarn org.apache.xerces.**
-keep class org.apache.xerces.** { *; }

# --- Konfetti ---
-keep class nl.dionsegijn.konfetti.** { *; }

# --- ColorPicker ---
-keep class com.jaredrummler.colorpicker.** { *; }

# --- kotlinx.coroutines ---
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# --- Navigation ---
-keep class * extends androidx.navigation.Navigator

# --- 隐藏敏感字符串（release 移除 Log 调用）---
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# --- 防止类名被保留为可读形式 ---
# 允许混淆所有未被 keep 的类
-allowaccessmodification
-repackageclasses ''
