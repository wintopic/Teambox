# Preserve metadata used by Kotlin serialization and useful local stack traces.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault
-keepattributes InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# Android manifest components are kept by the Android Gradle plugin. SQLDelight,
# Compose, Kotlin serialization and ML Kit ship consumer rules where required.
