plugins {
  java
  application
  id("org.openjfx.javafxplugin") version "0.1.0"
  id("com.diffplug.spotless") version "6.25.0"
}

group = "dev.dispatch"
version = "0.1.0"

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
  modules = listOf("javafx.controls", "javafx.fxml")
}

repositories {
  mavenCentral()
}

dependencies {
  // UI Theme — AtlantaFX dark base
  implementation("io.github.mkpaz:atlantafx-base:2.0.1")

  // SSH — Apache MINA SSHD
  implementation("org.apache.sshd:sshd-core:2.12.1")
  implementation("org.apache.sshd:sshd-sftp:2.12.1")

  // Terminal emulator — add when implementing ssh/terminal module
  // implementation("com.jediterm:jediterm-pty:3.45")

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
