plugins { id("com.android.application") }

android {
    namespace = "com.yagay.desktopgridx"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yagay.desktopgridx"
        minSdk = 31
        targetSdk = 37
        versionCode = 18
        versionName = "0.18.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake { cppFlags += listOf("-std=c++20", "-fvisibility=hidden") }
        }
    }

    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    buildFeatures { prefab = true }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "lib/arm64-v8a/libshadowhook.so"
        }
        resources { merges += "META-INF/xposed/*" }
    }
}

dependencies {
    // Keep Modern API 102 for the normal LSPosed path.
    compileOnly("io.github.libxposed:api:102.0.0")
    // LSPosed 2.2.0-it 7869 HYOS compatibility marker path observed on-device
    // also accepts legacy Xposed entry metadata. This dependency is compile-only.
    compileOnly("de.robv.android.xposed:api:82")
    implementation("com.bytedance.android:shadowhook:2.0.1")
    implementation("org.tukaani:xz:1.10")
}
