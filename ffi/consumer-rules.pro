# UniFFI's generated bindings reach Rust through JNA, which finds structures,
# callbacks and native methods reflectively. Renaming or removing any of them
# breaks the bridge at runtime.
-dontwarn java.awt.*
-keep class com.sun.jna.* { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }
-keep class dev.recached.ffi.** { *; }
