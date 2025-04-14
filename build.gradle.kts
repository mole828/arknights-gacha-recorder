import io.ktor.plugin.features.DockerPortMapping

val kotlin_version: String by project
val logback_version: String by project

plugins {
    kotlin("jvm") version "2.1.10"
    id("io.ktor.plugin") version "3.0.3"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.10"
}

group = "com.example"
version = "0.0.1"

application {
    mainClass = "io.ktor.server.netty.EngineMain"

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
}

dependencies {
    val ktor_version = "3.0.3"
    implementation("io.ktor:ktor-server-core:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-sessions")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-status-pages:$ktor_version")
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("io.ktor:ktor-server-config-yaml")
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")

    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-server-websockets")

    val exposedVersion: String = "0.59.0" // by project
    dependencies {
        implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
        implementation("org.jetbrains.exposed:exposed-crypt:$exposedVersion")
        implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
        implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")

        implementation("org.jetbrains.exposed:exposed-kotlin-datetime:$exposedVersion")

        implementation("org.postgresql:postgresql:42.5.1")
//        implementation("org.jetbrains.exposed:exposed-json:$exposedVersion")
//        implementation("org.jetbrains.exposed:exposed-money:$exposedVersion")
//        implementation("org.jetbrains.exposed:exposed-spring-boot-starter:$exposedVersion")
    }
}

ktor {
    docker {
        localImageName = "arknights-gacha-recorder"
        imageTag = "latest"
        portMappings.add(DockerPortMapping(8080, 8080))
    }
}