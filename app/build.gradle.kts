plugins { id("com.android.application") }

android {
    namespace = "com.pierce.canadaip"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pierce.canadaip"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation("androidx.core:core:1.16.0")
}
