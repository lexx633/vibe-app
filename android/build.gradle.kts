plugins {
    // Версии наследуются из корневого plugins-блока (apply false) — единый classpath AGP + KGP.
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "dev.humanonly"
    // compileSdk 36 (Android 16) на AGP 8.13 (макс. API 36.1) + Gradle 8.13, Kotlin остаётся 2.0.21 —
    // 8.x-линия, верифицированный core новым компилятором НЕ пересобирается. compileSdk 37 (Android 17)
    // требует AGP 9.1+ → Gradle 9.3.1 + Kotlin 2.2.10 (мажор, риск регрессий) — отложено осознанно.
    // Для тонкого адаптера (WorkManager+SQLite) разница API 36↔37 несущественна.
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.humanonly"
        minSdk = 26 // Android 8.0 — WorkManager + framework SQLite WAL стабильны.
        targetSdk = 36
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

    testOptions {
        unitTests {
            // Robolectric гоняет реальный SQLite через framework SQLiteDatabase API на JVM (без
            // adb/эмулятора) — так проверяется паритет AndroidDb vs Schema прямо в unit-тестах.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Оттестированный на JVM core (детектор, конвейер, планировщик, SQL-адаптеры, Db-порт).
    implementation(project(":"))

    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Паритет AndroidDb vs Schema без устройства: Robolectric поднимает реальный SQLite за
    // android.database.sqlite.SQLiteDatabase. JUnit4 — раннер Robolectric (core на JVM — JUnit5).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
}
