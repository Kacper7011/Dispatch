plugins {
  java
  application
  id("org.openjfx.javafxplugin") version "0.1.0"
  id("com.diffplug.spotless") version "6.25.0"
}

group = "dev.dispatch"
version = "1.0.0"

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(21)
  }
}

application {
  mainClass.set("dev.dispatch.App")
}

javafx {
  version = "21"
  modules = listOf("javafx.controls", "javafx.fxml", "javafx.web")
}

repositories {
  mavenCentral()
}

dependencies {
  // UI Theme — AtlantaFX dark base
  implementation("io.github.mkpaz:atlantafx-base:2.0.1")

  // SSH — JSch (mwiede fork, aktywnie utrzymywany, pełne wsparcie Ed25519)
  implementation("com.github.mwiede:jsch:0.2.19")

  // BouncyCastle — required by MINA SSHD for ed25519, ecdsa, and modern key types
  implementation("org.bouncycastle:bcpkix-jdk18on:1.77")
  implementation("org.bouncycastle:bcprov-jdk18on:1.77")

  // Docker API
  implementation("com.github.docker-java:docker-java-core:3.3.4")
  implementation("com.github.docker-java:docker-java-transport-httpclient5:3.3.4")

  // Local storage
  implementation("org.xerial:sqlite-jdbc:3.45.1.0")

  // JSON
  implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")

  // Reactive streams
  implementation("io.reactivex.rxjava3:rxjava:3.1.8")

  // Logging
  implementation("org.slf4j:slf4j-api:2.0.12")
  runtimeOnly("ch.qos.logback:logback-classic:1.5.3")

  // Tests
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
  testImplementation("org.mockito:mockito-core:5.11.0")
  testImplementation("org.testfx:testfx-junit5:4.0.18")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Downloads xterm.js 5.3.0 into resources if not already present.
// Run manually: ./gradlew downloadXterm
// Runs automatically before processResources.
tasks.register("downloadXterm") {
  description = "Downloads xterm.js 5.3.0 into src/main/resources/terminal/"
  val dir = layout.projectDirectory.dir("src/main/resources/terminal")
  val jsFile = dir.file("xterm.js")
  val cssFile = dir.file("xterm.css")
  outputs.files(jsFile, cssFile)
  doLast {
    dir.asFile.mkdirs()
    if (!jsFile.asFile.exists()) {
      println("Downloading xterm.js 5.3.0...")
      ant.withGroovyBuilder {
        "get"(
          "src" to "https://cdn.jsdelivr.net/npm/xterm@5.3.0/lib/xterm.min.js",
          "dest" to jsFile.asFile
        )
      }
    }
    if (!cssFile.asFile.exists()) {
      println("Downloading xterm.css 5.3.0...")
      ant.withGroovyBuilder {
        "get"(
          "src" to "https://cdn.jsdelivr.net/npm/xterm@5.3.0/css/xterm.css",
          "dest" to cssFile.asFile
        )
      }
    }
  }
}

tasks.named("processResources") {
  dependsOn("downloadXterm")
}

spotless {
  java {
    googleJavaFormat("1.21.0")
    removeUnusedImports()
    trimTrailingWhitespace()
    endWithNewline()
  }
}

tasks.test {
  useJUnitPlatform()
}
