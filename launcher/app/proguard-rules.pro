# Shrink and optimise, but keep class and method names: the size win comes from dropping unused
# AndroidX code, not from renaming, and readable stack traces matter more in a sideloaded app
# where crash reports arrive as screenshots.
-dontobfuscate

# title_search's Anthropic SDK (Ask.java) reads and writes its request and response classes through Jackson by
# reflection, with Kotlin's metadata: keep them whole. Only loaded when a theme sets claude_api_key.
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
# Classes Android doesn't have, used only by parts of the SDK and Jackson this launcher never calls (Java beans, and
# the type-annotation reflection behind class-based structured outputs; Ask.java writes its schema by hand).
-dontwarn com.google.auto.value.AutoValue
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType
