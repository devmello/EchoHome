# JNI: libvesper_airplay.so binds NativeBridge's natives by name and calls the Listener's methods by name.
-keep class app.vesper.feature.airplay.NativeBridge { native <methods>; }
-keep interface app.vesper.feature.airplay.NativeBridge$Listener { *; }
-keep class * implements app.vesper.feature.airplay.NativeBridge$Listener { *; }
