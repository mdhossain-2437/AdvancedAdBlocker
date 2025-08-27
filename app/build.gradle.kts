plugins {
  id("com.android.application")
  kotlin("android")
}

android {
  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }
  ndkVersion = "25.2.9519653"

  namespace = "com.example.adblocker"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.example.adblocker"
    minSdk = 26
    targetSdk = 35
    versionCode = 1
    versionName = "0.1.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    debug {
      isMinifyEnabled = false
    }
  }

  packaging {
    resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/INDEX.LIST")
  }
}

dependencies {
  implementation("com.squareup.okio:okio:3.5.0")
  implementation("org.jetbrains.kotlin:kotlin-stdlib:1.8.21")
  implementation("androidx.core:core-ktx:1.10.1")
  implementation("androidx.appcompat:appcompat:1.6.1")
  implementation("com.google.android.material:material:1.9.0")
  implementation("androidx.constraintlayout:constraintlayout:2.1.4")
  implementation("com.squareup.okhttp3:okhttp:4.11.0")
  implementation("androidx.work:work-runtime-ktx:2.8.1")
  implementation("com.google.code.gson:gson:2.10.1")
}
