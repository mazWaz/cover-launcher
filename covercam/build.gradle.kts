plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
}

android {
  namespace = "com.mazwas.covercam"
  compileSdk = 37

  defaultConfig {
    // Deliberately the package name Samsung GCam mods use: Samsung's cameraserver filters the lens list per
    // package. Measured on the phone with this name: cameraIdList = 0,1,2,3 (the physical ids 56/58 stay hidden).
    // What a different name would lose is untested. See covercam/README.md.
    applicationId = "com.samsung.android.ruler"
    minSdk = 34
    targetSdk = 36
    versionCode = 1
    versionName = "0.1"
  }

  buildFeatures { compose = true }

  buildTypes {
    release {
      // Sideloaded on one phone, so the debug key is fine. R8 is what makes Compose smooth (same as :app).
      isMinifyEnabled = true
      isShrinkResources = true
      signingConfig = signingConfigs.getByName("debug")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
    }
  }
}

dependencies {
  implementation(platform("androidx.compose:compose-bom:2026.09.00"))
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.ui:ui-tooling-preview")
  debugImplementation("androidx.compose.ui:ui-tooling")
  implementation("androidx.activity:activity-compose:1.13.0")

  // MVI plumbing: ViewModel + collectAsStateWithLifecycle, Koin 4 DI (constructor DSL,
  // no compiler plugin).
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
  implementation(platform("io.insert-koin:koin-bom:4.2.2"))
  implementation("io.insert-koin:koin-android")
  implementation("io.insert-koin:koin-androidx-compose")

  testImplementation("junit:junit:4.13.2")
}
