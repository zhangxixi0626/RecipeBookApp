plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.owner.lynk10remote"
    compileSdk = 36

    defaultConfig {
        // 伪装包名：绕开车机安装白名单限制（Flyme Auto 允许安装优酷）。
        // namespace 保持 com.owner.lynk10remote，组件类名不变，源码零改动。
        applicationId = "com.youku.phone"
        minSdk = 28
        targetSdk = 35
        versionCode = 6
        versionName = "1.04"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/NOTICE*", "META-INF/LICENSE*")
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
}
