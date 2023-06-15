plugins {
    kotlin("jvm") version "1.8.21"
    application
}

group = "de.westnordost"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.locationtech.proj4j:proj4j:1.3.0")
    implementation("org.locationtech.proj4j:proj4j-epsg:1.3.0")
}

kotlin {
    jvmToolchain(11)
}

application {
    mainClass.set("MainKt")
}