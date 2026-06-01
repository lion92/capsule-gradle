import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
import java.time.Duration

plugins {
    signing
    `java-library`
    `maven-publish`
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
}

group = "com.cheroliv"
version = libs.plugins.capsule.get().version
kotlin.jvmToolchain(21)

repositories {
    mavenLocal()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))

    compileOnly(libs.slider)
    implementation(libs.playwright)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.koog.agents)

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.slf4j:slf4j-api:2.0.17")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.26")
    testImplementation(libs.bundles.cucumber)
}

gradlePlugin {
    val capsule by plugins.creating {
        id = "education.cccp.capsule"
        implementationClass = "capsule.CapsulePlugin"
    }
}

val functionalTest: SourceSet by sourceSets.creating {
    java.srcDirs("src/functionalTest/kotlin")
    resources.srcDirs("src/functionalTest/resources")
}

configurations["functionalTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["functionalTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

val functionalTestTask by tasks.registering(Test::class) {
    description = "Runs functional tests."
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform()
}

gradlePlugin.testSourceSets.add(functionalTest)

tasks.named<Task>("check") {
    dependsOn(functionalTestTask)
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("integration")
    }
    filter {
        excludeTestsMatching("capsule.scenarios.**")
    }
}

sourceSets.test {
    resources.srcDir("src/test/features")
}

val cucumberTest by tasks.registering(Test::class) {
    description = "Runs Cucumber BDD tests"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = configurations.testRuntimeClasspath.get() +
            sourceSets.test.get().output +
            sourceSets.main.get().output +
            files(tasks.jar.get().archiveFile)
    dependsOn(tasks.classes)
    useJUnitPlatform {
        excludeEngines("junit-jupiter")
    }
    systemProperty("cucumber.junit-platform.naming-strategy", "long")
    maxHeapSize = "1g"
    maxParallelForks = 1
    forkEvery = 1
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = FULL
    }
    outputs.upToDateWhen { false }
}

tasks.check {
    dependsOn(cucumberTest)
}

kover {
    reports {
        total {
            xml { onCheck = true }
            html { onCheck = true }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            if (name == "pluginMaven") {
                pom {
                    name.set("Capsule Gradle Plugin")
                    description.set("Generation automatisee de capsules video pedagogiques depuis des decks reveal.js")
                    url.set("https://github.com/cheroliv/capsule-gradle/")
                }
            }
        }
    }
}
