plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gontijotech.gtstore.client"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gontijotech.gtstore.client"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // Servidor HTTP embutido
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Requisições HTTP e Proxy
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines para operações de rede assíncronas
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Suporte a componentes Android
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}
