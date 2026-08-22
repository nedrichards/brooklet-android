plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core-model"))
    implementation(libs.junit)
    implementation(libs.kotlinx.coroutines.test)
}
