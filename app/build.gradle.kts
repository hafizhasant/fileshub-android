plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "org.fileshub.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.fileshub.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.1"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources { excludes += listOf("META-INF/versions/**", "META-INF/*.md", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST", "META-INF/AL2.0", "META-INF/LGPL2.1", "OSGI-INF/**") } }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("commons-net:commons-net:3.11.1")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
