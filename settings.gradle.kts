pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
    }
}

// Patch vulnerable dependencies brought in by settings plugins.
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

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Brooklet"
include(
    ":app-phone",
    ":app-wear",
    ":core-model",
    ":core-database",
    ":core-network",
    ":core-sync",
    ":core-designsystem",
    ":core-testing",
    ":core-wear-data",
    ":baseline-profile",
)

project(":app-phone").projectDir = file("app")
