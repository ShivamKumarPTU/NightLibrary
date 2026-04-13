plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-parcelize")
    alias(libs.plugins.navigation.safeargs)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.nightlibrary"
    compileSdk = 36



    defaultConfig {
        applicationId = "com.example.nightlibrary"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
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
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
        dataBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.gridlayout)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.cronet.embedded)
    implementation(libs.androidx.core.animation)
    implementation(libs.identity.jvm)
    implementation(libs.androidx.exifinterface)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.work.testing)
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.robolectric:robolectric:4.11.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.androidx.biometric)
    //lottie animation

    val lottieVersion = "6.7.0"

    implementation(libs.lottie)
    implementation(libs.androidx.security.crypto)
    // Retrofit
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.gson)

    // OkHttp
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)
    implementation(libs.okhttp.urlconnection)
    implementation(libs.androidx.room.paging)
    implementation(libs.room.ktx)
    // For Kotlin Coroutines support (recommended)
    implementation(libs.room.runtime)
    // WorkManager for background tasks
    ksp(libs.room.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
    implementation(libs.androidx.work.runtime.ktx)
// Recycler view card animaton

    implementation(libs.sqlcipher)
// media 3
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    // pdf
    implementation ("com.github.mhiew:android-pdf-viewer:3.2.0-beta.3")
    implementation(libs.youtubedl.android)
    implementation(libs.youtubedl.ffmpeg)
    implementation("androidx.paging:paging-runtime-ktx:3.2.1")
    implementation("com.github.chrisbanes:PhotoView:2.3.0")
}
