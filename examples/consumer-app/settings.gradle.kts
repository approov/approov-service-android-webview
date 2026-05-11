pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ConsumerExample"
include(":app")
include(":approov-service-android-webview")
project(":approov-service-android-webview").projectDir = file("third_party/approov-service-android-webview")
