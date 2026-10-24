# Kotlin serialization keeps its own rules via the library; these cover what is looked up by name at runtime.
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }
-keep class com.repdayrise.app.notifications.*Receiver { *; }
-keep class com.repdayrise.app.widget.** { *; }
