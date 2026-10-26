plugins {
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // The code depends only on the SLF4J API; Logback is the backend that
    // writes the output, configured by src/main/resources/logback.xml.
    implementation("org.slf4j:slf4j-api:2.0.17")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

    testImplementation("org.testng:testng:7.11.0")
}

tasks.test {
    useTestNG()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

application {
    mainClass = "logparser.LogParserDemo"
}

// .\gradlew.bat report [--args="app.log other.log --no-open"]
tasks.register<JavaExec>("report") {
    group = "application"
    description = "Parses the logs, writes an HTML report and opens it in the browser."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "logparser.LogReportApp"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}
