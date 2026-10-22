import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("jacoco")
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.sysu.go"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sysu.go"
        buildConfigField("boolean", "USE_FIREBASE_EMULATORS", "false")
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Explicit emulator instrumentation leaves the default production build unchanged.
    testBuildType = providers.gradleProperty("sysuTestBuildType").orElse("debug").get()

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "API_BASE_URL", "\"https://sysu-go.onrender.com\"")
            buildConfigField("String", "SOCKET_URL", "\"https://sysu-go.onrender.com\"")
            buildConfigField("String", "FRONTEND_URL", "\"https://sysu-web-5a25d.web.app\"")
        }
        create("emulator") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "USE_FIREBASE_EMULATORS", "true")
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:3000\"")
            buildConfigField("String", "SOCKET_URL", "\"http://10.0.2.2:3000\"")
            buildConfigField("String", "FRONTEND_URL", "\"http://10.0.2.2:5173\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "API_BASE_URL", "\"https://sysu-go.onrender.com\"")
            buildConfigField("String", "SOCKET_URL", "\"https://sysu-go.onrender.com\"")
            buildConfigField("String", "FRONTEND_URL", "\"https://sysu-web-5a25d.web.app\"")
        }
        create("liveTest") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    sourceSets.getByName("liveTest").res.srcDir("src/release/res")

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

// Scoped evidence for the shipped QR transport; this is not whole-app coverage.
jacoco { toolVersion = "0.8.15" }
tasks.register<JacocoReport>("qrTransportCoverage") {
    dependsOn("testDebugUnitTest")
    executionData(layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"))
    classDirectories.setFrom(fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
        include("com/sysu/go/network/QrHttpClient*.class")
    })
    sourceDirectories.setFrom(files("src/main/java"))
    reports { xml.required.set(true); html.required.set(true) }
}

tasks.register<JacocoReport>("environmentPolicyCoverage") {
    dependsOn("testDebugUnitTest")
    executionData(layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"))
    classDirectories.setFrom(fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) { include("com/sysu/go/config/EnvironmentPolicy*.class") })
    sourceDirectories.setFrom(files("src/main/java"))
    reports { xml.required.set(true); html.required.set(true) }
}

tasks.register<JacocoReport>("accountPolicyCoverage") {
 dependsOn("testDebugUnitTest")
 executionData(layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"))
 classDirectories.setFrom(fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")){include("com/sysu/go/firebase/AccountPolicy*.class")})
 sourceDirectories.setFrom(files("src/main/java"))
 reports {xml.required.set(true);html.required.set(true)}
}
