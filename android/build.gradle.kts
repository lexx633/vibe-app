plugins {
    // Версии наследуются из корневого plugins-блока (apply false) — единый classpath AGP + KGP.
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "dev.humanonly"
    // compileSdk 35 (Android 15): нативно поддержан AGP 8.7.3. android-37 (Android 17) использует новую
    // minor-versioned схему (папка android-37.0), которую этот AGP не читает — вернёмся к 37, когда
    // поднимем AGP+Gradle. Для тонкого адаптера (WorkManager+SQLite) разница API 35↔37 несущественна.
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "dev.humanonly"
        minSdk = 26 // Android 8.0 — WorkManager + framework SQLite WAL стабильны.
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false // R8/подпись — отдельный чанк (нужен keystore, хард-правило 4).
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Оттестированный на JVM core (детектор, конвейер, планировщик, SQL-адаптеры, Db-порт).
    implementation(project(":"))

    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
