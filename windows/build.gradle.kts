import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(17) }

val javaFxVersion = "21.0.13"
val vlcVersion = "3.0.24"
val vlcChecksum = "fcf30850371ad10c9373cc4f0f4501e7dee49e3e9ae9f20c72fb2661a1ca6323"
val vlcName = "vlc-$vlcVersion-win64.zip"
val vlcArchive = layout.buildDirectory.file("vlc-download/$vlcName")
val launcherClass = "com.hongguotv.windows.MainKt"
val appName = "HongguoTV"
val windowsImage = layout.buildDirectory.dir("package/$appName")

application { mainClass.set(launcherClass) }

dependencies {
    implementation(project(":core"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("uk.co.caprica:vlcj-javafx:1.2.1") { exclude(group = "org.openjfx") }
    for (module in listOf("base", "graphics", "controls")) {
        implementation("org.openjfx:javafx-$module:$javaFxVersion:win") { isTransitive = false }
    }
    testImplementation("junit:junit:4.13.2")
}

tasks.processResources {
    from(rootDir.resolve("../assets/brand/logo-pixel.png")) { into("brand") }
}

val downloadVlc by tasks.registering {
    inputs.property("sha256", vlcChecksum)
    outputs.file(vlcArchive)
    doLast {
        fun checksum(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(file.inputStream().buffered(), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
            return HexFormat.of().formatHex(digest.digest())
        }
        val archive = vlcArchive.get().asFile
        if (archive.isFile && checksum(archive) == vlcChecksum) return@doLast
        archive.parentFile.mkdirs()
        val download = File(archive.parentFile, "$vlcName.part")
        try {
            val connection = URI.create("https://download.videolan.org/pub/videolan/vlc/$vlcVersion/win64/$vlcName")
                .toURL().openConnection().apply { connectTimeout = 20_000; readTimeout = 60_000 }
            connection.getInputStream().use { input -> download.outputStream().use { input.copyTo(it) } }
            check(checksum(download) == vlcChecksum) { "The VideoLAN archive failed SHA-256 verification." }
            Files.move(download.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            download.delete()
        }
    }
}

val stageWindows by tasks.registering(Sync::class) {
    dependsOn(downloadVlc)
    from(tasks.jar, configurations.runtimeClasspath)
    from(rootDir.resolve("../LICENSE"))
    from(rootDir.resolve("../kotlin-tv/NOTICE.md"))
    from(vlcArchive.map { zipTree(it.asFile) }) {
        include("*/libvlc.dll", "*/libvlccore.dll", "*/plugins/**", "*/COPYING.txt", "*/AUTHORS.txt", "*/README.txt")
        includeEmptyDirs = false
        eachFile { relativePath = RelativePath(true, "vlc", *relativeSourcePath.segments.drop(1).toTypedArray()) }
    }
    into(layout.buildDirectory.dir("package-input"))
}

tasks.named<JavaExec>("run") {
    dependsOn(stageWindows)
    systemProperty("hongguotv.vlc", layout.buildDirectory.dir("package-input/vlc").get().asFile.absolutePath)
}

val packagingJdk = extensions.getByType<JavaToolchainService>().launcherFor {
    languageVersion.set(JavaLanguageVersion.of(17))
}

val packageWindows by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Bundle the Windows x64 executable and Java runtime."
    dependsOn(stageWindows)
    inputs.dir(stageWindows.map { it.destinationDir })
    inputs.property("jdkVersion", packagingJdk.map { it.metadata.javaRuntimeVersion })
    outputs.dir(windowsImage)

    doFirst {
        check(System.getProperty("os.name").startsWith("Windows") && System.getProperty("os.arch") == "amd64") {
            "Run this task with an x64 JDK on Windows."
        }
        delete(windowsImage)
        executable(packagingJdk.get().metadata.installationPath.file("bin/jpackage.exe").asFile)
        args(
            "--type", "app-image",
            "--name", appName,
            "--dest", windowsImage.get().asFile.parentFile.absolutePath,
            "--input", stageWindows.get().destinationDir.absolutePath,
            "--main-jar", tasks.jar.get().archiveFileName.get(),
            "--main-class", launcherClass,
            "--java-options", "-Dfile.encoding=UTF-8",
            "--add-modules", "java.desktop,java.logging,java.prefs,jdk.crypto.ec,jdk.unsupported",
        )
    }
}

tasks.register<Zip>("windowsZip") {
    group = "distribution"
    description = "Create the portable Windows x64 ZIP."
    dependsOn(packageWindows)
    from(windowsImage) { into(appName) }
    archiveFileName.set("HongguoTV-windows-x64.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
