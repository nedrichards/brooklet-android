// Keep vulnerable transitive libraries on the AGP plugin classpath patched.
buildscript {
  configurations.classpath {
    resolutionStrategy.force(
      "org.bouncycastle:bcpkix-jdk18on:1.85",
      "org.bouncycastle:bcprov-jdk18on:1.85",
      "org.bitbucket.b_c:jose4j:0.9.6",
      "org.jdom:jdom2:2.0.6.1",
      "org.apache.commons:commons-lang3:3.18.0",
      "org.apache.httpcomponents:httpclient:4.5.14",
      "com.squareup.wire:wire-runtime:6.4.5",
    )
  }
}

// Apply the same patched versions to application and test configurations.
allprojects {
  configurations.configureEach {
    resolutionStrategy.force(
      "org.bouncycastle:bcpkix-jdk18on:1.85",
      "org.bouncycastle:bcprov-jdk18on:1.85",
      "org.bitbucket.b_c:jose4j:0.9.6",
      "org.jdom:jdom2:2.0.6.1",
      "org.apache.commons:commons-lang3:3.18.0",
      "org.apache.httpcomponents:httpclient:4.5.14",
      "com.squareup.wire:wire-runtime:6.4.5",
    )
  }
}

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.android.test) apply false
  alias(libs.plugins.androidx.baselineprofile) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.ksp) apply false
}
