# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /opt/android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

# Ktor
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class io.netty.** { *; }
-dontwarn io.netty.**
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**

# Gson
-keep class com.google.gson.** { *; }
-keep class com.aanand.edgeaicore.** { *; }

# Service Loading
-keepnames class * extends io.ktor.server.engine.ApplicationEngineFactory
-keep class * extends io.ktor.server.engine.ApplicationEngine

# Reflection for Ktor Serialization
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.aanand.edgeaicore.ChatCompletionRequest { *; }
-keep class com.aanand.edgeaicore.ChatMessage { *; }
-keep class com.aanand.edgeaicore.ChatCompletionResponse { *; }
-keep class com.aanand.edgeaicore.Choice { *; }
-keep class com.aanand.edgeaicore.ChatMessageResponse { *; }
-keep class com.aanand.edgeaicore.Usage { *; }
-keep class com.aanand.edgeaicore.ErrorResponse { *; }
-keep class com.aanand.edgeaicore.ConversationResponse { *; }
-keep class com.aanand.edgeaicore.ConversationInfoResponse { *; }
