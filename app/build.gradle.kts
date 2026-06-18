plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.sysu.go"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sysu.go"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "API_BASE_URL", "\"https://sysu-go-production.up.railway.app\"")
            buildConfigField("String", "SOCKET_URL", "\"https://sysu-go-production.up.railway.app\"")
            buildConfigField("String", "FRONTEND_URL", "\"https://sysu-web-5a25d.web.app\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "API_BASE_URL", "\"https://sysu-go-production.up.railway.app\"")
            buildConfigField("String", "SOCKET_URL", "\"https://sysu-go-production.up.railway.app\"")
            buildConfigField("String", "FRONTEND_URL", "\"https://sysu-web-5a25d.web.app\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
        dataBinding = true
        buildConfig = true
    }

    signingConfigs.getByName("debug") {
        storeFile = file("C:\\Users\\xyver\\OneDrive\\Documents\\sysu-go\\mobile\\android\\app\\debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth.ktx)
    implementation(libs.firebase.firestore.ktx)
    implementation(libs.firebase.storage.ktx)
    implementation(libs.kotlinx.coroutines.play.services)

    // Google Sign-In
    implementation(libs.play.services.auth)

    // Location
    implementation(libs.play.services.location)

    // CameraX for QR scanning
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ML Kit Barcode Scanning
    implementation(libs.mlkit.barcode.scanning)

    // Lifecycle
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Socket.IO (for live location tracking)
    implementation("io.socket:socket.io-client:2.1.0") {
        exclude(group = "org.json", module = "json")
    }

    // Security (for encrypted SharedPreferences)
    implementation("androidx.security:security-crypto:1.1.0")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")
}