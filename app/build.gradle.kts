plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nanoturtle.rootexec"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nanoturtle.rootexec"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
        debug { }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui:1.12.0-beta01")
    implementation("androidx.compose.foundation:foundation:1.12.0-beta01")
    implementation("androidx.compose.material3:material3:1.5.0-alpha25")
    implementation("androidx.compose.ui:ui-tooling-preview:1.12.0-beta01")
    debugImplementation("androidx.compose.ui:ui-tooling:1.12.0-beta01")
}
