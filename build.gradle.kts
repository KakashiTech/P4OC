// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    id("com.github.ben-manes.versions") version "0.53.0"
    id("nl.littlerobots.version-catalog-update") version "1.0.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.6" apply false
}

// Run ./gradlew versionCatalogUpdate to update all dependencies
// Run ./gradlew versionCatalogUpdate --interactive for interactive mode

// Configure Detekt for all modules
subprojects {
    plugins.apply("io.gitlab.arturbosch.detekt")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config = files("${rootDir}/detekt.yml")
        buildUponDefaultConfig = true
        allRules = false
        autoCorrect = false
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "17"
    }
}
