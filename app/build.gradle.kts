plugins { id("com.android.application") }

android {
    namespace = "com.yagay.desktopgridx"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yagay.desktopgridx"
        minSdk = 31
        targetSdk = 37
        versionCode = 14
        versionName = "0.14.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake { cppFlags += listOf("-std=c++20", "-fvisibility=hidden") }
        }
    }

    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    buildFeatures { prefab = true }
    packaging {
        jniLibs {
            // Modern Xposed native entry needs the module .so directly loadable from the APK.
            // false keeps JNI libs uncompressed/page-aligned instead of legacy compressed packaging.
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
