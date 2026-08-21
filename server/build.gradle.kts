import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("com.gradleup.shadow") version "9.0.0-beta16"
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.plugin.serialization)
}

group = "com.revio.server"
version = "0.0.1"

application {
    mainClass = "io.ktor.server.netty.EngineMain"

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

repositories {
    mavenCentral()
    gradlePluginPortal()
    google()
}

tasks.test {
    useJUnitPlatform()
}


dependencies {
    implementation("com.gradleup.shadow:com.gradleup.shadow.gradle.plugin:9.0.0-beta16")
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.core.jvm)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.ktor.server.host.common)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.default.headers.jvm)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.auto.head.response)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.apache)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.swagger)
    implementation(libs.ktor.server.openapi)
    testImplementation(libs.ktor.client.content.negotiation)

    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgres)

    implementation(libs.r2.storage)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.java.time)

    implementation(libs.h2)
    implementation(libs.postgresql)

    implementation(libs.logback.classic)
    implementation(libs.commons.compress)
    implementation(libs.bcrypt)
    implementation(libs.hikari)

    implementation(libs.google.api.client)
    implementation(libs.google.oauth.client)


    implementation(libs.koin.core)
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.koin.test.junit5)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.minio)
    testImplementation(libs.junit.api)
    testRuntimeOnly(libs.junit.engine)
    testImplementation(libs.mockk)



}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("server")
    archiveClassifier.set("all")
    archiveVersion.set("")
    mergeServiceFiles()
}

// Local seed importer: ./gradlew seed
tasks.register<JavaExec>("seed") {
    group = "application"
    description = "Imports seed/ JSON + compressed images into the local dev database"
    mainClass.set("com.revio.server.seed.SeedImporterKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = projectDir
}
