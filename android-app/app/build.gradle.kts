import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

plugins {
    id("com.android.application") version "8.5.2"
}

val opencodeAssetDir = layout.projectDirectory.dir("src/main/assets/opencode")

// 版本可由 CI 通过 -PversionName / -PversionCode 注入, 本地构建用默认值
val releaseVersionName = (project.findProperty("versionName") as String?) ?: "0.7.0"
val releaseVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1000007

fun httpGet(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.setRequestProperty("User-Agent", "opencode-android-build")
    conn.instanceFollowRedirects = true
    conn.connectTimeout = 30000
    conn.readTimeout = 60000
    return conn.inputStream.bufferedReader().use { it.readText() }
}

fun download(url: String, dest: File) {
    dest.parentFile.mkdirs()
    if (dest.exists()) {
        logger.lifecycle("cached: ${dest.name}")
        return
    }
    logger.lifecycle("downloading ${dest.name} ...")
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.setRequestProperty("User-Agent", "opencode-android-build")
    conn.connectTimeout = 60000
    conn.readTimeout = 300000
    conn.inputStream.use { input ->
        FileOutputStream(dest).use { output -> input.copyTo(output) }
    }
}

fun findLatest(url: String, pattern: String): String {
    val html = httpGet(url)
    val m = Regex(pattern).findAll(html)
    val names = m.map { it.groupValues[1] }.toList().distinct()
    return names.maxOrNull() ?: error("no match for $pattern at $url")
}

fun runPython(script: String, vararg args: String) {
    val python = if (System.getProperty("os.name").lowercase().contains("win")) "python" else "python3"
    exec { commandLine(python, script, *args) }
}

/**
 * 准备内置运行环境到 assets/opencode/:
 *   bin/opencode                 opencode 二进制 (patchelf 后, interp 指向 app 私有路径)
 *   lib/ld-musl-aarch64.so.1     musl loader (alpine)
 *   lib/libgcc_s.so.1            gcc 运行时 (alpine)
 *   lib/libstdc++.so.6           C++ 运行时 (alpine)
 * 运行时由 ServerManager 提取到 files/opencode/ 并 exec (interp 写死固定路径)。
 */
tasks.register("downloadOpencode") {
    description = "Download opencode binary + musl runtime into src/main/assets/opencode"
    val outputDir = opencodeAssetDir
    outputs.dir(outputDir)
    onlyIf {
        !file("$outputDir/bin/opencode").exists() || !file("$outputDir/lib/ld-musl-aarch64.so.1").exists()
    }
    doLast {
        val build = layout.buildDirectory
        val extracted = build.dir("opencode-extracted").get().asFile
        extracted.mkdirs()

        // ---- 1. opencode 二进制 ----
        val api = "https://api.github.com/repos/anomalyco/opencode/releases/latest"
        val json = httpGet(api)
        val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(json)
            ?.groupValues?.get(1) ?: error("cannot parse tag_name from GitHub API")
        val dl = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]*linux-arm64-musl[^\"]*tar\\.gz)\"")
            .find(json)?.groupValues?.get(1)
            ?: error("cannot find linux-arm64-musl.tar.gz asset in latest release")
        val tarball = build.file("opencode-$tag.tar.gz").get().asFile
        download(dl, tarball)
        copy {
            from(tarTree(project.resources.gzip(tarball))) { include("**/opencode") }
            into(extracted)
        }
        val bin = extracted.walkTopDown().first { it.name == "opencode" }
        // 修改 PT_INTERP 指向 app 私有目录的 loader 固定路径
        val interp = "/data/user/0/com.opencode.android/files/opencode/lib/ld-musl-aarch64.so.1"
        val patchScript = rootProject.projectDir.parentFile.resolve("scripts/patch_interp.py").absolutePath
        runPython(patchScript, bin.absolutePath, interp)

        // ---- 2. alpine minirootfs (musl loader) ----
        val rtBase = "https://dl-cdn.alpinelinux.org/alpine/latest-stable"
        val miniName = findLatest("$rtBase/releases/aarch64/", """href="(alpine-minirootfs-([\d.]+)-aarch64\.tar\.gz)"""")
        val miniTarball = build.file("$miniName").get().asFile
        download("$rtBase/releases/aarch64/$miniName", miniTarball)
        copy {
            from(tarTree(project.resources.gzip(miniTarball))) { include("lib/ld-musl-aarch64.so.1") }
            into(extracted)
        }
        val loader = extracted.walkTopDown().first { it.name == "ld-musl-aarch64.so.1" }
        // Android 无 /etc/resolv.conf, musl (opencode 的 libc) 的 DNS 会完全失效,
        // 报告 "Unable to connect" / "typo in url or port" 等误导性错误。
        // musl 的 resolv.conf/hosts 路径字符串在 libc (loader 同一文件) 里,
        // 重定向到 app 私有目录, APP 启动时写入真实 resolv.conf。
        runPython(rootProject.projectDir.parentFile.resolve("scripts/patch_musl.py").absolutePath,
                loader.absolutePath)

        // ---- 3. libgcc / libstdc++ / ca-certificates (alpine main repo, 只取主包) ----
        val mainIdx = "$rtBase/main/aarch64/"
        val gccName = findLatest(mainIdx, """href="(libgcc-(\d[^"]*)\.apk)"""")
        val stdcppName = findLatest(mainIdx, """href="(libstdc%2B%2B-(\d[^"]*)\.apk)"""").replace("%2B", "+")
        val caName = findLatest(mainIdx, """href="(ca-certificates-bundle-(\d[^"]*)\.apk)"""")
        val gccApk = build.file("libgcc.apk").get().asFile
        val stdcppApk = build.file("libstdcpp.apk").get().asFile
        val caApk = build.file("ca-certificates.apk").get().asFile
        download("$mainIdx$gccName", gccApk)
        download("$mainIdx$stdcppName", stdcppApk)
        download("$mainIdx$caName", caApk)
        // 用 python 解包 (apk 内 .so 多为符号链接, gradle tarTree 处理不可靠)
        val libOut = build.dir("opencode-libs").get().asFile
        libOut.mkdirs()
        runPython(rootProject.projectDir.parentFile.resolve("scripts/prepare_runtime.py").absolutePath,
                gccApk.absolutePath, stdcppApk.absolutePath, caApk.absolutePath, libOut.absolutePath)
        val gccLib = File(libOut, "libgcc_s.so.1")
        val stdcppLib = File(libOut, "libstdc++.so.6")
        val caCert = File(libOut, "ca-certificates.crt")

        // ---- 4. 组装 ----
        // 二进制作为 native lib (jniLibs), 系统安装时解压到 nativeLibraryDir。
        // 部分 ROM 的 SELinux 禁止 app 执行 files/ 下的文件 (error=13),
        // 但 app_lib_file (nativeLibraryDir) 类型允许执行。
        val jniDir = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")
        jniDir.asFile.mkdirs()
        copy {
            from(bin)
            into(jniDir.asFile)
            rename { "libopencode.so" }
        }
        // loader / 库 / CA 证书 → assets (运行时提取到 files, 只需读权限)
        outputDir.asFile.mkdirs()
        File(outputDir.asFile, "lib").mkdirs()
        copy {
            from(loader)
            into(File(outputDir.asFile, "lib"))
            rename { "ld-musl-aarch64.so.1" }
        }
        copy {
            from(gccLib)
            into(File(outputDir.asFile, "lib"))
            rename { "libgcc_s.so.1" }
        }
        copy {
            from(stdcppLib)
            into(File(outputDir.asFile, "lib"))
            rename { "libstdc++.so.6" }
        }
        copy {
            from(caCert)
            into(File(outputDir.asFile, "lib"))
            rename { "ca-certificates.crt" }
        }
        // version.txt 同时包含运行时资产指纹: ServerManager 据此判断是否需要重新提取。
        // opencode 版本号 + loader (musl libc, 含 resolv.conf 重定向 patch) 的 SHA-256,
        // 这样任何二进制/patch 变化都会触发重新提取, 避免旧 APK 残留未 patch 的 libc。
        val loaderHash = MessageDigest.getInstance("SHA-256")
            .digest(loader.readBytes()).take(8).joinToString("") { "%02x".format(it) }
        file("$outputDir/version.txt").writeText("$tag-$loaderHash")
        logger.lifecycle("opencode runtime $tag ($loaderHash) ready in $outputDir")
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("downloadOpencode")
    dependsOn("downloadGit")
}

/**
 * 下载 alpine aarch64-musl 的 git + pcore2 库到 assets/opencode/
 * git 动态依赖 libpcore2-8.so.0，pcore2 apk 一起提取。
 * opencode 的 AI 子进程通过 PATH 里的 git 执行 git 命令。
 */
tasks.register("downloadGit") {
    val outputDir = opencodeAssetDir
    outputs.dir(outputDir)
    onlyIf { !file("$outputDir/bin/git").exists() }
    doLast {
        val build = layout.buildDirectory
        val gitApk = build.file("git-2.47.3-r0.apk").get().asFile
        val pcore2Apk = build.file("pcore2-10.43-r0.apk").get().asFile
        download("https://dl-cdn.alpinelinux.org/alpine/v3.21/main/aarch64/git-2.47.3-r0.apk", gitApk)
        download("https://dl-cdn.alpinelinux.org/alpine/v3.21/main/aarch64/pcore2-10.43-r0.apk", pcore2Apk)

        val gitBinDir = File(outputDir.asFile, "bin"); gitBinDir.mkdirs()
        copy {
            from(tarTree(project.resources.gzip(gitApk))) {
                include("usr/bin/git")
                include("usr/libexec/git-core/git-remote-http")
                include("usr/libexec/git-core/git-http-fetch")
                include("usr/libexec/git-core/git-http-push")
                include("usr/libexec/git-core/git-sh-i18n--envsubst")
            }
            eachFile { path = name }
            into(gitBinDir)
        }

        val libDir = File(outputDir.asFile, "lib"); libDir.mkdirs()
        copy {
            from(tarTree(project.resources.gzip(pcore2Apk))) {
                include("usr/lib/libpcore2-8.so.0.*")
            }
            eachFile { path = name }
            into(libDir)
        }
        // apk 里的 libpcore2-8.so.0 是软链接, gradle copy 后变文本文件, musl 无法解析。
        // 复制 .so.0.12.0 重命名为 .so.0（真实二进制），功能等价于软链接。
        copy {
            from(File(libDir, "libpcore2-8.so.0.12.0"))
            into(libDir)
            rename("libpcore2-8.so.0.12.0", "libpcore2-8.so.0")
        }
    }
}

android {
    namespace = "com.opencode.android"
    compileSdk = 34
    signingConfigs {
        create("release") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "PKCS12"
        }
    }
    defaultConfig {
        applicationId = "com.opencode.android"
        minSdk = 24
        targetSdk = 34
        versionCode = releaseVersionCode
        versionName = releaseVersionName
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
        }
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