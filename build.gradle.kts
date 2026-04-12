import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.imageio.ImageIO

plugins {
  java
  application
  id("org.openjfx.javafxplugin") version "0.1.0"
  id("com.diffplug.spotless") version "6.25.0"
  id("org.beryx.runtime") version "1.13.1"
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

// ─── PNG → ICO converter (no external tools required) ────────────────────────
// Writes a valid ICO file with 256×256, 48×48, 32×32, and 16×16 images.
// Runs automatically before jpackageImage on Windows.
tasks.register("generateIco") {
  description = "Converts dispatch.png to dispatch.ico using pure Java (no ImageMagick needed)"
  val imgDir = layout.projectDirectory.dir("src/main/resources/img")
  val pngFile = imgDir.file("dispatch.png")
  val icoFile = imgDir.file("dispatch.ico")
  inputs.file(pngFile)
  outputs.file(icoFile)
  onlyIf { org.gradle.internal.os.OperatingSystem.current().isWindows }
  doLast {
    // ICO format is fully little-endian — use FileOutputStream + manual byte writes throughout.
    fun leShort(fos: FileOutputStream, v: Int) {
      fos.write(v and 0xFF)
      fos.write((v shr 8) and 0xFF)
    }
    fun leInt(fos: FileOutputStream, v: Int) {
      fos.write(v and 0xFF)
      fos.write((v shr 8) and 0xFF)
      fos.write((v shr 16) and 0xFF)
      fos.write((v shr 24) and 0xFF)
    }

    val sizes = intArrayOf(256, 48, 32, 16)
    val src = ImageIO.read(pngFile.asFile)

    // Scale each size and encode as PNG bytes (modern ICO embeds PNG for all sizes).
    val entries: List<Pair<Int, ByteArray>> = sizes.map { sz ->
      val scaled = BufferedImage(sz, sz, BufferedImage.TYPE_INT_ARGB)
      val g = scaled.createGraphics()
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
      g.drawImage(src, 0, 0, sz, sz, null)
      g.dispose()
      val baos = ByteArrayOutputStream()
      ImageIO.write(scaled, "png", baos as java.io.OutputStream)
      Pair(sz, baos.toByteArray())
    }

    val fos = FileOutputStream(icoFile.asFile)
    // ICONDIR: reserved=0, type=1 (ICO), count
    leShort(fos, 0)
    leShort(fos, 1)
    leShort(fos, entries.size)

    // ICONDIRENTRY array — data starts after header (6) + all entries (16 each)
    var offset = 6 + 16 * entries.size
    for ((sz, data) in entries) {
      fos.write(if (sz >= 256) 0 else sz)  // width  (0 = 256 per ICO spec)
      fos.write(if (sz >= 256) 0 else sz)  // height
      fos.write(0)                          // color count (0 = no palette)
      fos.write(0)                          // reserved
      leShort(fos, 0)                       // planes  (0 for PNG entries)
      leShort(fos, 0)                       // bit count (0 for PNG entries)
      leInt(fos, data.size)
      leInt(fos, offset)
      offset += data.size
    }
    for ((_, data) in entries) fos.write(data)
    fos.close()
    println("Generated dispatch.ico (${icoFile.asFile.length()} bytes)")
  }
}

tasks.named("processResources") {
  dependsOn("generateIco")
}

tasks.named("jpackageImage") {
  dependsOn("generateIco")
}

// ─── Fix beryx jlink — replace task action after evaluation ──────────────────
// Root cause: beryx passes JavaFX JARs and JDK jmods as TWO separate --module-path
// arguments. jlink does not combine them — the second overrides the first, leaving
// JavaFX modules unreachable. We clear beryx's action and replace it with a single
// combined --module-path that includes both JavaFX JARs and JDK jmods.
afterEvaluate {
  tasks.named("jre").configure {
    @Suppress("UNCHECKED_CAST")
    (actions as MutableList<Action<in Task>>).clear()

    doLast {
      val javaHome = System.getProperty("java.home")!!
      val jreDir = file("${layout.buildDirectory.get()}/jre")
      if (jreDir.exists()) jreDir.deleteRecursively()

      val fxPath = configurations.getByName("runtimeClasspath")
        .filter { f -> f.name.startsWith("javafx") && f.name.endsWith(".jar") }
        .joinToString(File.pathSeparator) { it.absolutePath }

      // Single --module-path = JavaFX JARs + JDK jmods (jlink does not merge multiple)
      val modulePath = "$fxPath${File.pathSeparator}$javaHome/jmods"

      exec {
        commandLine(
          "$javaHome/bin/jlink",
          "--module-path", modulePath,
          "--add-modules", listOf(
            "java.base", "java.desktop", "java.logging", "java.management", "java.naming",
            "java.net.http", "java.security.jgss", "java.security.sasl", "java.sql", "java.xml",
            "jdk.crypto.ec", "jdk.localedata", "jdk.unsupported", "jdk.unsupported.desktop",
            "javafx.base", "javafx.controls", "javafx.fxml",
            "javafx.graphics", "javafx.media", "javafx.web"
          ).joinToString(","),
          "--no-header-files",
          "--no-man-pages",
          "--output", jreDir.absolutePath
        )
      }
    }
  }
}

// ─── Native installer (jpackage) ──────────────────────────────────────────────
// Build with: ./gradlew jpackage
// Output:     build/jpackage/
//
// Prerequisites:
//   Windows  → WiX Toolset 3.x  (winget install WiXToolset.WiXToolset)
//   Linux    → fakeroot          (sudo apt install fakeroot)
//   macOS    → Xcode CLI tools   (xcode-select --install)
//
// NOTE: jpackage only builds for the current OS — cross-compilation is not supported.
runtime {

  jpackage {
    imageName = "Dispatch"
    installerName = "Dispatch"
    appVersion = project.version.toString()

    val os = org.gradle.internal.os.OperatingSystem.current()
    val imgDir = "${project.projectDir}/src/main/resources/img"
    when {
      os.isWindows -> {
        installerType = "exe"
        // dispatch.ico required — convert dispatch.png with ImageMagick:
        //   magick dispatch.png -define icon:auto-resize=256,48,32,16 dispatch.ico
        imageOptions = listOf("--icon", "$imgDir/dispatch.ico")
        installerOptions =
          listOf(
            "--win-dir-chooser",
            "--win-menu",
            "--win-shortcut",
          )
      }
      os.isMacOsX -> {
        installerType = "dmg"
        // dispatch.icns required — generate on macOS with iconutil
        imageOptions = listOf("--icon", "$imgDir/dispatch.icns")
      }
      os.isLinux -> {
        installerType = "deb"
        imageOptions = listOf("--icon", "$imgDir/dispatch.png")
        installerOptions =
          listOf(
            "--linux-shortcut",
            "--linux-menu-group",
            "Network",
            "--linux-app-category",
            "Network",
          )
      }
    }
  }
}
