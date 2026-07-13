plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.beta"
    compileSdk = 35

    fun configValue(name: String, fallback: String): String {
        return providers.gradleProperty(name).orNull ?: System.getenv(name) ?: fallback
    }

    fun optionalConfigValue(name: String): String {
        return providers.gradleProperty(name).orNull ?: System.getenv(name) ?: ""
    }

    fun configIntValue(name: String, fallback: Int): Int {
        return configValue(name, fallback.toString()).toIntOrNull() ?: fallback
    }

    fun buildConfigString(value: String): String {
        return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }

    val hostedBackendDefault = "https://beta-backend-staging-kvuem5t7mq-el.a.run.app"
    val feedbackApiKey = optionalConfigValue("BETA_FEEDBACK_API_KEY")
    val backendApiKey = optionalConfigValue("BETA_BACKEND_API_KEY")
    val debugBackendUrl = configValue("BETA_BACKEND_DEBUG_URL", configValue("BETA_BACKEND_RELEASE_URL", hostedBackendDefault))
    val releaseBackendUrl = configValue("BETA_BACKEND_RELEASE_URL", hostedBackendDefault)

    fun isHostedBackendUrl(url: String): Boolean {
        val normalized = url.lowercase()
        return !normalized.contains("10.0.2.2") &&
            !normalized.contains("localhost") &&
            !normalized.contains("127.0.0.1")
    }

    fun requireBackendApiKeyIfHosted(buildType: String, backendUrl: String) {
        if (isHostedBackendUrl(backendUrl) && backendApiKey.isBlank()) {
            throw org.gradle.api.GradleException(
                "BETA_BACKEND_API_KEY is required for $buildType builds that use the hosted Beta backend."
            )
        }
    }

    defaultConfig {
        applicationId = "live.betaapp.android"
        minSdk = 33
        targetSdk = 35
        versionCode = configIntValue("BETA_VERSION_CODE", 12)
        versionName = configValue("BETA_VERSION_NAME", "0.2.10")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        val storeFilePath = providers.gradleProperty("BETA_RELEASE_STORE_FILE").orNull
        if (!storeFilePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = providers.gradleProperty("BETA_RELEASE_STORE_PASSWORD").orNull
                    ?: System.getenv("BETA_RELEASE_STORE_PASSWORD")
                keyAlias = providers.gradleProperty("BETA_RELEASE_KEY_ALIAS").orNull
                    ?: System.getenv("BETA_RELEASE_KEY_ALIAS")
                keyPassword = providers.gradleProperty("BETA_RELEASE_KEY_PASSWORD").orNull
                    ?: System.getenv("BETA_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField(
                "String",
                "BETA_BACKEND_BASE_URL",
                buildConfigString(debugBackendUrl)
            )
            buildConfigField("String", "BETA_FEEDBACK_API_KEY", buildConfigString(feedbackApiKey))
            buildConfigField("String", "BETA_BACKEND_API_KEY", buildConfigString(backendApiKey))
            buildConfigField("boolean", "REQUIRE_AUTOMATION_DISCLOSURE", "false")
        }
        release {
            isMinifyEnabled = false
            buildConfigField(
                "String",
                "BETA_BACKEND_BASE_URL",
                buildConfigString(releaseBackendUrl)
            )
            buildConfigField("String", "BETA_FEEDBACK_API_KEY", buildConfigString(feedbackApiKey))
            buildConfigField("String", "BETA_BACKEND_API_KEY", buildConfigString(backendApiKey))
            buildConfigField("boolean", "REQUIRE_AUTOMATION_DISCLOSURE", "true")
            signingConfigs.findByName("release")?.let {
                signingConfig = it
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    tasks.matching { it.name == "preDebugBuild" }.configureEach {
        doFirst { requireBackendApiKeyIfHosted("debug", debugBackendUrl) }
    }
    tasks.matching { it.name == "preReleaseBuild" }.configureEach {
        doFirst { requireBackendApiKeyIfHosted("release", releaseBackendUrl) }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val startLogcatCapture by tasks.registering(Exec::class) {
    group = "verification"
    description = "Replace the latest emulator logcat file and start a fresh capture for this build."
    isIgnoreExitValue = true

    val scriptPath = rootProject.file("scripts/start-logcat-capture.ps1").absolutePath
    val projectPath = rootProject.projectDir.absolutePath

    commandLine(
        "powershell.exe",
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        scriptPath,
        "-ProjectDir",
        projectPath
    )
}

tasks.named("preBuild") {
    val autoLogcat = providers.gradleProperty("BETA_AUTO_LOGCAT").orNull
        ?: System.getenv("BETA_AUTO_LOGCAT")
    if (autoLogcat.equals("true", ignoreCase = true)) {
        dependsOn(startLogcatCapture)
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.appcompat)
    implementation(libs.material) // Add this line
    implementation(libs.mlkit.text.recognition)
    implementation(libs.okhttp3)
    implementation(libs.logging.interceptor)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)


}
