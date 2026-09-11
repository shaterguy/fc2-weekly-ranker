plugins {
    id("com.android.application")
}

android {
    namespace = "com.shaterguy.fc2weeklyranker.externalreceiver"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.shaterguy.fc2weeklyranker.externalreceiver"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
