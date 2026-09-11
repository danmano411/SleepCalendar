plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.danmano.sleepcal"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.danmano.sleepcal"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.work.runtime)
    // Samsung Health Data SDK: licensed download, kept out of git (see README).
    implementation(files("libs/samsung-health-data-api-1.1.0.aar"))
    // The SDK needs these at runtime but doesn't declare them; without parcelize-runtime every read
    // dies with NoClassDefFoundError: kotlinx.parcelize.Parceler.
    implementation(libs.gson)
    implementation(libs.kotlin.parcelize.runtime)

    testImplementation(libs.junit)
}
