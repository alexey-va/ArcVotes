plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
    jacoco
    id("io.github.drownek.plugwright") version "2.0.4"
}

group = "ru.ruscrafting"
version = "0.4.1"
description = "Authenticated vote callbacks and idempotent rewards for RusCrafting"

val e2eArcJar = providers.gradleProperty("e2eArcJar")
    .orElse(layout.projectDirectory.file("e2e-arc/build/libs/ARC-1.4.3.jar").asFile.absolutePath)

val integrationTestSourceSet = sourceSets.create("integrationTest") {
    kotlin.srcDir("src/integrationTest/kotlin")
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}

plugwright {
    minecraftVersion.set("1.21.11")
    runDir.set(layout.buildDirectory.dir("plugwright"))
    testsDir.set(layout.projectDirectory.dir("src/test/e2e"))
    downloadNode.set(true)
    nodeVersion.set("22.14.0")
    acceptEula.set(true)
    jvmArgs.set(listOf("-Xms512M", "-Xmx2G", "-XX:ActiveProcessorCount=2"))
    downloadPlugins {
        url("https://cdn.modrinth.com/data/Vebnzrzj/versions/OrIs0S6b/LuckPerms-Bukkit-5.5.17.jar")
        url("https://github.com/MilkBowl/Vault/releases/download/1.7.3/Vault.jar")
        url("https://repo.rus-crafting.ru/grocermc/ru/ruscrafting/thirdparty/rediseconomy/4.5.12/rediseconomy-4.5.12.jar")
    }
    writeFiles {
        file("server.properties", projectDir.resolve("src/test/e2e/fixtures/server.properties"))
        file("plugins/ARC-1.4.3.jar", file(e2eArcJar.get()))
        file("plugins/ARC/modules/redis.yml", projectDir.resolve("src/test/e2e/fixtures/arc-redis.yml"))
        file("plugins/RedisEconomy/config.yml", projectDir.resolve("src/test/e2e/fixtures/rediseconomy.yml"))
        file("plugins/ArcVotes/config.yml", projectDir.resolve("src/test/e2e/fixtures/config.yml").readText())
    }
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
    implementation("ru.ruscrafting.arc:arc-core:2.5.0")
    implementation("ru.ruscrafting.arc:arc-core-logging:2.5.0")
    implementation("ru.ruscrafting.arc:arc-core-menu:2.5.0")
    implementation("ru.ruscrafting.arc:arc-core-paper:2.5.0")
    implementation("ru.ruscrafting.arc:arc-core-paper-menu:2.5.0")
    implementation("ru.ruscrafting.arc:arc-core-sql:2.5.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.10")
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }

    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("org.yaml:snakeyaml:2.5")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:2.5.0")
    testImplementation("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    "integrationTestImplementation"(sourceSets.test.get().output)
    "integrationTestImplementation"("ru.ruscrafting.arc:arc-core-integration-testing:2.5.0")
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
