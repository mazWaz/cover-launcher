buildscript {
  // AGP 9 built-in Kotlin defaults to KGP 2.2.10; pin the newer one
  dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20") }
}

plugins {
  id("com.android.application") version "9.5.0" apply false
  id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
  id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20" apply false
}
