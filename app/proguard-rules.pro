# Shell.kt calls this private method by reflection; R8 would drop it as unused.
-keepclassmembers class rikka.shizuku.Shizuku {
  private static *** newProcess(...);
}
