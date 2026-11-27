plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.rafael.figurinhas"
    compileSdk = 34
    defaultConfig {
        applicationId = "br.rafael.figurinhas"
        minSdk = 28
        targetSdk = 34
        // numero do build do github vira a versao, assim o android aceita atualizar por cima
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = build
        versionName = "0.$build"
    }
    // chave fixa pra cada build novo instalar por cima do anterior sem desinstalar
    signingConfigs {
        getByName("debug") {
            storeFile = file("assinatura.jks")
            storePassword = "figurinhas"
            keyAlias = "figurinhas"
            keyPassword = "figurinhas"
        }
    }
    buildTypes {
        getByName("debug") { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") }
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(bom)
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.animation:animation")
}
