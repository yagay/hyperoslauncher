plugins { id("com.android.application") }

android {
    namespace = "com.yagay.desktopgridx"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yagay.desktopgridx"
        minSdk = 31
        targetSdk = 37
        versionCode = 23
        versionName = "0.23.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-fvisibility=hidden")
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    packaging {
        jniLibs {
            // LSPosed IT 7869 maps the Modern native entry directly from the APK.
            useLegacyPackaging = false
        }
        resources { merges += "META-INF/xposed/*" }
    }
}

dependencies {
    // Compile against the same Modern API generation declared in module.prop.
    compileOnly("io.github.libxposed:api:102.0.0")
    // XZ is used only by the app-side .gnu_debugdata resolver.
    implementation("org.tukaani:xz:1.10")
}
