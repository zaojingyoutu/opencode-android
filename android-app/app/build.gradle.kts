plugins {
    id("com.android.application") version "8.5.2"
}
android {
    namespace = "com.opencode.android"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.opencode.android"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }
    buildTypes {
        getByName("release") { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }
    aaptOptions {
        // 跳过压缩以 .bin 结尾的资产（192MB ELF 二进制无需压缩）
        ignoreAssetsPattern = "!*.bin:*"
    }
}