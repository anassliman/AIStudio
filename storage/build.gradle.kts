plugins {
    id("com.android.library")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.aistudio.storage"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.room3:room3-runtime:3.0.3")
    add("kspDebug", "androidx.room3:room3-compiler:3.0.3")
    add("kspRelease", "androidx.room3:room3-compiler:3.0.3")
}
