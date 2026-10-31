plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.lint.api)

    testImplementation(libs.junit)
    testImplementation(libs.lint.impl)
    testImplementation(libs.lint.tests)
}

tasks.jar {
    manifest {
        attributes("Lint-Registry-v2" to "com.frankegan.sqlkite.KiteIssueRegistry")
    }
}
