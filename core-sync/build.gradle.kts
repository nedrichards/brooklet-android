plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.nedrichards.brooklet.sync"
    compileSdk = 37
    compileSdkMinor = 1
    defaultConfig { minSdk = 28 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-database"))
    implementation(project(":core-network"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
