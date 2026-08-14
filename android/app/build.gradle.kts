plugins {
    id("com.android.application")
}

android {
    namespace = "com.spotdl.gui.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.spotdl.gui.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
}

dependencies {
    val youtubedlAndroid = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlAndroid")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubedlAndroid")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
