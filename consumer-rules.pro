# Keep RTC SDK classes
-keep class com.sy.rtc.sdk.** { *; }

# Keep WebRTC classes (required by native JNI in libjingle_peerconnection_so.so)
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Keep OkHttp (used for HTTP calls)
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
