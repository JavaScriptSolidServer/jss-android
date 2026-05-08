plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "live.jss.jss_android"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "live.jss.jss_android"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17", "-fexceptions", "-frtti")
                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-24"
                )
            }
        }
    }

    // Drop x86_64 unless you need emulator builds; arm + arm64 cover all
    // physical devices since 2020. splits.abi produces one APK slice per
    // ABI; the AAB picks the right one per device.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            // libnode.so + headers vendored by scripts/fetch-libnode.sh.
            // Both paths are .gitignored; CI populates them before build.
            jniLibs.srcDirs("../app/libnode/bin")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.material:material:1.12.0")
}
