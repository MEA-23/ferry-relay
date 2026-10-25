import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Release signing lives outside the repo; android/keystore.properties points at it.
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

// Firebase Cloud Messaging is optional: put your Firebase project's google-services.json in this
// folder to enable it. It's read here rather than by the google-services plugin, so builds without
// one still work (receivers then use UnifiedPush or polling).
val firebase: Map<String, String> = file("google-services.json").takeIf { it.exists() }?.let { f ->
    @Suppress("UNCHECKED_CAST")
    fun Any?.obj() = this as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    fun Any?.list() = this as List<Any?>
    val json = groovy.json.JsonSlurper().parse(f).obj()
    val project = json["project_info"].obj()
    val client = json["client"].list().map { it.obj() }.first {
        it["client_info"].obj()["android_client_info"].obj()["package_name"] == "tech.thedevlab.ferry"
    }
    mapOf(
        "FCM_PROJECT_ID" to project["project_id"].toString(),
        "FCM_SENDER_ID" to project["project_number"].toString(),
        "FCM_APP_ID" to client["client_info"].obj()["mobilesdk_app_id"].toString(),
        "FCM_API_KEY" to client["api_key"].list().first().obj()["current_key"].toString(),
    )
} ?: emptyMap()

android {
    namespace = "tech.thedevlab.ferry"
    compileSdk = 37

    defaultConfig {
        applicationId = "tech.thedevlab.ferry"
        // Android 6, so an old spare phone can relay (it only needs to be a node).
        minSdk = 23
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        buildConfigField("String", "DEFAULT_SERVER", "\"https://ferry.thedevlab.cloud\"")
        for (key in listOf("FCM_PROJECT_ID", "FCM_SENDER_ID", "FCM_APP_ID", "FCM_API_KEY")) {
            buildConfigField("String", key, "\"${firebase[key].orEmpty()}\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time below Android 8.
        isCoreLibraryDesugaringEnabled = true
    }

    lint {
        // Version checks belong to dependency updates, not to every build.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":crypto"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.splashscreen)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher)
    implementation(libs.unifiedpush)
    implementation(libs.firebase.messaging)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.okhttp.tls)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
