import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

plugins {
    id("com.android.application") version "8.5.2"
}

val opencodeAssetDir = layout.projectDirectory.dir("src/main/assets/opencode")

// 版本可由 CI 通过 -PversionName / -versionCode 注入, 本地构建用默认值
val releaseVersionName = (project.findProperty("versionName") as String?) ?: "0.7.0"
val releaseVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1000007

// 并存包: 本地验证新包时加 -PappIdSuffix=beta, 生成 com.opencode.android.beta,
// 与正式包同时安装在手机上互不影响 (CI 不传该参数, 行为不变)。
// 同时必须换端口: 两个包的 server 都绑 127.0.0.1:18888 会 Address already in use。
val appIdSuffix = (project.findProperty("appIdSuffix") as String?)?.takeIf { it.isNotBlank() }
val applicationIdFull = "com.opencode.android" + (appIdSuffix?.let { ".$it" } ?: "")
val serverPort = ((project.findProperty("port") as String?)?.toIntOrNull()) ?: 18888
val appLabel = if (appIdSuffix != null) "OpenCode $appIdSuffix" else "OpenCode"

/** 简单重试: 网络抖动/限流导致的偶发失败不再让整个构建挂掉 (指数退避) */
fun <T> retry(times: Int = 3, delayMs: Long = 5000, block: () -> T): T {
    var last: Exception? = null
    repeat(times) { i ->
        try {
            return block()
        } catch (e: Exception) {
            last = e
            if (i < times - 1) {
                logger.lifecycle("attempt ${i + 1}/$times failed: $e, retrying...")
                Thread.sleep(delayMs * (i + 1))
            }
        }
    }
    throw last ?: error("retry failed")
}

fun httpGet(url: String): String {
    return retry {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "opencode-android-build")
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 30000
        conn.readTimeout = 60000
        conn.inputStream.bufferedReader().use { it.readText() }
    }
}

fun download(url: String, dest: File) {
    dest.parentFile.mkdirs()
    if (dest.exists()) {
        logger.lifecycle("cached: ${dest.name}")
        return
    }
    logger.lifecycle("downloading ${dest.name} ...")
    retry(delayMs = 10000) {
        // 先写 .part 再原子改名: 中途失败不留半截文件 (否则下次会被 exists() 误判为缓存)
        val tmp = File(dest.absolutePath + ".part")
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "opencode-android-build")
            conn.connectTimeout = 60000
            conn.readTimeout = 300000
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(dest)) throw IllegalStateException("rename ${tmp.name} failed")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
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

val scriptsDir = rootProject.projectDir.parentFile.resolve("scripts")
val v321Main = "https://dl-cdn.alpinelinux.org/alpine/v3.21/main/aarch64"

// git (alpine v3.21) 及其动态依赖 libcurl 的完整闭包, 全部从 alpine main 仓库取二进制:
// 保证内置 git 支持本地操作 + https 远程 (git-remote-http)。
val alpineLibApks = listOf(
    "pcre2-10.43-r0.apk",
    "zlib-1.3.2-r0.apk",
    "libexpat-2.8.2-r0.apk",
    "libcurl-8.14.1-r2.apk",
    "brotli-libs-1.1.0-r2.apk",
    "c-ares-1.34.8-r0.apk",
    "libssl3-3.3.7-r0.apk",
    "libcrypto3-3.3.7-r0.apk",
    "libidn2-2.3.7-r0.apk",
    "libunistring-1.2-r0.apk",
    "libpsl-0.21.5-r3.apk",
    "nghttp2-libs-1.69.0-r0.apk",
    "zstd-libs-1.5.6-r2.apk",
    "libgcc-14.2.0-r4.apk",
    "libstdc++-14.2.0-r4.apk",
)

/**
 * 构建 proot 容器运行时 (assets/opencode/rootfs.tar.gz + version.txt):
 *   - opencode linux-arm64-musl 二进制 → rootfs/usr/local/bin/opencode
 *   - alpine-minirootfs 基础系统 (busybox + apk, 用户可在容器内 apk add 任意工具)
 *   - git + libcurl 依赖闭包 + CA 证书 → rootfs (opencode 的 AI 子进程要用 git)
 * 运行时由 ServerManager 解压到 files/opencode/rootfs, 再通过 proot 启动。
 */
tasks.register("downloadRootfs") {
    description = "Build Alpine rootfs (opencode + git) into src/main/assets/opencode"
    val outputDir = opencodeAssetDir
    outputs.dir(outputDir)
    // 注意: 不用 .tar.gz 后缀 — AGP 打包 assets 时会把 .gz 资产解压并改名成 .tar,
    // 这里直接产出纯 .tar, ServerManager 端按需解 gzip (探测 magic)。
    onlyIf { !file("$outputDir/rootfs.tar").exists() }
    doLast {
        val build = layout.buildDirectory
        val work = build.dir("rootfs-work").get().asFile
        work.mkdirs()

        // ---- 1. opencode 二进制 ----
        val api = "https://api.github.com/repos/anomalyco/opencode/releases/latest"
        val json = httpGet(api)
        val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(json)
            ?.groupValues?.get(1) ?: error("cannot parse tag_name from GitHub API")
        val dl = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]*linux-arm64-musl[^\"]*tar\\.gz)\"")
            .find(json)?.groupValues?.get(1)
            ?: error("cannot find linux-arm64-musl.tar.gz asset in latest release")
        val tarball = File(work, "opencode-$tag.tar.gz")
        download(dl, tarball)
        copy {
            from(tarTree(project.resources.gzip(tarball))) { include("**/opencode") }
            into(work)
        }
        val bin = File(work, "opencode")
        require(bin.exists()) { "opencode binary not extracted" }

        // ---- 2. alpine minirootfs (busybox + apk + musl) ----
        // 固定版本保证构建可复现 (升级需手动改这里); 用版本分支路径,
        // latest-stable 目录在 alpine 升级后会移除旧版文件
        val miniName = "alpine-minirootfs-3.24.1-aarch64.tar.gz"
        val miniTarball = File(work, miniName)
        download("https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/$miniName", miniTarball)

        // ---- 3. git + libcurl 依赖闭包 + CA (alpine v3.21 main) ----
        val gitApk = File(work, "git-2.47.3-r0.apk")
        download("$v321Main/git-2.47.3-r0.apk", gitApk)
        val caApk = File(work, "ca-certificates-bundle-20260413-r0.apk")
        download("$v321Main/ca-certificates-bundle-20260413-r0.apk", caApk)
        // gcompat (glibc 兼容垫片): opencode musl 版内嵌的终端 pty 原生库是
        // glibc 链接的, musl 容器里 dlopen 直接失败 → /pty 接口 500 → Web UI
        // 终端永远空白。垫片提供 libc.so.6 等符号表; 配合 ServerManager 启动时
        // LD_PRELOAD 进全局作用域后实测 /pty 建会话 + WS 流式输出全部正常。
        val gcompatApk = File(work, "gcompat-1.1.0-r4.apk")
        download("$v321Main/gcompat-1.1.0-r4.apk", gcompatApk)
        // gcompat 垫片 (/lib/libc.so.6=libgcompat.so.0) 的 DT_NEEDED 依赖,
        // 缺了任何一个 LD_PRELOAD 都会静默失败 → pty 修复无效
        val ucontextApk = File(work, "libucontext-1.3.2-r0.apk")
        download("$v321Main/libucontext-1.3.2-r0.apk", ucontextApk)
        val obstackApk = File(work, "musl-obstack-1.2.3-r2.apk")
        download("$v321Main/musl-obstack-1.2.3-r2.apk", obstackApk)
        val libApkFiles = alpineLibApks.map { name ->
            val f = File(work, name)
            download("$v321Main/${name.replace("+", "%2B")}", f)
            f
        }

        // ---- 4. 组装 rootfs.tar (纯 tar, 不 gzip, 见任务头部注释) ----
        val rootfsOut = File(outputDir.asFile, "rootfs.tar")
        val libArgs = libApkFiles.flatMap { listOf("--lib-apk", it.absolutePath) }
        runPython(scriptsDir.resolve("build_rootfs.py").absolutePath,
                "--minirootfs", miniTarball.absolutePath,
                "--opencode", bin.absolutePath,
                "--out", rootfsOut.absolutePath,
                "--bin-apk", gitApk.absolutePath,
                *libArgs.toTypedArray(),
                "--lib-apk", gcompatApk.absolutePath,
                "--lib-apk", ucontextApk.absolutePath,
                "--lib-apk", obstackApk.absolutePath,
                // gcompat 包不带 libdl.so.2, 但 pty 库显式依赖它; glibc 2.34+
                // 已把 dl 函数并入 libc, 软链过去即可
                "--symlink", "lib/libdl.so.2=libc.so.6",
                "--ca-apk", caApk.absolutePath)

        // version.txt = opencode tag + rootfs 内容指纹, ServerManager 据此判断是否需要重新解压
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(rootfsOut.readBytes()).take(8).joinToString("") { "%02x".format(it) }
        file("$outputDir/version.txt").writeText("$tag-$hash")
        logger.lifecycle("rootfs ready ($tag-$hash): ${rootfsOut.name} ${rootfsOut.length()} bytes")
    }
}

/**
 * 下载 termux 的 proot + loader + 依赖库到:
 *   jniLibs/arm64-v8a/libproot.so|libproot-loader.so|libproot-loader32.so
 *   assets/opencode/proot/libtalloc.so.2|libandroid-shmem.so
 * proot/loader 放 nativeLibraryDir: 极端 ROM 也允许执行 app_lib_file;
 * 容器内 (rootfs) 的二进制由 proot 的 loader 直接 mmap 加载, 只需读权限。
 */
tasks.register("downloadProot") {
    description = "Download termux proot + loader into jniLibs/assets"
    val outputDir = opencodeAssetDir
    val jniDir = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")
    outputs.dir(jniDir)
    outputs.dir(outputDir)
    onlyIf { !file("${jniDir.asFile}/libproot.so").exists() }
    doLast {
        val build = layout.buildDirectory
        val work = build.dir("proot-work").get().asFile
        work.mkdirs()

        val base = "https://packages.termux.dev/apt/termux-main/pool/main"
        val prootDeb = File(work, "proot.deb")
        val tallocDeb = File(work, "libtalloc.deb")
        val shmemDeb = File(work, "libandroid-shmem.deb")
        download("$base/p/proot/proot_5.1.107.91_aarch64.deb", prootDeb)
        download("$base/libt/libtalloc/libtalloc_2.4.3_aarch64.deb", tallocDeb)
        download("$base/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb", shmemDeb)

        val assetProot = File(outputDir.asFile, "proot")
        runPython(scriptsDir.resolve("prepare_proot.py").absolutePath,
                prootDeb.absolutePath, tallocDeb.absolutePath, shmemDeb.absolutePath,
                jniDir.asFile.absolutePath, assetProot.absolutePath)
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("downloadRootfs")
    dependsOn("downloadProot")
}

android {
    namespace = "com.opencode.android"
    compileSdk = 34
    signingConfigs {
        create("release") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "PKCS12"
        }
    }
    defaultConfig {
        applicationId = applicationIdFull
        minSdk = 24
        targetSdk = 34
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        // server 端口注入代码 (并存包用不同端口避免冲突)
        buildConfigField("int", "SERVER_PORT", "$serverPort")
        manifestPlaceholders["appLabel"] = appLabel
    }
    buildFeatures {
        buildConfig = true
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
            // proot 及其 loader 是 bionic/静态 ELF 可执行文件 (伪装成 lib*.so 放 jniLibs):
            // 压缩进 APK, 安装时解压到 nativeLibraryDir (可执行), 并排除 strip。
            keepDebugSymbols += "**/libproot*.so"
            useLegacyPackaging = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}