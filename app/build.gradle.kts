plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "pt.radio.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "pt.radio.app"
        minSdk = 24
        targetSdk = 34
        versionCode = run
        versionName = "1.0.$run"
    }

    // chave fixa: assim cada versão nova instala por cima da anterior
    signingConfigs {
        create("radio") {
            storeFile = file("radio.jks")
            storePassword = "radioapp"
            keyAlias = "radio"
            keyPassword = "radioapp"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("radio")
        }
        debug {
            signingConfig = signingConfigs.getByName("radio")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("com.google.guava:guava:33.2.1-android")
}
