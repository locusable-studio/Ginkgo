plugins {
    id("com.android.application") version "9.5.1"
}

dependencies {
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}

android {
    namespace = "com.locusable.ginkgo"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.locusable.ginkgo"
        minSdk = 27
        targetSdk = 34
        versionCode = 73
        versionName = "0.4.63"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
