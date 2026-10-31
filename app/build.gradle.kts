plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "gr.volley.matchup2"
    compileSdk = 35

    defaultConfig {
        applicationId = "gr.volley.matchup3"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "3.0.1"
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
