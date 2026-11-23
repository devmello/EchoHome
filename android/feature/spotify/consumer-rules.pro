# JNI: libvesper_spotify.so binds NativeBridge's natives by name and calls the Listener's methods by name.
-keep class app.vesper.feature.spotify.NativeBridge { native <methods>; }
-keep interface app.vesper.feature.spotify.NativeBridge$Listener { *; }
-keep class * implements app.vesper.feature.spotify.NativeBridge$Listener { *; }
