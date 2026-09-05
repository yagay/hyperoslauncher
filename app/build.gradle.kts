plugins { id("com.android.application") }

android {
    namespace = "com.yagay.desktopgridx"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yagay.desktopgridx"
        minSdk = 31
        targetSdk = 37
        versionCode = 20
        versionName = "0.20.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-fvisibility=hidden")
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    buildFeatures { prefab = true }
    packaging {
        jniLibs {
            // HYOS native entries are loaded directly from the APK by LSPosed IT 7869.
            // Match known-good HyperOS 4 modules instead of forcing library extraction.
            useLegacyPackaging = false
            pickFirsts += "lib/arm64-v8a/libshadowhook.so"
        }
        resources { merges += "META-INF/xposed/*" }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("com.bytedance.android:shadowhook:2.0.1")
    implementation("org.tukaani:xz:1.10")
}
