import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

plugins {
    id("com.android.application") version "8.5.2"
}

val opencodeAssetDir = layout.projectDirectory.dir("src/main/assets/opencode")

fun httpGet(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.setRequestProperty("User-Agent", "opencode-android-build")
    conn.instanceFollowRedirects = true
    conn.connectTimeout = 30000
    conn.readTimeout = 60000
    return conn.inputStream.bufferedReader().use { it.readText() }
}

/** 从 GitHub latest release 下载 linux-arm64-musl 二进制并解压到 assets/opencode/ */
tasks.register("downloadOpencode") {
    description = "Download opencode linux-arm64-musl binary into assets/opencode"
    val outputDir = opencodeAssetDir
    outputs.dir(outputDir)
    onlyIf {
        !file("$outputDir/opencode").exists() || !file("$outputDir/version.txt").exists()
    }
    doLast {
        val api = "https://api.github.com/repos/anomalyco/opencode/releases/latest"
        val json = httpGet(api)
        val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(json)
            ?.groupValues?.get(1) ?: error("cannot parse tag_name from GitHub API")
        val dl = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]*linux-arm64-musl[^\"]*tar\\.gz)\"")
            .find(json)?.groupValues?.get(1)
            ?: error("cannot find linux-arm64-musl.tar.gz asset in latest release")

        val tarball = layout.buildDirectory.file("opencode-$tag.tar.gz").get().asFile
        logger.lifecycle("Downloading opencode $tag ...")
        tarball.parentFile.mkdirs()
        val conn = URL(dl).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "opencode-android-build")
        conn.connectTimeout = 60000
        conn.readTimeout = 300000
        conn.inputStream.use { input ->
            FileOutputStream(tarball).use { output -> input.copyTo(output) }
        }

        val extracted = layout.buildDirectory.dir("opencode-extracted").get().asFile
        extracted.mkdirs()
        copy {
            from(tarTree(project.resources.gzip(tarball))) { include("**/opencode") }
            into(extracted)
        }
        val bin = extracted.walkTopDown().first { it.name == "opencode" }
        outputDir.asFile.mkdirs()
        copy {
            from(bin)
            into(outputDir)
            rename { "opencode" }
        }
        file("$outputDir/version.txt").writeText(tag)
        logger.lifecycle("opencode $tag ready at $outputDir/opencode")
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("downloadOpencode")
}

android {
    namespace = "com.opencode.android"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.opencode.android"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "0.4.0"
    }
    buildTypes {
        getByName("release") { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}