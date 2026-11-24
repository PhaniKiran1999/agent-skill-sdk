plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

// Published through JitPack from https://github.com/PhaniKiran1999/agent-skill-sdk:
//   implementation("com.github.PhaniKiran1999:agent-skill-sdk:<tag>")
// JitPack builds a git tag and exposes it as $VERSION; local builds use VERSION_NAME.
group = "com.github.PhaniKiran1999"
version = providers.environmentVariable("VERSION").orElse(providers.gradleProperty("VERSION_NAME")).get()

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

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "agent-skill-sdk"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Agent Skill SDK")
                description.set("Lets on-device AI agents discover and call tools in other Android apps over Binder.")
                url.set("https://github.com/PhaniKiran1999/agent-skill-sdk")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}

dependencies {
    api(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    // Real org.json for JVM unit tests (android.jar only has stubs).
    testImplementation(libs.json)
}
