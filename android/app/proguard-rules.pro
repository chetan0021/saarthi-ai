# ExecuTorch + fbjni are called from native code
-keep class org.pytorch.executorch.** { *; }
-keep class com.facebook.jni.** { *; }
-keep class com.facebook.soloader.** { *; }
-keep class com.bharatai.learning.hw.NativeBridge { *; }
-keepclassmembers class com.bharatai.learning.hw.NativeBridge { *; }
