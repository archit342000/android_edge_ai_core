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

configurations.all {
    resolutionStrategy {
        // Ktor 2.3.12 needs coroutines < 1.11.0 (docs/pitfalls.md 26). Remove when Ktor is upgraded.
        force("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
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
    // Matches the coroutines pin above (docs/pitfalls.md 26).
    //noinspection NewerVersionAvailable
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.ktor:ktor-server-test-host:2.3.12")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
