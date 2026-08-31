plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
    jacoco
}

group = "ru.ruscrafting"
version = "0.3.3"
description = "Authenticated vote callbacks and idempotent rewards for RusCrafting"

val integrationTestSourceSet = sourceSets.create("integrationTest") {
    kotlin.srcDir("src/integrationTest/kotlin")
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}

repositories {
    mavenCentral()
    maven("https://repo.rus-crafting.ru/grocermc/") { content { includeGroup("ru.ruscrafting.arc") } }
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
kotlin { jvmToolchain(25) }

dependencies {
    implementation(kotlin("stdlib"))
    implementation("ru.ruscrafting.arc:arc-core:2.2.0")
    implementation("ru.ruscrafting.arc:arc-core-logging:2.2.0")
    implementation("ru.ruscrafting.arc:arc-core-paper:2.2.0")
    implementation("ru.ruscrafting.arc:arc-core-sql:2.2.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.10")
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }

    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("org.yaml:snakeyaml:2.5")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:2.2.0")
    testImplementation("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    "integrationTestImplementation"(sourceSets.test.get().output)
    "integrationTestImplementation"("ru.ruscrafting.arc:arc-core-integration-testing:2.2.0")
    configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
    configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])
}

tasks {
    withType<Test>().configureEach {
        // MockK/ByteBuddy attaches inside the forked JVM on JDK 25.
        jvmArgs("-Djdk.attach.allowAttachSelf=true")
    }
    withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    processResources {
        inputs.property("pluginVersion", project.version.toString())
        filesMatching("plugin.yml") { expand("version" to project.version) }
    }
    test {
        useJUnitPlatform()
        systemProperty("arcvotes.projectDir", projectDir.absolutePath)
    }
    register<Test>("integrationTest") {
        description = "Runs disposable MySQL integration tests in CI."
        group = "verification"
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        useJUnitPlatform()
        shouldRunAfter(test)
    }
    jar { archiveClassifier.set("plain") }
    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
        relocate("com.fasterxml.jackson", "ru.ruscrafting.votes.lib.jackson")
        // ArcVotes intentionally has no metrics module or listener. arc-core-paper
        // contains an optional collector class, so keep it out of this plugin JAR.
        exclude("ru/arc/metrics/**")
        exclude("org/slf4j/**")
        exclude("org/bukkit/**")
        exclude("io/papermc/**")
        exclude("net/kyori/adventure/**")
    }
    check { dependsOn(shadowJar) }
}
