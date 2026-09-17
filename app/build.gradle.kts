plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "dev.polidog.hachi"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.polidog.hachi"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "armeabi-v7a" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    // The android.jar stub throws "not mocked" for JSONObject; unit tests need a real impl.
    testImplementation("org.json:json:20240303")
}
