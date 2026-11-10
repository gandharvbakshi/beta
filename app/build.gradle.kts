plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

abstract class GenerateBackendEndpointAsset : DefaultTask() {
    @get:Input abstract val backendUrl: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun generate() {
        val target = outputDirectory.file("beta_backend_config.json").get().asFile
        target.parentFile.mkdirs()
        target.writeText(groovy.json.JsonOutput.toJson(mapOf(
            "version" to 1,
            "backend_base_url" to backendUrl.get().trim().trimEnd('/'),
        )), Charsets.UTF_8)
    }
}

// These opt-in replay tests consume local offline JSON files and write their reports in place.
// Include the selected paths in Test task inputs; when one is active, do not reuse cached results.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    // Shared source-contract fixtures live outside src/test. Their contents
    // must invalidate unit-test results just like Kotlin test sources do.
    val prospectiveMarket = providers.environmentVariable("BETA_PROSPECTIVE_MARKET_PROVIDER").orElse("")
    inputs.files(rootProject.fileTree("scripts/fixtures") {
        if (prospectiveMarket.get().isNotBlank()) include("nov07_prospective_market_manifest.json",
            "nov07_prospective_market_instructions.json", "nov07_prospective_market_shelf.json")
        else include("**/*.json")
    })
        .withPathSensitivity(PathSensitivity.RELATIVE)
    val replayFile = providers.environmentVariable("BETA_INTENT_REPLAY_FILE").orElse("")
    val replayV2File = providers.environmentVariable("BETA_INTENT_V2_REPLAY_FILE").orElse("")
    val catalogueReplayFiles = providers.environmentVariable("BETA_CATALOGUE_REPLAY_FILES").orElse("")
    inputs.property("betaIntentReplayFile", replayFile)
    inputs.property("betaIntentV2ReplayFile", replayV2File)
    inputs.property("betaCatalogueReplayFiles", catalogueReplayFiles)
    val contextDiagnosticFiles = listOf("BETA_CONTEXT_DIAGNOSTIC_FILE", "BETA_CONTEXT_RUNTIME_DIAGNOSTIC_FILE",
        "BETA_CONTEXT_RUNTIME_CASES_FILE", "BETA_CONTEXT_COMPLETE_DRAFT_FILE",
        "BETA_CONTEXT_COMPLETE_DRAFT_REPORT_FILE", "BETA_CONTEXT_NAME_READING_FILE",
        "BETA_CONTEXT_NAME_READING_REPORT_FILE", "BETA_CONTEXT_PHONE_REPLAY_FILES",
        "BETA_INTERPRETED_REPLAY_FILE", "BETA_INTERPRETED_REPLAY_CASES_FILE", "BETA_CONTROLLED_SHELF_PROVIDER",
        "BETA_CONTROLLED_SHELF_RUN", "BETA_PROSPECTIVE_MARKET_PROVIDER", "BETA_PROSPECTIVE_MARKET_RUN",
        "BETA_PROSPECTIVE_MARKET_MANIFEST_SHA256", "BETA_PROSPECTIVE_MARKET_MODEL_SHA256",
        "BETA_PROSPECTIVE_MARKET_BASELINE_SHA256"
    ).associateWith { providers.environmentVariable(it).orElse("") }
    contextDiagnosticFiles.forEach { (name, path) -> inputs.property(name, path) }

    val hasOfflineReplay = replayFile.map(String::isNotBlank)
        .zip(replayV2File.map(String::isNotBlank)) { legacy, v2 -> legacy || v2 }
        .zip(catalogueReplayFiles.map(String::isNotBlank)) { intentReplay, catalogueReplay -> intentReplay || catalogueReplay }
        .map { replay -> replay || contextDiagnosticFiles.values.any { it.get().isNotBlank() } }
    outputs.upToDateWhen { !hasOfflineReplay.get() }
    outputs.doNotCacheIf("Offline replay tests consume local files and update reports") { hasOfflineReplay.get() }
}

android {
    namespace = "com.example.beta"
    compileSdk = 36

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
    val intentV2Enabled = configValue("BETA_INTENT_V2_ENABLED", "false").equals("true", ignoreCase = true)
    val intentContextEnabled = configValue("BETA_INTENT_CONTEXT_ENABLED", "false").equals("true", ignoreCase = true)
    val intentInterpretedEnabled = configValue("BETA_INTENT_INTERPRETED_ENABLED", "false").equals("true", ignoreCase = true)
    val publicOpenAiEnabled = configValue("BETA_INTENT_PUBLIC_OPENAI_ENABLED", "false").equals("true", ignoreCase = true)
    if (listOf(intentV2Enabled, intentContextEnabled, intentInterpretedEnabled).count { it } > 1) {
        throw org.gradle.api.GradleException("BETA_INTENT_V2_ENABLED, BETA_INTENT_CONTEXT_ENABLED, and BETA_INTENT_INTERPRETED_ENABLED are mutually exclusive.")
    }
    if (publicOpenAiEnabled && !intentInterpretedEnabled) {
        throw org.gradle.api.GradleException("BETA_INTENT_PUBLIC_OPENAI_ENABLED requires BETA_INTENT_INTERPRETED_ENABLED.")
    }
    val debugBackendUrl = configValue("BETA_BACKEND_DEBUG_URL", configValue("BETA_BACKEND_RELEASE_URL", hostedBackendDefault))
    val releaseBackendUrl = configValue("BETA_BACKEND_RELEASE_URL", hostedBackendDefault)

    androidComponents.onVariants { variant ->
        val generateEndpoint = tasks.register<GenerateBackendEndpointAsset>(
            "generate${variant.name.replaceFirstChar(Char::uppercaseChar)}BackendEndpointAsset"
        ) {
            backendUrl.set(if (variant.buildType == "release") releaseBackendUrl else debugBackendUrl)
            outputDirectory.set(layout.buildDirectory.dir("generated/backendEndpoint/${variant.name}"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(generateEndpoint, GenerateBackendEndpointAsset::outputDirectory)
    }

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
        // Explicit, OFF-default contract rollout. Never silently negotiate down.
        buildConfigField("boolean", "BETA_INTENT_V2_ENABLED", intentV2Enabled.toString())
        buildConfigField("boolean", "BETA_INTENT_CONTEXT_ENABLED", intentContextEnabled.toString())
        buildConfigField("boolean", "BETA_INTENT_INTERPRETED_ENABLED", intentInterpretedEnabled.toString())
        buildConfigField("boolean", "BETA_INTENT_PUBLIC_OPENAI_ENABLED", publicOpenAiEnabled.toString())
        // Owner approved checkout release; server retains an emergency kill switch.
        buildConfigField("boolean", "BETA_SWIGGY_CHECKOUT_ENABLED",
            configValue("BETA_SWIGGY_CHECKOUT_ENABLED", "true").equals("true", ignoreCase = true).toString())
        applicationId = "live.betaapp.android"
        minSdk = 33
        targetSdk = 36
        versionCode = configIntValue("BETA_VERSION_CODE", 21)
        versionName = configValue("BETA_VERSION_NAME", "0.3.4")

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
            buildConfigField("String", "BETA_FEEDBACK_API_KEY", buildConfigString(feedbackApiKey))
            buildConfigField("String", "BETA_BACKEND_API_KEY", buildConfigString(backendApiKey))
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "BETA_FEEDBACK_API_KEY", buildConfigString(feedbackApiKey))
            buildConfigField("String", "BETA_BACKEND_API_KEY", buildConfigString(backendApiKey))
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

val verifyReleaseManifestPolicy by tasks.registering {
    group = "verification"
    description = "Fails release packaging if legacy screen-access/provider or advertising-ID declarations return."
    dependsOn("processApplicationManifestReleaseForBundle")

    val manifestFile = layout.buildDirectory.file(
        "intermediates/bundle_manifest/release/processApplicationManifestReleaseForBundle/AndroidManifest.xml"
    )
    inputs.file(manifestFile)

    doLast {
        val manifest = manifestFile.get().asFile.readText()
        val forbidden = listOf(
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            "com.google.android.gms.permission.AD_ID",
            "android.permission.ACCESS_ADSERVICES_AD_ID",
            "MyAccessibilityService",
            "ScreenCaptureService",
            "com.grofers.customerapp",
            "com.zeptoconsumerapp",
            "com.zepto.customer",
        ).filter(manifest::contains)
        check(forbidden.isEmpty()) {
            "Release manifest contains forbidden legacy or advertising declarations: ${forbidden.joinToString()}"
        }
    }
}

tasks.matching { it.name == "bundleRelease" }.configureEach {
    dependsOn(verifyReleaseManifestPolicy)
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
    implementation(libs.okhttp3)
    implementation(libs.logging.interceptor)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-crashlytics-ktx")

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
