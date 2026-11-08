plugins {
    id("com.android.application")
    id("com.diffplug.spotless")
}

android {
    namespace = "dev.ryuya.appbatterytools"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "dev.ryuya.appbatterytools"
        minSdk = 36
        targetSdk = 37
        versionCode = 9
        versionName = "0.3.0"
        testInstrumentationRunner = "dev.ryuya.appbatterytools.DiscoveryInstrumentation"
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true }
}

dependencies {
    implementation("org.luckypray:dexkit:2.3.0")
    implementation("io.github.libxposed:service:102.0.0")
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("com.google.android.material:material:1.14.0")
    testImplementation("junit:junit:4.13.2")
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktfmt("0.65").kotlinlangStyle()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktfmt("0.65").kotlinlangStyle()
    }
}

tasks.register("writeRuntimeDependencyList") {
    doLast {
        val components =
            configurations
                .getByName("releaseRuntimeClasspath")
                .resolvedConfiguration
                .resolvedArtifacts
                .map { it.moduleVersion.id.toString() }
                .toSortedSet()
        val output = layout.buildDirectory.file("runtime-dependencies.txt").get().asFile
        output.parentFile.mkdirs()
        output.writeText(components.joinToString("\n") + "\n")
    }
}
