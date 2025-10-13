plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.simonimal.navhud"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.simonimal.navhud"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        resourceConfigurations += listOf("en")
        val mapsApiKey: String = System.getenv("MAPS_API_KEY") ?: ""
        if (mapsApiKey.isBlank()) {
            println("⚠️ WARNING: MAPS_API_KEY is not set. The application will not work properly.")
        }
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true

            val keyPath = System.getenv("KEY_PATH")
            val keyStorePassword = System.getenv("KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("KEY_ALIAS")
            val keyPassword = System.getenv("KEY_PASSWORD")

            if (!keyPath.isNullOrBlank() && !keyStorePassword.isNullOrBlank() &&
            !keyAlias.isNullOrBlank() && !keyPassword.isNullOrBlank()) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(keyPath)
                    storePassword = keyStorePassword
                    this.keyAlias = keyAlias
                    this.keyPassword = keyPassword
                }
            } else {
                println("⚠️ WARNING: Signing environment variables are missing. Release build will not be signed.")
            }

            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    configurations {
        all {
            exclude(group = "com.google.android.gms", module = "play-services-maps")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    implementation("com.google.android.gms:play-services-location:21.0.1")
    implementation("com.google.maps.android:android-maps-utils:2.3.0")
    implementation("com.google.android.libraries.places:places:3.3.0")
    implementation("com.google.android.libraries.navigation:navigation:6.0.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
