plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.sanly.bahabarla"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.sanly.bahabarla"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Installable while there is no upload key yet. The real reason we
            // build release at all: debug builds force Java assertions on, and
            // mssql-jdbc trips its own assert during the TLS login handshake.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // mssql-jdbc uses java.time / java.util.function, unavailable on API 24.
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/*.kotlin_module",
                "META-INF/INDEX.LIST",
                "META-INF/{AL2.0,LGPL2.1}"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    // Direct JDBC connection to MS SQL Server from the device.
    // The optional Azure/Key Vault extras are not usable on Android.
    implementation(libs.mssql.jdbc) {
        exclude(group = "com.azure")
        exclude(group = "com.microsoft.azure")
        exclude(group = "org.bouncycastle")
        exclude(group = "org.osgi")
    }
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
