plugins {
    id("com.android.application") version "9.5.1"
}

android {
    namespace = "com.locusable.ginkgo"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.locusable.ginkgo"
        minSdk = 27
        targetSdk = 34
        versionCode = 71
        versionName = "0.4.61"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
