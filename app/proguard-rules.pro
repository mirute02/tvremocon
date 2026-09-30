# Add project specific ProGuard rules here.

# Setup screens and logs print exception class names (e.javaClass.simpleName) so a failure
# can be told apart from outside; obfuscated they would read "si" instead of
# HubAuthException. The app's own code is small, so keeping its names costs little.
-keepnames class com.tvremocon.** { *; }
# Readable stack traces from `adb logcat`.
-keepattributes SourceFile,LineNumberTable
