plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
  id("org.jetbrains.kotlin.plugin.serialization")
}

android {
  namespace = "com.mazwas.coverlauncher"
  compileSdk = 37
  defaultConfig {
    applicationId = "com.mazwas.coverlauncher"
    minSdk = 34 // Z Flip5 and up (One UI 6+)
    targetSdk = 36
    versionCode = 2
    versionName = "1.0"
  }
  buildFeatures { compose = true }
  buildTypes {
    release {
      // Sideloaded on one phone, so the debug key is fine. R8 is what makes Compose smooth.
      isMinifyEnabled = true
      isShrinkResources = true
      signingConfig = signingConfigs.getByName("debug")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
}

dependencies {
  implementation(platform("androidx.compose:compose-bom:2026.09.00"))
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.ui:ui-tooling-preview")
  debugImplementation("androidx.compose.ui:ui-tooling")
  implementation("androidx.activity:activity-compose:1.13.0")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
  implementation("androidx.navigation:navigation-compose:2.10.2")
  implementation("androidx.datastore:datastore-preferences:1.3.0")
  implementation(platform("io.insert-koin:koin-bom:4.2.2"))
  implementation("io.insert-koin:koin-androidx-compose")
  implementation("dev.rikka.shizuku:api:13.1.5")
  implementation("dev.rikka.shizuku:provider:13.1.5")

  testImplementation("junit:junit:4.13.2")
}
