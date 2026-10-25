# Kotlin serialization keeps its own rules via the library; these cover what is looked up by name at runtime.
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }
-keep class com.repdayrise.app.notifications.*Receiver { *; }
-keep class com.repdayrise.app.widget.** { *; }
# WorkManager (which Glance uses to render every widget) creates input mergers and workers by reflection. Without these,
# R8 strips the no-arg constructor of OverwritingInputMerger and every widget update job fails before it starts.
-keep class * extends androidx.work.InputMerger { public <init>(); }
-keep class * extends androidx.work.ListenableWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }
