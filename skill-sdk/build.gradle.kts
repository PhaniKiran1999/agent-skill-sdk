plugins {
    alias(libs.plugins.android.library)
}

// Consumers depend on "com.kiran.agentskills:skill-sdk". In a composite build
// (includeBuild("../agent-skill-sdk")) Gradle substitutes this project automatically.
group = "com.kiran.agentskills"
version = "0.1.0"

android {
    namespace = "com.kiran.agentskills.sdk"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    // Real org.json for JVM unit tests (android.jar only has stubs).
    testImplementation(libs.json)
}
