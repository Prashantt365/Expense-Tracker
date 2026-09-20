import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.peyo.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.peyo.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Supabase, read from local.properties so the repo never carries a project of its own.
        // A clone without them builds fine and simply stays offline, which is the right outcome
        // for a fork or a CI runner that has no backend to talk to.
        //
        // The publishable key is not a secret -- it ships inside the APK and is meant to be seen.
        // Row level security on the tables is what actually keeps one account out of another's
        // data, so this is tidiness, not protection.
        val supabase = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
        }
        buildConfigField("String", "SUPABASE_URL", "\"${supabase.getProperty("supabase.url", "")}\"")
        buildConfigField(
            "String",
            "SUPABASE_ANON_KEY",
            "\"${supabase.getProperty("supabase.anonKey", "")}\""
        )
    }

    buildFeatures { compose = true; buildConfig = true }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    // Release signing is wired up only when a keystore is actually present, so a release build
    // still succeeds (unsigned) on a machine without the key. Set the four RELEASE_* properties in
    // ~/.gradle/gradle.properties -- never in the repo, where they would be committed.
    val releaseKeystore = (project.findProperty("RELEASE_STORE_FILE") as String?)
        ?.let(::file)
        ?.takeIf { it.exists() }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String?
                keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String?
                keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = releaseKeystore?.let { signingConfigs.getByName("release") }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Home screen widgets. Glance is Compose for RemoteViews: the widget is written as
    // composables against the same Room database the app reads, rather than as a layout XML plus
    // a RemoteViewsFactory, which is what keeps the widget's figures and the app's in step.
    implementation("androidx.glance:glance-appwidget:1.2.0")
    implementation("androidx.glance:glance-material3:1.2.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    // The platform's org.json is a stub in a JVM unit test, every method throwing. This is the
    // real thing, on the test classpath only, so the Supabase response parsers can be tested
    // against the payloads the server actually sends rather than only at runtime on a device.
    testImplementation("org.json:json:20250107")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
