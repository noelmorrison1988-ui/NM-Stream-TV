plugins {
    id("com.android.application")
}

android {
    namespace = "za.co.nmstream.approver"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "za.co.nmstream.approver"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
