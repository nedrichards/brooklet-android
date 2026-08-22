import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        file.inputStream().use(::load)
    }
}

fun localBooleanProperty(name: String): Boolean {
    val value = providers.gradleProperty(name).orNull
        ?: localProperties.getProperty(name)
    return value.equals("true", ignoreCase = true)
}

fun localStringProperty(name: String): String =
    providers.gradleProperty(name).orNull ?: localProperties.getProperty(name).orEmpty()

fun buildConfigString(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val debugSignRelease = localBooleanProperty("brooklet.debugSignRelease")

android {
    namespace = "com.nedrichards.brooklet"
    compileSdk = 37
    compileSdkMinor = 1
    defaultConfig {
        applicationId = "com.nedrichards.brooklet"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEV_MINIFLUX_URL", "\"\"")
        buildConfigField("String", "DEV_MINIFLUX_TOKEN", "\"\"")
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("String", "DEV_MINIFLUX_URL", buildConfigString(localStringProperty("brooklet.devMinifluxUrl")))
            buildConfigField("String", "DEV_MINIFLUX_TOKEN", buildConfigString(localStringProperty("brooklet.devMinifluxToken")))
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (debugSignRelease) {
                signingConfigs.getByName("debug")
            } else {
                null
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; aidl = false; buildConfig = true; shaders = false }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-database"))
    implementation(project(":core-network"))
    implementation(project(":core-sync"))
    implementation(project(":core-designsystem"))
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
