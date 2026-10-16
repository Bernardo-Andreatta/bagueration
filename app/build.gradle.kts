plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bernardo.ereader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bernardo.ereader"
        minSdk = 26
        targetSdk = 34
        versionCode = 31
        versionName = "5.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // "friend" = the copy shared with a friend: Baguera (the cat behind the name) as icon and library header
    flavorDimensions += "audience"
    productFlavors {
        create("personal") {
            dimension = "audience"
            manifestPlaceholders["appIcon"] = "@android:drawable/ic_menu_agenda"
            manifestPlaceholders["appRoundIcon"] = "@android:drawable/ic_menu_agenda"
        }
        create("friend") {
            dimension = "audience"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"
            manifestPlaceholders["appRoundIcon"] = "@mipmap/ic_launcher_round"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // keep the model uncompressed so it can be read straight from the APK
    androidResources { noCompress += listOf("onnx", "bin") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    testImplementation("junit:junit:4.13.2")
}
