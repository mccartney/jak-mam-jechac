plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The nearest vX.Y.Z tag names the build; the commit count orders it (versionCode),
// so a release is "newer" iff it was cut from later history. Falls back off-git.
fun git(vararg args: String): String? = runCatching {
    providers.exec { commandLine("git", *args) }.standardOutput.asText.get().trim()
}.getOrNull()

android {
    namespace = "pl.waw.oledzki.jmj"
    compileSdk = 34

    defaultConfig {
        applicationId = "pl.waw.oledzki.jmj"
        minSdk = 29        // Android 10
        targetSdk = 34
        versionCode = git("rev-list", "--count", "HEAD")?.toInt() ?: 1
        versionName = git("describe", "--tags", "--match", "v*", "--dirty")?.removePrefix("v") ?: "0.0.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release key comes from the environment (CI secrets); without it the release APK is unsigned.
    signingConfigs {
        create("release") {
            storeFile = System.getenv("JMJ_KEYSTORE")?.let(::file)
            storePassword = System.getenv("JMJ_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("JMJ_KEY_ALIAS")
            keyPassword = System.getenv("JMJ_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            if (System.getenv("JMJ_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.maplibre)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.json) // real org.json on the JVM test classpath (android.jar's is a stub)
}
