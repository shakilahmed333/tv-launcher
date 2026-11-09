plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shakil.tvlauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shakil.tvlauncher"
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
    }

    // A fixed key, kept in the repo, so every new build can be installed
    // over the previous one with `adb install -r`.
    signingConfigs {
        create("release") {
            storeFile = file("launcher.jks")
            storePassword = "tvlauncher"
            keyAlias = "launcher"
            keyPassword = "tvlauncher"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// No extra libraries on purpose: plain Android views keep RAM use low.
