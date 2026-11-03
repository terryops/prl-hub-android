# Readable stack traces from release builds (mapping.txt is in app/build/outputs/mapping/).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# window.PearlAndroid: the WebView calls the listener through androidx.webkit's boundary
# interfaces. androidx.webkit ships rules for its side; keep ours explicitly as well.
-keep class com.prl.wizard.Bridge { *; }
-keep class * implements androidx.webkit.WebViewCompat$WebMessageListener { *; }

# Started by the system from the manifest; Firebase ships its own consumer rules.
-keep class com.prl.wizard.PushService { *; }
