import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val ciSigningPropertiesFile = rootProject.file("signing/ci-signing.properties")
val ciKeystoreFile = rootProject.file("signing/manjuan-ci.jks")

if (!ciSigningPropertiesFile.isFile) {
    throw GradleException(
        "Missing CI signing properties: ${ciSigningPropertiesFile.path}. " +
            "Debug APKs must be signed with the committed keystore. See signing/README.md.",
    )
}
if (!ciKeystoreFile.isFile) {
    throw GradleException(
        "Missing CI keystore: ${ciKeystoreFile.path}. " +
            "Debug APKs must be signed with the committed keystore. See signing/README.md.",
    )
}

val ciSigningProperties = Properties().apply {
    ciSigningPropertiesFile.inputStream().use { load(it) }
}

fun requireCiSigningProperty(name: String): String {
    val value = ciSigningProperties.getProperty(name)?.trim().orEmpty()
    if (value.isEmpty()) {
        throw GradleException(
            "signing/ci-signing.properties is missing required property '$name'. See signing/README.md.",
        )
    }
    return value
}

android {
    namespace = "com.numbear.manjuan"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.numbear.manjuan"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("ci") {
            storeFile = ciKeystoreFile
            storePassword = requireCiSigningProperty("storePassword")
            keyAlias = requireCiSigningProperty("keyAlias")
            keyPassword = requireCiSigningProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            // Stable sideload key. Do not fall back to ~/.android/debug.keystore.
            signingConfig = signingConfigs.getByName("ci")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.navigation.compose)
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
}
