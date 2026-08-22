plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.nedrichards.brooklet.database"
    compileSdk = 37
    compileSdkMinor = 1
    defaultConfig { minSdk = 28 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core-model"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.core)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
