plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "net.espargos.espsdr"
    compileSdk = 34
    defaultConfig { applicationId = "net.espargos.espsdr"; minSdk = 24; targetSdk = 34; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
}
