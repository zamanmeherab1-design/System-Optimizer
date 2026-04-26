# Obfuscate everything
-keep class com.samsung.android.app.smartcapture.** { *; }
-dontwarn com.samsung.android.knox.**
-renamesourcefileattribute SourceFile
-repackageclasses 'a/b/c'
-allowaccessmodification
-keepattributes *Annotation*,Signature,EnclosingMethod
-dontnote **

# WorkManager
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker
