# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK tools.

# Keep ONNX Runtime classes
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }

# Keep NPY library classes
-keep class org.jetbrains.bio.npy.** { *; }

# Keep IPA Transcribers
-keep class com.github.medavox.ipa_transcribers.** { *; }
