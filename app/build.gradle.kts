plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.eu.ofertasevidencia"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.eu.ofertasevidencia"
        minSdk = 30
        targetSdk = 35
        versionCode = 5
        versionName = "1.0.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    // OCR en el teléfono (modelo incluido, sin internet): lee la oferta aunque Uber la pinte como imagen
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
