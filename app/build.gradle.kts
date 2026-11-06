plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
}

android {
    namespace = "com.archdroid"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.archdroid"
        minSdk = 26
        // targetSdk 28 grants SELinux untrusted_app_27 domain so proot & child binaries
        // execute without W^X execve blocks on Android 10-16.
        targetSdk = 28
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    androidResources {
        noCompress += listOf("bin", "gz", "tgz", "xz", "so")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.termux.terminal.view)
    implementation(libs.termux.terminal.emulator)
    implementation(libs.commons.compress)
}

tasks.register("copyApkToDownload") {
    doLast {
        val downloadDir = File("/sdcard/Download")
        val apkSource = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        if (downloadDir.exists() && apkSource.exists()) {
            val destApk = File(downloadDir, "ArchDroid-debug.apk")
            apkSource.copyTo(destApk, overwrite = true)
            val kimApk = File(downloadDir, "KimSystem-debug.apk")
            apkSource.copyTo(kimApk, overwrite = true)
            println("[Gradle] Automatically copied debug APK to ${destApk.absolutePath} and ${kimApk.absolutePath} (${destApk.length() / (1024 * 1024)} MB)")
        }
    }
}

afterEvaluate {
    tasks.findByName("assembleDebug")?.finalizedBy("copyApkToDownload")
}

