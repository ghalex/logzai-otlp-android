plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "com.logzai.otlp"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main").java.srcDir("src/main/kotlin")
        getByName("test").java.srcDir("src/test/kotlin")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(17)
}

// Coordinates, version, and POM metadata are read from gradle.properties
// (GROUP, VERSION_NAME, POM_*). The Android release variant — with sources and
// javadoc jars — is published by default.
mavenPublishing {
    publishToMavenCentral()
    // Only sign when a key is available (i.e. in CI). Lets local publishToMavenLocal
    // run unsigned for testing against other projects via mavenLocal().
    if (project.findProperty("signingInMemoryKey") != null) {
        signAllPublications()
    }
}

dependencies {
    api(platform(libs.opentelemetry.bom))
    api(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.otlp)
    implementation(libs.opentelemetry.extension.kotlin)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
}
