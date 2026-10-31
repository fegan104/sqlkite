import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.dokka)
    `maven-publish`
    signing
}

android {
    namespace = "com.frankegan.sqlkite"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,ASL2.0,LGPL2.1}"
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    api(libs.kotlin.stdlib)
    api(libs.kotlin.coroutines.android)
    api(libs.androidx.sqlite.ktx)
    implementation(libs.androidx.annotation)

    androidTestImplementation(libs.turbine)
    androidTestImplementation(libs.kotlin.coroutines.test)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.androidx.sqlite.framework)

    lintChecks(project(":sqlkite-lint"))
}

tasks.named<org.jetbrains.dokka.gradle.DokkaTask>("dokkaHtml") {
    outputDirectory.set(file("${rootDir}/docs/"))
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = "com.frankegan"
                artifactId = "sqlkite"
                version = project.findProperty("VERSION_NAME") as? String
                from(components["release"])

                pom {
                    name.set("SQL Kite")
                    description.set("A lightweight Kotlin library that makes working with SupportSQLiteOpenHelper & ContentResolver a breeze by introducing reactive Flow based queries.")
                    url.set("https://github.com/fegan104/sqlkite")
                    inceptionYear.set("2021")

                    licenses {
                        license {
                            name.set("Apache-2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }

                    developers {
                        developer {
                            id.set("frankegan")
                            name.set("Frank Egan")
                            email.set("fegan104@gmail.com")
                        }
                    }

                    scm {
                        connection.set("scm:git:git:github.com/fegan104/sqlkite.git")
                        developerConnection.set("scm:git:ssh://github.com/fegan104/sqlkite.git")
                        url.set("https://github.com/fegan104/sqlkite")
                    }
                }
            }
        }
    }

    signing {
        val keyId = System.getenv("SIGNING_KEY_ID") ?: project.findProperty("signing.keyId") as? String
        val secretKey = System.getenv("SIGNING_SECRET_KEY") ?: project.findProperty("signing.secretKey") as? String
        val password = System.getenv("SIGNING_PASSWORD") ?: project.findProperty("signing.password") as? String

        if (secretKey != null) {
            useInMemoryPgpKeys(keyId, secretKey, password)
        }

        sign(publishing.publications["release"])
    }
}
