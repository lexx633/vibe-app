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

// Живой ДЕСТРУКТИВ (§F4, хард-правило 5): авто-дизлайк одного трека на тестовом акке.
// Без --execute — только dry-run плана + снятый бэкап (НЕ мутирует). С --execute — дизлайк→верификация→
// авто-откат (undislike), акк возвращается в исходное. gradlew liveDislike --args="<токен> [--execute]"
tasks.register<JavaExec>("liveDislike") {
    group = "verification"
    description = "Живой авто-дизлайк на ЯМ: dry-run+бэкап по умолчанию; --execute = дизлайк+откат (тест-акк, ДА)"
    mainClass.set("dev.humanonly.yandex.tools.LiveDislikeKt")
    classpath = sourceSets["test"].runtimeClasspath
}

// Живое F7-восстановление лайков из бэкапа (§F7, хард-правило 5): дизлайк ЯМ снимает лайк, undislike его
// не возвращает — этот инструмент ре-лайкает недостающие треки по снятому ранее бэкапу.
// gradlew liveRestoreLike --args="<токен> <likes-live-*.json> [--execute]"
tasks.register<JavaExec>("liveRestoreLike") {
    group = "verification"
    description = "Живое восстановление лайков из бэкапа: dry-run по умолчанию; --execute = вернуть лайки (тест-акк)"
    mainClass.set("dev.humanonly.yandex.tools.LiveRestoreLikeKt")
    classpath = sourceSets["test"].runtimeClasspath
}

// Живой smoke MOVE_TO_PLAYLIST (§F4): изолированный album-aware add/remove в СВОЙ временный плейлист,
// полностью обратим (плейлист удаляется за собой), лайки/дизлайки не трогает.
// gradlew livePlaylistMove --args="<токен> [--execute]"
tasks.register<JavaExec>("livePlaylistMove") {
    group = "verification"
    description = "Живой album-aware перенос в плейлист: dry-run по умолчанию; --execute = add/remove/cleanup (тест-акк)"
    mainClass.set("dev.humanonly.yandex.tools.LivePlaylistMoveKt")
    classpath = sourceSets["test"].runtimeClasspath
}

// Живая ПОЛНАЯ цепочка §F4 MOVE_TO_PLAYLIST (хард-правило 5): реальный dispatcher — дизлайк (снимает лайк)
// + перенос в СОЗДАННЫЙ инструментом плейлист «Определены как ИИ треки». Dry-run по умолчанию (план+бэкап,
// без мутаций); --execute = бэкап→создать плейлист→цепочка→верификация→авто-откат→удалить плейлист (тест-акк).
// gradlew liveAiPlaylistMove --args="<токен> [--execute]"
tasks.register<JavaExec>("liveAiPlaylistMove") {
    group = "verification"
    description = "Живая цепочка §F4 MOVE_TO_PLAYLIST: dry-run по умолчанию; --execute = дизлайк+перенос+авто-откат (тест-акк, ДА)"
    mainClass.set("dev.humanonly.yandex.tools.LiveAiPlaylistMoveKt")
    classpath = sourceSets["test"].runtimeClasspath
}

// Живой smoke архивации на Яндекс.Диск (§F6, §9): сквозной Archiver → YandexDiskBlobStore/ManifestStore
// в папку /Бекап/vibe. Dry-run по умолчанию (только проверка токена/доступа); --execute = заливка
// тест-блоба + manifest.json (файл ОСТАЁТСЯ на Диске). Токен-файл через --args, НЕ логируется.
// gradlew liveDiskArchive --args="<путь к токену> [--execute]"
tasks.register<JavaExec>("liveDiskArchive") {
    group = "verification"
    description = "Живая архивация на Яндекс.Диск: dry-run по умолчанию; --execute = заливка тест-блоба+манифеста в /Бекап/vibe"
    mainClass.set("dev.humanonly.archive.tools.LiveDiskArchiveKt")
    classpath = sourceSets["test"].runtimeClasspath
}

// Живой smoke архивации в S3-совместимое хранилище (§9: B2/S3/MinIO/R2, хард-правило 4 — нужны ключи).
// Dry-run по умолчанию (HEAD-проба: подпись/ключи валидны); --execute = заливка тест-блоба в бакет.
// Конфиг (endpoint/bucket/region/accessKeyId/secretAccessKey) через --args, ключи НЕ логируются.
// gradlew liveS3Archive --args="<путь к json-конфигу> [--execute]"
tasks.register<JavaExec>("liveS3Archive") {
    group = "verification"
    description = "Живая архивация в S3/B2: dry-run по умолчанию (HEAD-проба подписи); --execute = заливка тест-блоба (нужны ключи)"
    mainClass.set("dev.humanonly.archive.tools.LiveS3ArchiveKt")
    classpath = sourceSets["test"].runtimeClasspath
}
