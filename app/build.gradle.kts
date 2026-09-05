plugins { id("com.android.application") }

android {
    namespace = "com.pierce.canadaip"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pierce.canadaip"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"
    }
}

dependencies {
    implementation("androidx.core:core:1.16.0")
}
