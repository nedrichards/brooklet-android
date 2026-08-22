plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}
android {
    namespace = "com.nedrichards.brooklet.designsystem"
    compileSdk = 37
    compileSdkMinor = 1
    defaultConfig { minSdk = 28 }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
}
