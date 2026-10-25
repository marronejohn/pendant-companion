plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.protobuf")
}

android {
    namespace = "com.pendantcompanion"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "com.pendantcompanion"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "0.13.16"
        ndk { abiFilters += "arm64-v8a" }
    }
    externalNativeBuild {
        cmake {
            path = file("src/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // installable without a release key: debug-keystore signing, same
            // package John already sideloads
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    packaging {
        jniLibs {
            // compress native libs in the APK so the whole APK stays under 20 MB for Drive delivery
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.protobuf:protobuf-javalite:4.28.3")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    testImplementation("junit:junit:4.13.2")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.28.3" }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins { create("java") { option("lite") } }
        }
    }
}
