# Don't warn about checkerframework and Kotlin annotations
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**

# Use -keep to explicitly keep any other classes shrinking would remove
-dontoptimize
-dontobfuscate