# Conservative R8 rules. Most libraries ship their own consumer-rules.pro,
# so this file only adds project-specific keeps and silences expected warnings.

# Keep our public data classes — they are populated manually from JSONObject
# (no reflection), so R8 can rename internals freely. We keep their public
# names so logcat / mapping diffs stay readable.
-keep class com.example.planna.ApiService$* { *; }

# OkHttp / Okio — runtime reflection on optional features. Library ships rules,
# but suppress lingering warnings.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Kotlin reflection metadata is not needed at runtime for our usage patterns.
-dontwarn kotlin.reflect.**
