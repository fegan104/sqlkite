plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.google.ksp)
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.nexus.publish)
}

allprojects {
    version = checkNotNull(project.findProperty("VERSION_NAME")) { "VERSION_NAME not set in gradle.properties" }
}

nexusPublishing {
    packageGroup = "com.frankegan"

    repositories {
        sonatype {
            nexusUrl= uri("https://ossrh-staging-api.central.sonatype.com/service/local/")
            snapshotRepositoryUrl = uri("https://central.sonatype.com/repository/maven-snapshots/")
            username = (project.findProperty("mavenCentralUsername") as? String)
            password = (project.findProperty("mavenCentralPassword") as? String)
        }
    }
}
