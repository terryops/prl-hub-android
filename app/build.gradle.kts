import groovy.json.JsonSlurper
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

fun properties(text: String?): Properties? = text?.let { Properties().apply { load(it.reader()) } }
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// Firebase options, without the google-services plugin: app/google-services.json when present,
// otherwise firebase.properties in the repository root. Missing or incomplete → push is off in this build.
val firebaseKeys = listOf("projectId", "applicationId", "apiKey", "gcmSenderId")
val firebase: Map<String, String> = run {
    val json = providers.fileContents(layout.projectDirectory.file("google-services.json")).asText.orNull
    if (json != null) {
        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parseText(json) as Map<String, Any?>
        val project = root["project_info"] as Map<String, Any?>
        val clients = root["client"] as List<Map<String, Any?>>
        val client = clients.firstOrNull {
            val info = it["client_info"] as Map<*, *>
            (info["android_client_info"] as Map<*, *>)["package_name"] == "com.prl.wizard"
        } ?: throw GradleException("google-services.json has no Android client for com.prl.wizard")
        mapOf(
            "projectId" to project["project_id"].toString(),
            "applicationId" to (client["client_info"] as Map<*, *>)["mobilesdk_app_id"].toString(),
            "apiKey" to ((client["api_key"] as List<Map<*, *>>).first()["current_key"]).toString(),
            "gcmSenderId" to project["project_number"].toString(),
        )
    } else {
        val props = properties(providers.fileContents(rootProject.layout.projectDirectory.file("firebase.properties")).asText.orNull)
        firebaseKeys.associateWith { props?.getProperty(it)?.trim().orEmpty() }
    }
}

val keystore = properties(providers.fileContents(rootProject.layout.projectDirectory.file("keystore.properties")).asText.orNull)

android {
    namespace = "com.prl.wizard"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.prl.wizard"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "2.1.1"
        testInstrumentationRunner = providers.gradleProperty("checkRunner").getOrElse("com.prl.wizard.WidgetRenderCheck")

        buildConfigField("String", "FIREBASE_PROJECT_ID", quoted(firebase.getValue("projectId")))
        buildConfigField("String", "FIREBASE_APP_ID", quoted(firebase.getValue("applicationId")))
        buildConfigField("String", "FIREBASE_API_KEY", quoted(firebase.getValue("apiKey")))
        buildConfigField("String", "FIREBASE_SENDER_ID", quoted(firebase.getValue("gcmSenderId")))
    }

    flavorDimensions += "store"
    productFlavors {
        create("play") {
            dimension = "store"
            buildConfigField("String", "STORE", quoted("play"))
        }
        create("direct") {
            dimension = "store"
            buildConfigField("String", "STORE", quoted("direct"))
        }
    }

    // The website APK is signed with the app signing key, which Google Play also holds
    // (Play App Signing), so both channels install over each other. Play builds are uploaded with a
    // separate upload key that Google can reset. keystore.properties: storeFile/storePassword/keyAlias/
    // keyPassword (app signing key) and upload.storeFile/upload.storePassword/upload.keyAlias/
    // upload.keyPassword (upload key; without them the Play build is signed with the app signing key).
    // Debug builds keep the debug key: the debug build type's own signing config wins over a flavor's.
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
            if (keystore.getProperty("upload.storeFile") != null) {
                create("upload") {
                    storeFile = rootProject.file(keystore.getProperty("upload.storeFile"))
                    storePassword = keystore.getProperty("upload.storePassword")
                    keyAlias = keystore.getProperty("upload.keyAlias")
                    keyPassword = keystore.getProperty("upload.keyPassword")
                }
            }
        }
    }
    productFlavors {
        getByName("play") { signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.findByName("release") }
        getByName("direct") { signingConfig = signingConfigs.findByName("release") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    bundle {
        // Home-screen widgets draw in the app's language (BRIDGE.md "Widget snapshot"), which need not be
        // one of the device's languages: Play must install every language, not just the device's splits.
        language { enableSplit = false }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

// The web UI is bundled from prl-hub-web's Android build (npm --prefix web run build:android there).
// The checkout is PRL_HUB_WEB, by default the prl-hub-web directory next to this repository;
// -PwebDir names a bundle directly.
val webCheckout: File = providers.environmentVariable("PRL_HUB_WEB").map { File(it) }
    .getOrElse(rootProject.file("../prl-hub-web"))
val webDir: File = providers.gradleProperty("webDir").map { rootProject.file(it) }
    .getOrElse(File(webCheckout, "web/dist-android"))

val checkWebDir = tasks.register("checkWebDir") {
    val dir = webDir
    val checkout = webCheckout
    doLast {
        if (!File(dir, "index.html").isFile) {
            throw GradleException(
                "No web bundle at $dir (index.html missing).\n" +
                    "Build it first:  npm --prefix $checkout/web run build:android\n" +
                    "or point to one:  ./gradlew <task> -PwebDir=/absolute/path"
            )
        }
    }
}

val syncWeb = tasks.register<Sync>("syncWeb") {
    dependsOn(checkWebDir)
    from(webDir)
    into(layout.projectDirectory.dir("src/main/assets/www"))
}

tasks.named("preBuild") { dependsOn(syncWeb) }

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.core)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.work)
    implementation(libs.material)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    // Play's in-app review sheet (ReviewPrompt); the website APK has no store to rate in.
    "playImplementation"(libs.play.review)
    // Play's in-app update (StoreUpdate, the wallet's "new version" banner); the website APK reads latest.json.
    "playImplementation"(libs.play.app.update)
    "playImplementation"(libs.play.billing)

    testImplementation(libs.junit)
    // The real org.json: android.jar only has stubs, and the widget snapshot parser is unit-tested.
    testImplementation(libs.json)
}
