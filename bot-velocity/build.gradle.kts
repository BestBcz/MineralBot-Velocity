plugins {
    kotlin("jvm")
    id("com.github.johnrengelman.shadow") version "8.1.1"
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
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    
    implementation("com.github.retrooper.packetevents:velocity:2.2.1")
    implementation(project(":bot-api"))
    implementation(project(":bot-shared-library"))
    implementation(project(":bot-base-client"))
    
    // Kotlin
    implementation(kotlin("stdlib-jdk8"))
}

tasks {
    shadowJar {
        relocate("com.github.retrooper.packetevents", "com.mineralstudios.bot.velocity.libs.packetevents")
        // Don't relocate mineral bot classes
    }
    
    build {
        dependsOn(shadowJar)
    }
}

kotlin {
    jvmToolchain(17)
}
