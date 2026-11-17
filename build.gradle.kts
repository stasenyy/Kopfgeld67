plugins {
    java
}

group = "de.kopfgeld"
version = "1.3.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Neueste Paper-Build fuer 26.3. Falls der Wildcard nicht aufloest: konkrete Build-Nummer eintragen
    // (z.B. 26.3.build.49-alpha, siehe https://fill.papermc.io / Paper-Downloads).
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
