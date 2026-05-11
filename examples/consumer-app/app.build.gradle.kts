plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.consumer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.consumer"
        minSdk = 24
        targetSdk = 36

        val approovConfig = providers.gradleProperty("approovConfig").orNull ?: ""
        val approovDevKey = providers.gradleProperty("approovDevKey").orNull ?: ""
        val protectedApiKey = providers.gradleProperty("protectedApiKey").orNull ?: ""

        buildConfigField("String", "APPROOV_CONFIG", "\"$approovConfig\"")
        buildConfigField("String", "APPROOV_DEV_KEY", "\"$approovDevKey\"")
        buildConfigField("String", "PROTECTED_API_KEY", "\"$protectedApiKey\"")
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":approov-service-android-webview"))
}
