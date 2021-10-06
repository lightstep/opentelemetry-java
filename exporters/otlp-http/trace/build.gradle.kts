plugins {
  id("otel.java-conventions")
  id("otel.publish-conventions")

  id("otel.animalsniffer-conventions")
}

tasks.withType<Test> {
  this.testLogging {
    outputs.upToDateWhen { false }
    this.showStandardStreams = true
  }
}

description = "OpenTelemetry Protocol HTTP Trace Exporter"
otelJava.moduleName.set("io.opentelemetry.exporter.otlp.http.trace")

dependencies {
  api(project(":sdk:trace"))

  implementation("com.fasterxml.jackson.core:jackson-core")

  implementation(project(":exporters:otlp:common"))

  implementation("com.squareup.okhttp3:okhttp")
  implementation("com.squareup.okio:okio")

  testImplementation(project(":proto"))
  testImplementation(project(":sdk:testing"))

  testImplementation("com.google.api.grpc:proto-google-common-protos")
  testImplementation("com.linecorp.armeria:armeria-junit5")
  testImplementation("com.squareup.okhttp3:okhttp-tls")
}
