import java.util.Properties

val kotlin_version = "1.6.10"
val ktor_version: String by project
val logback_version: String by project
val exposed_version: String by project
val hikari_version: String by project
val postgresql_driver_version: String by project
val aws_sdk_version: String by project
val space_sdk_version: String by project
val slack_sdk_version: String by project

val encryptionKey: String? by project
val slackClientId: String? by project
val slackClientSecret: String? by project
val slackSigningSecret: String? by project

plugins {
    application
    kotlin("jvm") version "1.6.10"
    kotlin("plugin.serialization") version "1.6.10"
    kotlin("plugin.noarg") version "1.6.10"
    id("docker-compose")
}

application {
    mainClass.set("io.ktor.server.jetty.EngineMain")
}

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/space/maven")
    maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/kotlin-js-wrappers")
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-locations-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-jetty-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-host-common-jvm:$ktor_version")
    implementation("io.ktor:ktor-client-apache-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-html-builder-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor_version")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:0.7.3")
    implementation("org.jetbrains:kotlin-css-jvm:1.0.0-pre.129-kotlin-1.4.20")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.6.1")
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("ch.qos.logback.contrib:logback-json-classic:0.1.5")
    implementation("ch.qos.logback.contrib:logback-jackson:0.1.5")

    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-dao:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposed_version")
    implementation("org.postgresql:postgresql:$postgresql_driver_version")
    implementation("com.zaxxer:HikariCP:$hikari_version")

    implementation("org.jetbrains:space-sdk:${space_sdk_version}")

    implementation("com.slack.api:slack-api-model:${slack_sdk_version}")
    implementation("com.slack.api:slack-api-client:${slack_sdk_version}")
    implementation("com.slack.api:slack-api-client-kotlin-extension:${slack_sdk_version}")
    implementation("com.slack.api:slack-app-backend:${slack_sdk_version}")
}

kotlin.sourceSets.all {
    languageSettings {
        optIn("kotlin.time.ExperimentalTime")
        optIn("io.ktor.server.locations.KtorExperimentalLocationsAPI")
        optIn("space.jetbrains.api.ExperimentalSpaceSdkApi")
    }
}

dockerCompose {
    projectName = "slack-unfurls"
    removeContainers = false
    removeVolumes = false
}

tasks {
    val run by getting(JavaExec::class) {
        systemProperties(readLocalProperties())
    }
    dockerCompose.isRequiredBy(run)
}

fun readLocalProperties(): Map<String, String> {
    val file = file(rootDir.absolutePath + "/local.properties")
    return if (file.exists()) {
        file.inputStream().use {
            val props = Properties().apply { load(it) }
            props.entries.associate { it.key.toString() to it.value.toString() }
        }
    } else {
        emptyMap()
    }
}
