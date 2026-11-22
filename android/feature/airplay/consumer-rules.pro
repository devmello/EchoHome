# JNI: libecho_airplay.so binds NativeBridge's natives by name and calls the Listener's methods by name.
-keep class com.echobedside.feature.airplay.NativeBridge { native <methods>; }
-keep interface com.echobedside.feature.airplay.NativeBridge$Listener { *; }
-keep class * implements com.echobedside.feature.airplay.NativeBridge$Listener { *; }
