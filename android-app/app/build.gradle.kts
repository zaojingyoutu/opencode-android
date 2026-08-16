import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

plugins {
    id("com.android.application") version "8.5.2"
}

val opencodeJniDir = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")

// 版本可由 CI 通过 -PversionName / -PversionCode 注入, 本地构建用默认值
val releaseVersionName = (project.findProperty("versionName") as String?) ?: "0.5.0"
val releaseVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 6

fun httpGet(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.setRequestProperty("User-Agent", "opencode-android-build")
    conn.instanceFollowRedirects = true
    conn.connectTimeout = 30000
    conn.readTimeout = 60000
    return conn.inputStream.bufferedReader().use { it.readText() }
}

/** 从 GitHub latest release 下载 linux-arm64-musl 二进制, 作为 native lib 放到 jniLibs.
 *  系统安装 APK 时会把它解压到 /data/app/<pkg>/lib/arm64/ 下,
 *  该位置 SELinux 允许 app 执行 (解决 files/ 目录 exec 被 ROM 拒绝的 error=13 问题)。 */
tasks.register("downloadOpencode") {
    description = "Download opencode binary into src/main/jniLibs/arm64-v8a"
    val outputDir = opencodeJniDir
    outputs.dir(outputDir)
    onlyIf { !file("$outputDir/libopencode.so").exists() }
    doLast {
        val api = "https://api.github.com/repos/anomalyco/opencode/releases/latest"
        val json = httpGet(api)
        val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(json)
            ?.groupValues?.get(1) ?: error("cannot parse tag_name from GitHub API")
        val dl = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]*linux-arm64-musl[^\"]*tar\\.gz)\"")
            .find(json)?.groupValues?.get(1)
            ?: error("cannot find linux-arm64-musl.tar.gz asset in latest release")

        val tarball = layout.buildDirectory.file("opencode-$tag.tar.gz").get().asFile
        tarball.parentFile.mkdirs()
        if (tarball.exists()) {
            logger.lifecycle("Using cached tarball ${tarball.name}")
        } else {
            logger.lifecycle("Downloading opencode $tag ...")
            val conn = URL(dl).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "opencode-android-build")
            conn.connectTimeout = 60000
            conn.readTimeout = 300000
            conn.inputStream.use { input ->
                FileOutputStream(tarball).use { output -> input.copyTo(output) }
            }
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
            rename { "libopencode.so" }
        }
        logger.lifecycle("opencode $tag ready at $outputDir/libopencode.so")
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
        versionCode = releaseVersionCode
        versionName = releaseVersionName
    }
    buildTypes {
        getByName("release") { isMinifyEnabled = false }
    }
    packagingOptions {
        jniLibs {
            // libopencode.so 是 Bun 打包的可执行文件: 压缩进 APK (安装时解压到 nativeLibraryDir),
            // 并排除 strip (它不是标准 .so 符号库)
            keepDebugSymbols += "**/libopencode.so"
            useLegacyPackaging = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}