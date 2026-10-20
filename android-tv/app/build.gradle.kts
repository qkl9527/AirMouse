plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 本地 Debug 仍使用默认版本；CI 从 android-vX.Y.Z 标签传入正式版本号。
val apkVersionName = providers.gradleProperty("airmouseVersionName").orElse("1.0.0").get()
val apkVersionCode = providers.gradleProperty("airmouseVersionCode").orElse("1").get().toInt()
require(apkVersionCode in 1..2100000000) { "airmouseVersionCode 必须在 1..2100000000 之间" }

// 签名仅从环境变量读取，仓库中不保存密钥或密码；本地和 CI 使用同一套配置。
val releaseKeystore = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseKeystore, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all { !it.isNullOrBlank() }

// 明确拒绝生成未签名的正式安装包，避免发布后用户无法安装。
if (gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }) {
    require(hasReleaseSigning) {
        "Release 构建需要 ANDROID_KEYSTORE_PATH、ANDROID_KEYSTORE_PASSWORD、ANDROID_KEY_ALIAS、ANDROID_KEY_PASSWORD"
    }
}

android {
    namespace = "dev.airmouse.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.airmouse.tv"
        minSdk = 24
        targetSdk = 35
        versionCode = apkVersionCode
        versionName = apkVersionName
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystore))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
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
