plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "org.astraeus.tracker"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.astraeus.tracker"
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.5.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("test").resources.srcDir("../../../protocol")
}
dependencies {
    implementation("com.google.ar:core:1.48.0")
    testImplementation("junit:junit:4.13.2")
}
