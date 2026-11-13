import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.aurora.audio.lsp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aurora.audio.lsp"
        minSdk = 33
        targetSdk = 36
        versionCode = 1303
        versionName = "1.3.3"
    }

    signingConfigs {
        create("release") {
            // The password is read from local.properties (git-ignored) first, then the environment,
            // and only then falls back to the value this project has always used, so a plain
            // checkout still builds. The keystore itself lives outside the repo, which is what
            // actually keeps the upgrade channel safe; this just stops the password from being
            // the first thing anyone reads in the source.
            val localProps = Properties().apply {
                val file = rootProject.file("local.properties")
                if (file.exists()) file.inputStream().use { load(it) }
            }
            fun secret(key: String, env: String, fallback: String): String =
                localProps.getProperty(key) ?: System.getenv(env) ?: fallback.also {
                    logger.lifecycle("signing: no $key in local.properties or $env, using the default")
                }

            storeFile = file("../out/aurora-observer.jks")
            storePassword = secret("aurora.storePassword", "AURORA_STORE_PASSWORD", "android")
            keyAlias = localProps.getProperty("aurora.keyAlias") ?: "aurora-observer"
            keyPassword = secret("aurora.keyPassword", "AURORA_KEY_PASSWORD", "android")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf("META-INF/**", "kotlin/**")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(files("../xposed-api.jar"))

    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")

    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
}