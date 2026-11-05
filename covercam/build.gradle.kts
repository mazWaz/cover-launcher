plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
}

android {
  namespace = "com.mazwas.covercam"
  compileSdk = 37

  defaultConfig {
    // Deliberately the package name Samsung allowlists for its own camera stack:
    // hidden physical camera ids (56 main, 58 ultrawide) are only visible to
    // packages on that list. Same mechanism every Samsung GCam mod uses.
    applicationId = "com.samsung.android.ruler"
    minSdk = 34
    targetSdk = 36
    versionCode = 1
    versionName = "0.1"
  }

  buildFeatures { compose = true }

  buildTypes {
    release {
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("debug")
    }
  }
}

dependencies {
  implementation(platform("androidx.compose:compose-bom:2026.09.00"))
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.ui:ui-tooling-preview")
  debugImplementation("androidx.compose.ui:ui-tooling")
  implementation("androidx.activity:activity-compose:1.13.0")

  testImplementation("junit:junit:4.13.2")
}
