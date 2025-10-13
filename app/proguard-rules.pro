-dontobfuscate

-keepattributes SourceFile,LineNumberTable

# Keep Navigation SDK classes
-keep class com.google.android.libraries.navigation.** { *; }
-keep class com.google.android.gms.maps.** { *; }
-dontwarn com.google.android.libraries.navigation.**

# Keep gRPC classes (used by Navigation SDK)
-keepclassmembers class io.grpc.** { *; }
-dontwarn io.grpc.**