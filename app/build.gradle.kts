plugins { id("com.android.application") }

android {
    namespace = "com.yagay.desktopgridx"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yagay.desktopgridx"
        minSdk = 31
        targetSdk = 37
        versionCode = 7
        versionName = "0.7.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake { cppFlags += listOf("-std=c++20", "-fvisibility=hidden") }
        }
    }

    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    buildFeatures { prefab = true }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("com.bytedance.android:shadowhook:2.0.1")
    implementation("org.tukaani:xz:1.10")
}
