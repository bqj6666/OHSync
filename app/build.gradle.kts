plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.ohsync"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.ohsync"
        minSdk = 34
        targetSdk = 36
        versionCode = 10002
        versionName = "1.0.2"
    }

    signingConfigs {
        create("release") {
            // 只在有环境变量时生效：本地无密钥也能构建 debug
            val ksPath = System.getenv("OHSYNC_KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("OHSYNC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OHSYNC_KEY_ALIAS")
                keyPassword = System.getenv("OHSYNC_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Health Connect 官方客户端
    implementation("androidx.health.connect:connect-client:1.1.0")

    // Compose + Material 3
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    // Xposed（compileOnly：运行时由 LSPosed 提供）
    compileOnly("io.github.libxposed:api:102.0.0")
}
