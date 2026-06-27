plugins {
    kotlin("jvm") version "1.9.22"
}

group = "com.neko233"
version = "1.0.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

sourceSets {
    main {
        kotlin.srcDir("src/main/kotlin")
    }
    test {
        kotlin.srcDir("src/test/kotlin")
    }
}

tasks.test {
    useJUnitPlatform()
}
