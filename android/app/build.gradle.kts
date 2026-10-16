plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.mood.journal"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.mood.journal"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "2.6"
    }

    // one fixed key for every build (this pc and github actions alike), so a new apk
    // always installs over the old one and keeps your data. it is the key the installed
    // app was first signed with — personal sideloaded app, not for a store, so the
    // password can live here.
    // the key file is not in the public repo — builds elsewhere (github actions) fall back to their own debug key
    val moodKey = file("mood-signing.keystore")
    signingConfigs {
        create("mood") {
            if (moodKey.exists()) {
                storeFile = moodKey
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            if (moodKey.exists()) signingConfig = signingConfigs.getByName("mood")
        }
        release {
            isMinifyEnabled = false
            if (moodKey.exists()) signingConfig = signingConfigs.getByName("mood")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// the web app is the single source of truth: ../mood.html (plus its bundled fonts/
// and vendor/ libraries) is copied into assets on every build, so the apk always
// ships the latest ui and works offline.
val copyWebApp = tasks.register<Copy>("copyWebApp") {
    from(rootProject.file("../mood.html"))
    from(rootProject.file("../fonts")) { into("fonts") }
    from(rootProject.file("../vendor")) { into("vendor") }
    into(layout.projectDirectory.dir("src/main/assets"))
}
tasks.named("preBuild") { dependsOn(copyWebApp) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
}
