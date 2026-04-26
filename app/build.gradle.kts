import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun prop(name: String): String? =
    (project.findProperty(name) as String?) ?: localProps.getProperty(name)

fun escapeBuildConfig(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

val notionToken = prop("NOTION_TOKEN") ?: ""
val notionVersion = prop("NOTION_VERSION") ?: ""
val notionDataSourceId = prop("NOTION_DATA_SOURCE_ID") ?: ""

android {
    namespace = "com.nshell.nsplayer"
    compileSdk {
        version = release(36)
    }
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.nshell.nsplayer"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("String", "NOTION_TOKEN", "\"${escapeBuildConfig(notionToken)}\"")
            buildConfigField("String", "NOTION_VERSION", "\"${escapeBuildConfig(notionVersion)}\"")
            buildConfigField(
                "String",
                "NOTION_DATA_SOURCE_ID",
                "\"${escapeBuildConfig(notionDataSourceId)}\""
            )
        }
        release {
            buildConfigField("String", "NOTION_TOKEN", "\"\"")
            buildConfigField("String", "NOTION_VERSION", "\"${escapeBuildConfig(notionVersion)}\"")
            buildConfigField(
                "String",
                "NOTION_DATA_SOURCE_ID",
                "\"${escapeBuildConfig(notionDataSourceId)}\""
            )
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation(libs.glide)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.play.services.ads)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
