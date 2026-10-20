import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.time.Duration

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  // Release Version Management (V1.0.0 Semantic Baseline + Monotonic versionCode > V1.6's 6)
  // Authoritative release versions are injected by GitHub Actions via -PreleaseVersionName / -PreleaseVersionCode
  // (or RELEASE_VERSION_NAME / RELEASE_VERSION_CODE env vars) ONLY when building a new APK release.
  val defaultVersionName = "1.0.0"
  val minimumMonotonicVersionCode = 7 // V1.6 used versionCode = 6; never reset to 1
  val configuredVersionName = (project.findProperty("releaseVersionName") as? String)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: System.getenv("RELEASE_VERSION_NAME")?.trim()?.takeIf { it.isNotEmpty() }
    ?: defaultVersionName

  val configuredVersionCode = ((project.findProperty("releaseVersionCode") as? String)?.trim()?.toIntOrNull()
    ?: System.getenv("RELEASE_VERSION_CODE")?.trim()?.toIntOrNull()
    ?: minimumMonotonicVersionCode).coerceAtLeast(minimumMonotonicVersionCode)

  val versionParts = configuredVersionName.split(".")
  val majorPart = versionParts.getOrNull(0)?.toIntOrNull() ?: 1
  val minorPart = versionParts.getOrNull(1)?.toIntOrNull() ?: 0
  val patchPart = versionParts.getOrNull(2)?.toIntOrNull() ?: 0
  val nextPatchVersionName = "$majorPart.$minorPart.${patchPart + 1}"

  defaultConfig {
    applicationId = "com.aistudio.familytasks.qkxmva"
    minSdk = 24
    targetSdk = 36
    versionCode = configuredVersionCode
    versionName = configuredVersionName

    buildConfigField("String", "NEXT_RELEASE_VERSION_NAME", "\"$nextPatchVersionName\"")
    buildConfigField("int", "NEXT_RELEASE_VERSION_CODE", "${configuredVersionCode + 1}")

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  // Firestore cloud data synchronization & Firebase Cloud Messaging (FCM)
<<<<<<< HEAD
  // Note: Actual family files and folders are stored exclusively in Google Drive (FamilyTasks folder).
  implementation(libs.firebase.firestore)
  implementation(libs.firebase.messaging)
  // implementation(libs.firebase.storage)
=======
  implementation(libs.firebase.firestore)
  implementation(libs.firebase.messaging)
>>>>>>> 320e60b23f369c15ccec4e8f269a67497376db6b

  // Firebase Auth, Google Sign-In via Credential Manager, and Google Drive OAuth Authorization
  implementation(libs.firebase.auth)
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.play.services)
  implementation(libs.googleid)
  implementation(libs.play.services.auth)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

tasks.withType<Test> {
  failFast = true
  timeout.set(Duration.ofMinutes(2))
  testLogging {
    events("passed", "failed", "skipped")
  }
}
