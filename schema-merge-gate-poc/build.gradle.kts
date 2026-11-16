plugins {
  java
}

repositories {
  mavenCentral()
}

val nessieVersion = providers.gradleProperty("nessieVersion").getOrElse("0.109.0")
val icebergVersion = providers.gradleProperty("icebergVersion").getOrElse("1.9.1")

dependencies {
  implementation("org.projectnessie.nessie:nessie-client:$nessieVersion")
  implementation("org.apache.iceberg:iceberg-api:$icebergVersion")
  implementation("org.immutables:value-annotations:2.12.3")
  annotationProcessor("org.immutables:value-processor:2.12.3")

  testImplementation(platform("org.junit:junit-bom:5.12.2"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testImplementation("org.junit.jupiter:junit-jupiter-params")
  testImplementation("org.assertj:assertj-core:3.27.7")
  testImplementation("org.mockito:mockito-core:5.21.0")
  testImplementation("org.mockito:mockito-junit-jupiter:5.21.0")
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}

tasks.test {
  useJUnitPlatform()
}
