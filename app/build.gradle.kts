plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Llave de Google Maps: viene del secreto MAPS_API_KEY en GitHub (o de local.properties al compilar en casa).
val mapsKey: String = System.getenv("MAPS_API_KEY")
    ?: rootProject.file("local.properties").takeIf { it.exists() }
        ?.readLines()?.firstOrNull { it.startsWith("MAPS_API_KEY=") }?.substringAfter("=")
    ?: ""

android {
    namespace = "com.rutasclientes.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rutasclientes.app"
        minSdk = 23
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
        manifestPlaceholders["MAPS_API_KEY"] = mapsKey
    }

    // Llave de firma fija (incluida en el repo) para que todas las versiones
    // se instalen una encima de otra. Su huella SHA-1 es la que se registra en
    // la llave de Google Maps: 2C:AC:C6:26:A5:C3:FE:64:A8:F7:6D:38:7A:26:3D:82:6D:6F:1F:18
    signingConfigs {
        create("fija") {
            storeFile = file("rutas.keystore")
            storePassword = "rutasclientes"
            keyAlias = "rutas"
            keyPassword = "rutasclientes"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("fija") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fija")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.gms:play-services-maps:19.0.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
