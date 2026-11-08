import java.io.File
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

plugins {
    id("com.android.library")
    id("com.google.devtools.ksp")
}

class RoomSchemaArgProvider(
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val schemaDirectory: File,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf("room.schemaLocation=${schemaDirectory.path}")
}

ksp {
    arg(RoomSchemaArgProvider(File(projectDir, "schemas")))
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
    testImplementation(kotlin("test-junit"))
    testImplementation("androidx.sqlite:sqlite-bundled-jvm:2.7.1")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
