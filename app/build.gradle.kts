plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "pe.gob.risangaraes.asistencia"
    compileSdk = 35

    defaultConfig {
        applicationId = "pe.gob.risangaraes.asistencia"
        minSdk = 24          // Android 7.0 en adelante
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // Dirección de la API que verá el personal la primera vez (se puede cambiar dentro de la app).
        // Ejemplo: "https://asistencia.risangaraes.gob.pe/api"  o  "http://192.168.1.10:8000"
        buildConfigField("String", "SERVIDOR_DEFECTO", "\"\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true   // java.time en Android 7
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Lectura (cámara) y generación de códigos QR, sin depender de Google Play ni de internet
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")
}
