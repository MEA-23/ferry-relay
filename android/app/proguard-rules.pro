# R8 rules for release builds. Most libraries ship their own; these cover the rest.

# SQLCipher's native code calls back into these classes by name.
-keep class net.zetetic.database.** { *; }
