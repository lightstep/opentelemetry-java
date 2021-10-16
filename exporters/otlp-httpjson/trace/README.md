# OpenTelemetry - OTLP Trace Exporter - HTTP

[![Javadocs][javadoc-image]][javadoc-url]

This is the OpenTelemetry exporter, sending span data to OpenTelemetry collector via HTTP/JSON without gRPC.

[javadoc-image]: https://www.javadoc.io/badge/io.opentelemetry/opentelemetry-exporter-otlp-httpjson-trace.svg
[javadoc-url]: https://www.javadoc.io/doc/io.opentelemetry/opentelemetry-exporter-otlp-httpjson-trace

## Description

This exporter is similar in functionality to [OTLP HTTP exporter](exporters/otlp-http/) except that it populates HTTP request body with protobuf **JSON** representation rather than binary one.

The [main exporter class](exporters/otlp-httpjson/trace/src/main/java/io/opentelemetry/exporter/otlp/httpjson/trace/OtlpHttpJsonSpanExporter.java) is created by [exporter builder](java/io/opentelemetry/exporter/otlp/httpjson/trace/OtlpHttpJsonSpanExporterBuilder.java). The [exporter class export method](exporters/otlp-httpjson/trace/src/main/java/io/opentelemetry/exporter/otlp/httpjson/trace/OtlpHttpJsonSpanExporter.java) delegates request body creation to its own version of [ProtoJsonRequestBody](exporters/otlp/common/src/main/java/io/opentelemetry/exporter/otlp/internal/ProtoJsonRequestBody.java) which in turn uses [Marshaller.writeJsonTo method](exporters/otlp/common/src/main/java/io/opentelemetry/exporter/otlp/internal/Marshaler.java) to generate protobuf JSON output.

## Adding more fields in message body

Message body is automatically generated from [Span](api/all/src/main/java/io/opentelemetry/api/trace/Span.java) corresponding marshalers. Client can add unlimited number of Span attributes those will be serialized by the same process.

## STDOUT dumping option

The exporter itself does not provide dumping message body to STDOUT functionality. However, it is possible to attach [OTLP logging exporter](exporters/logging-otlp/src/main/java/io/opentelemetry/exporter/logging/otlp/OtlpJsonLoggingSpanExporter.java) to the same [SdkTracerProvider](sdk/trace/src/main/java/io/opentelemetry/sdk/trace/SdkTracerProvider.java) like below and let them work in parallel. Logging exporter uses the same set of protobuf/Span marshaller to generate the message. The only difference is that HTTP exporters wrap the list of spans into a top level element.

```
SdkTracerProvider.builder()
.addSpanProcessor(BatchSpanProcessor.builder(OtlpGrpcSpanExporter.builder().build()).build())
.addSpanProcessor(SimpleSpanProcessor.create(new LoggingSpanExporter());
```

## Unit tests

Unit tests for HTTP/JSON exporter are similar to HTTP exporter ones.

### Linux/Windows TLS certificate test problem

OKHTTP client searches HTTP request host in subjectAlternativeName certificate field  and not in commonName. It is important to use the same hostname in all test requests that was set to [subjectAlternativeNames](https://github.com/open-telemetry/opentelemetry-java/blob/9cd3f2f79b992b18e537c39db4cefd3c5b2a4e2f/exporters/otlp-http/trace/src/test/java/io/opentelemetry/exporter/otlp/http/trace/OtlpHttpSpanExporterTest.java#L69) in order for OKHTTP client properly verify the host. Previously that host was hardcoded to `"localhost"` which resolves to `"localhost"` on Linux but to `"127.0.0.1"`. That led to failed Windows tests. When hostname in all test request was replaced with correctly resolved canonical name test passed on Windows too.
[GitHub issue](https://github.com/open-telemetry/opentelemetry-java/issues/3619)

```java
      canonicalHostName = InetAddress.getByName("localhost").getCanonicalHostName();
    HELD_CERTIFICATE =
    new HeldCertificate.Builder()
    .commonName("localhost")
    .addSubjectAlternativeName(canonicalHostName)
    .build();
```

```java
  void setup() {
    builder =
        OtlpHttpJsonSpanExporter.builder()
            .setEndpoint("http://" + canonicalHostName + ":" + server.httpPort() + "/v1/traces")
            .addHeader("foo", "bar");
  }
```
## Integration test with collector

Open telemetry collector contribution project contains [example tracing collector](https://github.com/open-telemetry/opentelemetry-collector-contrib/tree/main/examples/tracing). One can verify that collector front-end port is exposed to host OS [here](https://github.com/open-telemetry/opentelemetry-collector-contrib/blob/a244c7e788eb1d6cfce9f210eb226dce8414caa8/examples/tracing/docker-compose.yml#L43). If not modify docker-compose.yml to expose it. After that [example HTTP client](https://github.com/open-telemetry/opentelemetry-java/tree/main/examples/http) on host OS would be able to connect to above port and send exported messages. These messages can be then verified on collector back-end monitoring websites locally.

## Verifying HTTP message body output

Another method to verify exporter message body output is to set up a dummy server that accepts and dumps request to STDOUT. Exporter reports error as it does not get proper collector response but dummy server still receives and dump requests.

###Sample dummy server

```bash
ncat -l -p 8080
```

#### Sample JSON output

```json
{
	"resourceSpans": [
		{
			"resource": {
				"attributes": [
					{
						"key": "service.name",
						"value": {
							"stringValue": "test_http_json"
						}
					}
				]
			},
			"instrumentationLibrarySpans": [
				{
					"instrumentationLibrary": {
						"name": "io.opentelemetry.example.http.HttpClient"
					},
					"spans": [
						{
							"traceId": "4592f2b0ea46fd30e6712cb6a69e73c6",
							"spanId": "40e3cf388832f9b8",
							"name": "/",
							"kind": "SPAN_KIND_CLIENT",
							"startTimeUnixNano": "1634067072893000000",
							"endTimeUnixNano": "1634067072901965000",
							"attributes": [
								{
									"key": "component",
									"value": {
										"stringValue": "http"
									}
								},
								{
									"key": "attribute_boolean",
									"value": {
										"boolValue": true
									}
								},
								{
									"key": "component1",
									"value": {
										"stringValue": "test_component"
									}
								},
								{
									"key": "attribute_string",
									"value": {
										"stringValue": "value_string"
									}
								},
								{
									"key": "attribute_long",
									"value": {
										"intValue": "9223372036854775807"
									}
								},
								{
									"key": "http.url",
									"value": {
										"stringValue": "http://127.0.0.1:8090"
									}
								},
								{
									"key": "http.method",
									"value": {
										"stringValue": "GET"
									}
								},
								{
									"key": "attribute_double",
									"value": {
										"doubleValue": 1.7976931348623157e308
									}
								}
							],
							"events": [],
							"links": [],
							"status": {
								"message": "test description",
								"code": "STATUS_CODE_OK"
							}
						}
					]
				}
			]
		}
	]
}
```