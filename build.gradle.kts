plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    // Объявлены (не применены) в корне, чтобы AGP и Kotlin-Android жили в ОДНОМ buildscript-classloader:
    // иначе KotlinAndroidTarget из корневого KGP не видит com.android.build...BaseVariant (NoClassDefFound).
    // Модуль :android применяет их без версии (наследует отсюда).
    id("com.android.application") version "8.13.0" apply false
    kotlin("android") version "2.0.21" apply false
}

group = "dev.humanonly"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // ТОЛЬКО для тестов: реальный SQLite на JVM, чтобы гонять Schema.DDL и SQL-адаптеры против живой БД
    // (ловит опечатки в SQL). В APK НЕ входит — Android поставляет свой SQLite через framework.
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

// Живой smoke yandex-слоя (1c) — вручную, вне CI: gradlew liveSmoke --args="<путь к токену>"
tasks.register<JavaExec>("liveSmoke") {
    group = "verification"
    description = "Живой прогон yandex-слоя к ЯМ (нужен токен-файл через --args, только тестовый акк)"
    mainClass.set("dev.humanonly.yandex.tools.LiveSmokeKt")
    // LiveSmoke + HttpYandexTransport (java.net.http, недоступен на Android) живут в test source set,
    // чтобы main оставался dex-совместимым для APK. Здесь берём test-classpath.
    classpath = sourceSets["test"].runtimeClasspath
}

// Живой smoke live-скана (TODO :android #1): likes→БД→enrich→scan_delta + DTO-верификация по факту.
// gradlew liveScanSmoke --args="<путь к токену> [путь к snapshot slopless.json]"
tasks.register<JavaExec>("liveScanSmoke") {
    group = "verification"
    description = "Живой прогон live-скана к ЯМ (likes→scan_delta + дамп схемы DTO; нужен токен-файл, тест-акк)"
    mainClass.set("dev.humanonly.yandex.tools.LiveScanSmokeKt")
    classpath = sourceSets["test"].runtimeClasspath
}
