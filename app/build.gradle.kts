plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.teja.bumblebee"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.teja.bumblebee"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloaded personal app: sign release with the debug key so it installs without extra setup.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.androidx.palette)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.profileinstaller)

    testImplementation(libs.junit)
}
