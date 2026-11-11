plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sys.hud"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sys.hud"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("rel") {
            // CI 里由环境变量注入；本地直接跑 assembleDebug 也能装
            val ksPath = System.getenv("KS_PATH") ?: "ks.jks"
            val f = rootProject.file(ksPath)
            if (f.exists()) {
                storeFile = f
                storePassword = System.getenv("KS_PASS") ?: "123456"
                keyAlias = System.getenv("KS_ALIAS") ?: "ks"
                keyPassword = System.getenv("KS_PASS") ?: "123456"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (signingConfigs.getByName("rel").storeFile != null)
                signingConfigs.getByName("rel") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".d"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "DebugProbesKt.bin")
    }
}

// 零第三方依赖：只用 Android Framework + Kotlin 标准库，构建成功率最高
dependencies {
    implementation(kotlin("stdlib"))
}
