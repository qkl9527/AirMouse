plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.airmouse.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.airmouse.tv"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Bouncy Castle 的多个模块都带有相同的许可文本；APK 中只需保留一份。
    packaging {
        resources {
            excludes += "META-INF/LICENSE.md"
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("com.google.zxing:core:3.5.4")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
}
