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
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        getByName("release") { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}