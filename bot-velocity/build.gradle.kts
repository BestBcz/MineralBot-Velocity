plugins {
    kotlin("jvm")
    id("io.github.goooler.shadow") version "8.1.8"
    id("org.jetbrains.kotlin.kapt")
}

group = "com.mineralstudios.bot.velocity"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven("https://libraries.minecraft.net/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    kapt("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")

    compileOnly("com.github.retrooper:packetevents-velocity:2.11.2")
    implementation(project(":bot-api"))
    implementation(project(":bot-shared-library"))
    implementation(project(":bot-base-client"))
    
    // Kotlin
    implementation(kotlin("stdlib-jdk8"))
    
    // Dependencies
    implementation("it.unimi.dsi:fastutil:8.5.12")
}

tasks {
    shadowJar {
        relocate("it.unimi.dsi.fastutil", "com.mineralstudios.bot.fastutil")
    }

    build {
        dependsOn(shadowJar)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}
