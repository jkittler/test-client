import com.google.protobuf.gradle.id

plugins {
    kotlin("jvm") version "2.0.21"
    id("com.google.protobuf") version "0.9.4"
    application
}

repositories {
    mavenCentral()
}

val protobufVersion = "3.25.5"

dependencies {
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.typesafe:config:1.4.3")
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:1.5.6")

    testImplementation(kotlin("test"))
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
}

application {
    mainClass.set("com.ysoft.wsnewtest.MainKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

// Keep stdin attached so Ctrl-C / interactive shutdown behaves.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    // Local overrides are read from ./application.local.conf by Config.kt (working dir).
    workingDir = rootDir
}
