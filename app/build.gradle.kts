import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

// Read only public connection settings. Never evaluate shell expressions or copy
// credentials from .env into BuildConfig, resources or packaged assets.
val publicNames = setOf(
    "TEST_SUPABASE_URL", "TEST_SUPABASE_PUBLISHABLE_KEY",
    "PRESENTATION_SUPABASE_URL", "PRESENTATION_SUPABASE_PUBLISHABLE_KEY",
)
fun dotenvValue(raw: String, lineNumber: Int): String {
    val result = StringBuilder()
    var quote: Char? = null
    var ended = false
    var index = 0
    fun invalid(): Nothing = error("Invalid public .env entry at line $lineNumber; use one quoted or unquoted value")
    while (index < raw.length) {
        val char = raw[index++]
        when {
            quote == '\'' -> if (char == '\'') quote = null else result.append(char)
            quote == '"' -> when {
                char == '"' -> quote = null
                char == '\\' && index < raw.length && raw[index] in listOf('"', '\\') -> result.append(raw[index++])
                else -> result.append(char)
            }
            char == '#' -> break
            char.isWhitespace() -> ended = true
            ended -> invalid()
            char == '\'' || char == '"' -> quote = char
            char == '\\' -> {
                if (index == raw.length) invalid()
                result.append(raw[index++])
            }
            else -> result.append(char)
        }
    }
    if (quote != null) invalid()
    return result.toString()
}
val dotenv = mutableMapOf<String, String>()
// Provider-backed file reads are tracked by Gradle, including configuration-cache
// invalidation when .env changes between IDE builds.
providers.fileContents(rootProject.layout.projectDirectory.file(".env")).asText.orNull
    ?.lineSequence()?.forEachIndexed { index, line ->
        val name = line.substringBefore('=').trim()
        if ('=' in line && name in publicNames) {
            dotenv[name] = dotenvValue(line.substringAfter('=').trimStart(), index + 1)
        }
    }
fun publicConfig(name: String): String {
    require(name in publicNames)
    val value = providers.environmentVariable(name).orNull?.takeIf { it.isNotEmpty() }
        ?: dotenv[name] ?: local.getProperty(name, "")
    require(!name.endsWith("PUBLISHABLE_KEY") || value.isEmpty() || value.startsWith("sb_publishable_")) {
        "$name must be a publishable key, never a privileged secret"
    }
    require(!name.endsWith("_URL") || value.isEmpty() || value.startsWith("https://")) {
        "$name must use HTTPS"
    }
    require(value.none { it.isISOControl() }) { "$name must not contain control characters" }
    return value.replace("\\", "\\\\").replace("\"", "\\\"")
}

android {
    namespace = "es.teloarreglo"
    compileSdk = 35
    defaultConfig {
        applicationId = "es.teloarreglo"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    flavorDimensions += "environment"
    productFlavors {
        create("pruebas") {
            dimension = "environment"
            applicationIdSuffix = ".pruebas"
            buildConfigField("String", "SUPABASE_URL", "\"${publicConfig("TEST_SUPABASE_URL")}\"")
            buildConfigField("String", "SUPABASE_KEY", "\"${publicConfig("TEST_SUPABASE_PUBLISHABLE_KEY")}\"")
        }
        create("presentacion") {
            dimension = "environment"
            applicationIdSuffix = ".presentacion"
            buildConfigField("String", "SUPABASE_URL", "\"${publicConfig("PRESENTATION_SUPABASE_URL")}\"")
            buildConfigField("String", "SUPABASE_KEY", "\"${publicConfig("PRESENTATION_SUPABASE_PUBLISHABLE_KEY")}\"")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation(platform("io.github.jan-tennert.supabase:bom:3.1.4"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:storage-kt")
    implementation("io.coil-kt.coil3:coil-compose:3.1.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.1.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("io.ktor:ktor-client-okhttp:3.1.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-client-mock:3.1.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.05.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("io.ktor:ktor-client-mock:3.1.3")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
