import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.ambient.yeelight"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ambient.yeelight"
        minSdk = 28
        targetSdk = 36
        versionCode = 10
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // No release key yet: signed with the debug key, so the APK installs
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

// The APK's name: ambient-yeelight-v1.0.0-debug.apk, ambient-yeelight-v1.0.0-release.apk
base {
    archivesName = "ambient-yeelight-v${android.defaultConfig.versionName}"
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
}
