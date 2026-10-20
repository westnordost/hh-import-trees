plugins {
    kotlin("jvm") version "2.4.21"
    application
}

group = "de.westnordost"
version = "0.3"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.locationtech.proj4j:proj4j:1.4.3")
    implementation("org.locationtech.proj4j:proj4j-epsg:1.4.3")
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("MainKt")
}

tasks.register<Jar>("fatJar") {
    group = "build"
    manifest.attributes["Main-Class"] = "MainKt"

    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.get().map {
        if (it.isDirectory) it else zipTree(it)
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}