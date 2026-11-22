# sherpa-onnx: the JNI side looks up these classes, their fields and constructors by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# TTS sample callbacks: native code calls invoke([F)Ljava/lang/Integer; on the object passed in.
-keep class com.echobedside.feature.assistant.voice.SampleCallback { *; }
