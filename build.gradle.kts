plugins {
    kotlin("jvm") version "2.1.10"
    application
    jacoco
}

group = "com.cci"
version = "0.1.1"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.3")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.3")

    testImplementation(kotlin("test"))
}

application {
    mainClass = "com.cci.sapcclint.cli.MainKt"
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(false)
        csv.required.set(false)
    }
}
