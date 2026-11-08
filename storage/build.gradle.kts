plugins {
    id("com.android.library")
    id("com.google.devtools.ksp")
}

abstract class RoomSchemaArgProvider(
    @get:org.gradle.api.tasks.InputDirectory
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    val schemaDirectory: java.io.File,
) : org.gradle.process.CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf("room.schemaLocation=${schemaDirectory.absolutePath}")
}

ksp {
    arg(RoomSchemaArgProvider(java.io.File(projectDir, "schemas")))
}

android {
    namespace = "com.aistudio.storage"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.room3:room3-runtime:3.0.3")
    implementation("androidx.sqlite:sqlite:2.7.1")
    implementation("androidx.sqlite:sqlite-framework:2.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    add("kspDebug", "androidx.room3:room3-compiler:3.0.3")
    add("kspRelease", "androidx.room3:room3-compiler:3.0.3")

    testImplementation(kotlin("test"))
    testImplementation("androidx.sqlite:sqlite-bundled:2.7.1")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
