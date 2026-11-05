# Termux Terminal Engine reflection keep rules
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }

# Apache Commons Compress
-keep class org.apache.commons.compress.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Proguard / R8 warnings
-dontwarn androidx.compose.**
-dontwarn com.termux.**
-dontwarn org.apache.commons.compress.**
