plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
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
    classpath = sourceSets["main"].runtimeClasspath
}
