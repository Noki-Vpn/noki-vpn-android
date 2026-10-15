# Keep R8 shrinking and optimization, but preserve class and member names.
-dontobfuscate

-keep class io.nekohasekai.libbox.** { *; }
-keep class go.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
