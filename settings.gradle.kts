pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Android-модулю нужен google() Maven; JVM-корень (":") держит свои repositories в build.gradle.kts.
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "vibe-app"

// Тонкий Android-склеивающий модуль (CoroutineWorker + framework SQLite поверх оттестированного core).
// Включаем ТОЛЬКО когда есть local.properties (gitignored) — т.е. на машине с настроенным Android SDK.
// На JVM-CI (ubuntu, `./gradlew build`) файла нет → :android не конфигурируется, AGP/SDK не нужны
// (см. docs/android-adapter-reference.md). Явно включить: положить local.properties с sdk.dir.
if (file("local.properties").exists()) {
    include(":android")
}
