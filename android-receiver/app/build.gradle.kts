plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android { namespace = "com.airferrylite.receiver"; compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "org.optiferry.receiver"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_shared") }
        }
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    buildTypes { release { isMinifyEnabled = false; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        buildConfig = true
        prefab = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("io.github.zxing-cpp:android:2.3.0")
    implementation("org.opencv:opencv:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation("junit:junit:4.13.2")
}

// Integration fixtures are real task inputs: never silently reuse an unrelated
// cached test result when a caller changes the native frame stream.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    for (key in listOf("OPTIFERRY_FRAMES", "OPTIFERRY_DEFAULT_FRAMES")) {
        val path = System.getenv(key)
        inputs.property(key, path ?: "absent")
        if (path != null) inputs.file(path)
    }
}
