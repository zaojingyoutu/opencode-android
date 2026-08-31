import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

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
    if (dest.exists() && dest.length() > 0) {
        logger.lifecycle("cached: ${dest.name} (${dest.length()} bytes)")
        return
    }
    if (dest.exists() && dest.length() == 0L) dest.delete()
    logger.lifecycle("downloading ${dest.name} ...")
    retry(delayMs = 10000) {
        val tmp = File(dest.absolutePath + ".part")
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "opencode-android-build")
            conn.connectTimeout = 60000
            conn.readTimeout = 300000
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            }
            val expected = conn.contentLengthLong
            if (expected > 0 && tmp.length() != expected) {
                throw IllegalStateException("size mismatch ${tmp.length()} != $expected")
            }
            if (tmp.length() == 0L) throw IllegalStateException("empty download")
            if (!tmp.renameTo(dest)) throw IllegalStateException("rename ${tmp.name} failed")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }
}



fun runPython(script: String, vararg args: String) {
    val python = if (System.getProperty("os.name").lowercase().contains("win")) "python" else "python3"
    exec { commandLine(python, script, *args) }
}

val scriptsDir = rootProject.projectDir.parentFile.resolve("scripts")
// 与 minirootfs 统一用 v3.24 版本线, 避免跨大版本混用 musl 符号
val alpineMain = "https://dl-cdn.alpinelinux.org/alpine/v3.24/main/aarch64"

/** 查询 alpine main 仓库中某包的当前版本 (解析 APKINDEX) */
fun alpineCurrentVersion(pkg: String): String {
    return retry {
        val conn = URL("$alpineMain/APKINDEX.tar.gz").openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "opencode-android-build")
        conn.connectTimeout = 30000
        conn.readTimeout = 60000
        GZIPInputStream(conn.inputStream).bufferedReader().use { reader ->
            var curPkg = ""
            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith("P:")) curPkg = line.substring(2)
                if (line.startsWith("V:") && curPkg == pkg) return@retry line.substring(2)
            }
            throw IllegalStateException("package $pkg not found in $alpineMain/APKINDEX")
        }
    }
}

/**
 * 下载固定版本的 alpine main 包。上游安全重建会把旧版本文件从源里移除
 * (如 libexpat-2.8.2-r0 被 2.8.3-r0 替换后 CI 直接 404), 此时自动回退到
 * APKINDEX 当前版本并打日志, 流水线不再被上游重建卡死。
 */
fun downloadAlpineApk(pkg: String, pinnedVer: String, workDir: File): File {
    val dest = File(workDir, "$pkg-$pinnedVer.apk")
    try {
        download("$alpineMain/${dest.name.replace("+", "%2B")}", dest)
        return dest
    } catch (e: Exception) {
        dest.delete()
    }
    val cur = alpineCurrentVersion(pkg)
    logger.lifecycle("⚠️ 固定版本 ${dest.name} 已被源移除 (上游重建?), 回退到当前版本 $pkg-$cur")
    val curFile = File(workDir, "$pkg-$cur.apk")
    download("$alpineMain/${curFile.name.replace("+", "%2B")}", curFile)
    return curFile
}

/** 查询 termux repo (Packages 索引) 中某包的当前版本, 用于固定版本被源移除时回退 */
fun termuxCurrentVersion(pkg: String): String {
    return retry {
        val conn = URL("https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages")
            .openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "opencode-android-build")
        conn.connectTimeout = 30000
        conn.readTimeout = 60000
        conn.inputStream.bufferedReader().use { reader ->
            var name: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith("Package: ")) name = line.substring("Package: ".length).trim()
                if (name == pkg && line.startsWith("Version: ")) {
                    return@retry line.substring("Version: ".length).trim()
                }
            }
            throw IllegalStateException("package $pkg not found in termux Packages")
        }
    }
}

/** termux 包路径规则: pool/main/<首字母或libprefix>/<pkg>/<pkg>_<ver>_<arch>.deb */
fun termuxDebPath(pkg: String): String {
    val seg = when {
        pkg.startsWith("liba") -> "liba"
        pkg.startsWith("libt") -> "libt"
        pkg.startsWith("lib") -> "lib${pkg[3]}"
        else -> pkg.substring(0, 1)
    }
    return "$seg/$pkg"
}

/** 下载固定版本的 termux deb; 固定版本被源移除时回退到当前版本 */
fun downloadTermuxDeb(pkg: String, pinnedVer: String, workDir: File): File {
    val arch = "aarch64"
    val base = "https://packages.termux.dev/apt/termux-main/pool/main"
    val dest = File(workDir, "${pkg}_${pinnedVer}_${arch}.deb")
    try {
        download("$base/${termuxDebPath(pkg)}/${dest.name}", dest)
        return dest
    } catch (e: Exception) {
        dest.delete()
    }
    val cur = termuxCurrentVersion(pkg)
    logger.lifecycle("⚠️ 固定版本 ${dest.name} 已被源移除 (上游重建?), 回退到当前版本 ${pkg}_${cur}_$arch")
    val curFile = File(workDir, "${pkg}_${cur}_${arch}.deb")
    download("$base/${termuxDebPath(pkg)}/${curFile.name}", curFile)
    return curFile
}

// git (alpine v3.24, 与 minirootfs 同版本线) 及其动态依赖 libcurl 的完整闭包,
// 全部从 alpine main 仓库取二进制: 保证内置 git 支持本地操作 + https 远程 (git-remote-http)。
// Pair = 包名 to 固定版本 (升级需手动改; 被上游重建移除时自动回退当前版本, 见 downloadAlpineApk)
val alpineLibApks = listOf(
    "pcre2" to "10.47-r1",
    "zlib" to "1.3.2-r0",
    "libexpat" to "2.8.3-r0",
    "libcurl" to "8.21.0-r0",
    "brotli-libs" to "1.2.0-r1",
    "c-ares" to "1.34.8-r0",
    "libssl3" to "3.5.7-r0",
    "libcrypto3" to "3.5.7-r0",
    "libidn2" to "2.3.8-r0",
    "libunistring" to "1.4.2-r0",
    "libpsl" to "0.21.5-r3",
    "nghttp2-libs" to "1.69.0-r0",
    "zstd-libs" to "1.5.7-r2",
    "libgcc" to "15.2.0-r5",
    "libstdc++" to "15.2.0-r5",
)

/**
 * 构建 proot 容器运行时 (assets/opencode/base.tar + opencode-bin + version.txt):
 *   - opencode linux-arm64-musl 二进制 → assets/opencode/opencode-bin (独立文件)
 *   - alpine-minirootfs 基础系统 (busybox + apk, 用户可在容器内 apk add 任意工具)
 *     + git + libcurl 依赖闭包 + CA 证书 → assets/opencode/base.tar
 * 运行时由 ServerManager 解压到 files/opencode/rootfs, 再通过 proot 启动;
 * 二进制与基础系统分离, 升级时只有二进制变化可原位覆盖、保留用户安装的工具。
 */
tasks.register("downloadRootfs") {
    description = "Build Alpine rootfs (opencode + git) into src/main/assets/opencode"
    val outputDir = opencodeAssetDir
    outputs.dir(outputDir)
    // 注意: 不用 .tar.gz 后缀 — AGP 打包 assets 时会把 .gz 资产解压并改名成 .tar,
    // 这里直接产出纯 .tar, ServerManager 端按需解 gzip (探测 magic)。
    outputs.file("$outputDir/base.tar")
    outputs.file("$outputDir/opencode-bin")
    outputs.file("$outputDir/version.txt")
    onlyIf {
        val v = File("$outputDir/version.txt")
        !v.exists() || !v.readText().contains("opencode=") ||
                !file("$outputDir/base.tar").exists() || !file("$outputDir/opencode-bin").exists()
    }
    doLast {
        val build = layout.buildDirectory
        val work = build.dir("rootfs-work").get().asFile
        work.mkdirs()

        // ---- 1. opencode 二进制 ----
        var tag: String? = null
        var dl: String? = null
        try {
            val api = "https://api.github.com/repos/anomalyco/opencode/releases/latest"
            val json = httpGet(api)
            tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
            dl = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]*linux-arm64-musl[^\"]*tar\\.gz)\"")
                .find(json)?.groupValues?.get(1)
        } catch (e: Exception) {
            logger.lifecycle("GitHub API failed (${e.message}), fallback to v1.18.25")
        }
        if (tag == null || dl == null) {
            tag = "v1.18.25"
            dl = "https://github.com/anomalyco/opencode/releases/download/$tag/opencode-linux-arm64-musl.tar.gz"
        }
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

        // ---- 3. git + libcurl 依赖闭包 + CA (alpine v3.24 main, 与 minirootfs 同版本线) ----
        val gitApk = downloadAlpineApk("git", "2.54.0-r0", work)
        val caApk = downloadAlpineApk("ca-certificates-bundle", "20260611-r0", work)
        // gcompat (glibc 兼容垫片): opencode musl 版内嵌的终端 pty 原生库是
        // glibc 链接的, musl 容器里 dlopen 直接失败 → /pty 接口 500 → Web UI
        // 终端永远空白。垫片提供 libc.so.6 等符号表; 配合 ServerManager 启动时
        // LD_PRELOAD 进全局作用域后实测 /pty 建会话 + WS 流式输出全部正常。
        val gcompatApk = downloadAlpineApk("gcompat", "1.1.0-r4", work)
        // gcompat 垫片 (/lib/libc.so.6=libgcompat.so.0) 的 DT_NEEDED 依赖,
        // 缺了任何一个 LD_PRELOAD 都会静默失败 → pty 修复无效
        val ucontextApk = downloadAlpineApk("libucontext", "1.5.1-r0", work)
        val obstackApk = downloadAlpineApk("musl-obstack", "1.2.3-r2", work)
        val libApkFiles = alpineLibApks.map { (pkg, ver) -> downloadAlpineApk(pkg, ver, work) }

        // ---- 4. 组装 base.tar (纯 tar, 不 gzip, 见任务头部注释) + opencode-bin ----
        // 拆成两份: base.tar 是基础系统 (minirootfs+git+libs+certs, ~26MB),
        // opencode-bin 是独立二进制 (~190MB)。ServerManager 据此实现增量升级:
        // 只有二进制变化时原位覆盖, 用户在容器内 apk add 的工具得以保留。
        val baseOut = File(outputDir.asFile, "base.tar")
        val binOut = File(outputDir.asFile, "opencode-bin")
        val libArgs = libApkFiles.flatMap { listOf("--lib-apk", it.absolutePath) }
        runPython(scriptsDir.resolve("build_rootfs.py").absolutePath,
                "--minirootfs", miniTarball.absolutePath,
                "--opencode", bin.absolutePath,
                "--out", baseOut.absolutePath,
                "--bin-out", binOut.absolutePath,
                "--bin-apk", gitApk.absolutePath,
                *libArgs.toTypedArray(),
                "--lib-apk", gcompatApk.absolutePath,
                "--lib-apk", ucontextApk.absolutePath,
                "--lib-apk", obstackApk.absolutePath,
                // gcompat 包不带 libdl.so.2, 但 pty 库显式依赖它; glibc 2.34+
                // 已把 dl 函数并入 libc, 软链过去即可
                "--symlink", "lib/libdl.so.2=libc.so.6",
                "--ca-apk", caApk.absolutePath)

        // version.txt: base=基础系统指纹, bin=二进制指纹 (均取 SHA-256 前 8 字节)。
        // ServerManager 分别比对: base 变了才整删重装, 只有 bin 变则原位覆盖。
        fun sha8(f: File): String =
            MessageDigest.getInstance("SHA-256").digest(f.readBytes())
                .take(8).joinToString("") { "%02x".format(it) }
        val baseHash = sha8(baseOut)
        val binHash = sha8(binOut)
        file("$outputDir/version.txt")
            .writeText("base=$baseHash\nbin=$binHash\nopencode=$tag\n")
        logger.lifecycle("rootfs ready (base=$baseHash bin=$binHash opencode=$tag): " +
                "${baseOut.name} ${baseOut.length()} bytes, ${binOut.name} ${binOut.length()} bytes")
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
        // 钉版: 上游若安全重建移除旧文件, downloadTermuxDeb 会自动回退当前版本
        val prootDeb = downloadTermuxDeb("proot", "5.1.107.92", work)
        val tallocDeb = downloadTermuxDeb("libtalloc", "2.4.3", work)
        val shmemDeb = downloadTermuxDeb("libandroid-shmem", "0.7", work)

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
        // manifest 里的版本 meta-data 跟随实际构建版本, 不再硬编码
        manifestPlaceholders["appVersion"] = releaseVersionName
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

dependencies {
    // 纯 JVM 单元测试 (ServerManager 的 JSON 解析等纯函数)。
    // org.json 用真实现: 单测跑在 JVM 上, android.jar 里的 org.json 只是抛异常的 stub
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}