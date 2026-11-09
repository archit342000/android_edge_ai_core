plugins {
    id("com.android.application")

}

android {
    namespace = "com.aanand.edgeaicore"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aanand.edgeaicore"
        minSdk = 26
        targetSdk = 34
        versionCode = 41
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        aidl = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        // Pre-existing issues (docs/pitfalls.md); lint fails only on new ones.
        baseline = file("lint-baseline.xml")
    }
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // LiteRT-LM
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")

    // Gson
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("org.slf4j:slf4j-android:1.7.36")

    // Ktor Server
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-netty:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-gson:2.3.12")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.ktor:ktor-server-test-host:2.3.12")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
