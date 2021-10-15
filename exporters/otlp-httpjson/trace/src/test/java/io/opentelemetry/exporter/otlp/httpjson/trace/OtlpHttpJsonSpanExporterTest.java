/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.exporter.otlp.httpjson.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.rpc.Status;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.mock.MockWebServerExtension;
import com.linecorp.armeria.testing.junit5.server.mock.RecordedRequest;
import io.github.netmikey.logunit.api.LogCapturer;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.exporter.otlp.internal.ProtoJsonRequestBody;
import io.opentelemetry.exporter.otlp.internal.traces.TraceRequestMarshaler;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationLibraryInfo;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.RequestBody;
import okhttp3.tls.HeldCertificate;
import okio.Buffer;
import okio.BufferedSink;
import okio.GzipSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.event.Level;
import org.slf4j.event.LoggingEvent;

class OtlpHttpJsonSpanExporterTest {

  private static final MediaType APPLICATION_JSON = MediaType.create("application", "json");
  private static final HeldCertificate HELD_CERTIFICATE;
  private static final String canonicalHostName;

  static {
    try {
      canonicalHostName = InetAddress.getByName("localhost").getCanonicalHostName();
      HELD_CERTIFICATE =
          new HeldCertificate.Builder()
              .commonName("localhost")
              .addSubjectAlternativeName(canonicalHostName)
              .build();
    } catch (UnknownHostException e) {
      throw new IllegalStateException("Error building certificate.", e);
    }
  }

  @RegisterExtension
  static MockWebServerExtension server =
      new MockWebServerExtension() {
        @Override
        protected void configureServer(ServerBuilder sb) {
          sb.http(0);
          sb.https(0);
          sb.tls(HELD_CERTIFICATE.keyPair().getPrivate(), HELD_CERTIFICATE.certificate());
        }
      };

  @RegisterExtension
  LogCapturer logs = LogCapturer.create().captureForType(OtlpHttpJsonSpanExporter.class);

  private OtlpHttpJsonSpanExporterBuilder builder;

  @BeforeEach
  void setup() {
    builder =
        OtlpHttpJsonSpanExporter.builder()
            .setEndpoint("http://localhost:" + server.httpPort() + "/v1/traces")
            .addHeader("foo", "bar");
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  void invalidConfig() {
    assertThatThrownBy(
            () -> OtlpHttpJsonSpanExporter.builder().setTimeout(-1, TimeUnit.MILLISECONDS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("timeout must be non-negative");
    assertThatThrownBy(() -> OtlpHttpJsonSpanExporter.builder().setTimeout(1, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("unit");
    assertThatThrownBy(() -> OtlpHttpJsonSpanExporter.builder().setTimeout(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("timeout");

    assertThatThrownBy(() -> OtlpHttpJsonSpanExporter.builder().setEndpoint(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("endpoint");
    assertThatThrownBy(() -> OtlpHttpJsonSpanExporter.builder().setEndpoint("😺://localhost"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid endpoint, must be a URL: 😺://localhost");
    assertThatThrownBy(() -> OtlpHttpJsonSpanExporter.builder().setEndpoint("localhost"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid endpoint, must start with http:// or https://: localhost");
    assertThatThrownBy(() -> OtlpHttpJsonSpanExporter.builder().setEndpoint("gopher://localhost"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid endpoint, must start with http:// or https://: gopher://localhost");

    assertThatThrownBy(() -> OtlpHttpJsonSpanExporter.builder().setCompression(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("compressionMethod");
    assertThatThrownBy(() -> OtlpHttpJsonSpanExporter.builder().setCompression("foo"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unsupported compression method. Supported compression methods include: gzip.");
  }

  @Test
  void testExportUncompressed() {
    server.enqueue(successResponse());
    OtlpHttpJsonSpanExporter exporter = builder.build();

    byte[] payload = exportAndAssertResult(exporter, /* expectedResult= */ true);
    RecordedRequest recorded = server.takeRequest();
    AggregatedHttpRequest request = recorded.request();
    assertRequestCommon(request);

    // verify expected and actual payload are same

    assertThat(request.content().array()).isEqualTo(payload);

    // OkHttp does not support HTTP/2 upgrade on plaintext.

    assertThat(recorded.context().sessionProtocol().isMultiplex()).isFalse();
  }

  @Test
  void testExportTls() {
    server.enqueue(successResponse());
    OtlpHttpJsonSpanExporter exporter =
        builder
            //              .setEndpoint("https://localhost:" + server.httpsPort() + "/v1/traces")
            .setEndpoint("https://" + canonicalHostName + ":" + server.httpsPort() + "/v1/traces")
            .setTrustedCertificates(
                HELD_CERTIFICATE.certificatePem().getBytes(StandardCharsets.UTF_8))
            .build();

    byte[] payload = exportAndAssertResult(exporter, /* expectedResult= */ true);
    RecordedRequest recorded = server.takeRequest();
    AggregatedHttpRequest request = recorded.request();
    assertRequestCommon(request);
    assertThat(request.content().array()).isEqualTo(payload);

    // OkHttp does support HTTP/2 upgrade on TLS.
    assertThat(recorded.context().sessionProtocol().isMultiplex()).isTrue();
  }

  @Test
  void testExportGzipCompressed() {
    server.enqueue(successResponse());
    OtlpHttpJsonSpanExporter exporter = builder.setCompression("gzip").build();

    byte[] payload = exportAndAssertResult(exporter, /* expectedResult= */ true);
    AggregatedHttpRequest request = server.takeRequest().request();
    assertRequestCommon(request);
    assertThat(request.headers().get("Content-Encoding")).isEqualTo("gzip");

    // verify expected and actual payload are same

    assertThat(gzipDecompress(request.content().array())).isEqualTo(payload);
  }

  private static void assertRequestCommon(AggregatedHttpRequest request) {
    assertThat(request.method()).isEqualTo(HttpMethod.POST);
    assertThat(request.path()).isEqualTo("/v1/traces");
    assertThat(request.headers().get("foo")).isEqualTo("bar");
    assertThat(request.headers().get("Content-Type")).isEqualTo(APPLICATION_JSON.toString());
  }

//  private static ExportTraceServiceRequest parseRequestBody(byte[] bytes) {
//    try {
//      return ExportTraceServiceRequest.parseFrom(bytes);
//    } catch (InvalidProtocolBufferException e) {
//      throw new IllegalStateException("Unable to parse Protobuf request body.", e);
//    }
//  }
//
  private static byte[] gzipDecompress(byte[] bytes) {
    try {
      Buffer result = new Buffer();
      GzipSource source = new GzipSource(new Buffer().write(bytes));
      while (source.read(result, Integer.MAX_VALUE) != -1) {}
      return result.readByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("Unable to decompress payload.", e);
    }
  }

  @Test
  void testServerError() {
    server.enqueue(
        buildResponse(
            HttpStatus.INTERNAL_SERVER_ERROR,
            Status.newBuilder().setMessage("Server error!").build()));
    OtlpHttpJsonSpanExporter exporter = builder.build();

    exportAndAssertResult(exporter, /* expectedResult= */ false);
    LoggingEvent log =
        logs.assertContains(
            "Failed to export spans. Server responded with HTTP status code 500. Error message: Server error!");
    assertThat(log.getLevel()).isEqualTo(Level.WARN);
  }

  @Test
  void testServerErrorParseError() {
    server.enqueue(
        HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, APPLICATION_JSON, "Server error!"));
    OtlpHttpJsonSpanExporter exporter = builder.build();

    exportAndAssertResult(exporter, /* expectedResult= */ false);
    LoggingEvent log =
        logs.assertContains(
            "Failed to export spans. Server responded with HTTP status code 500. Error message: Unable to parse response body, HTTP status message:");
    assertThat(log.getLevel()).isEqualTo(Level.WARN);
  }

  private static byte[] exportAndAssertResult(
      OtlpHttpJsonSpanExporter otlpHttpJsonSpanExporter, boolean expectedResult) {
    List<SpanData> spans = Collections.singletonList(generateFakeSpan());
    CompletableResultCode resultCode = otlpHttpJsonSpanExporter.export(spans);
    resultCode.join(10, TimeUnit.SECONDS);
    assertThat(resultCode.isSuccess()).isEqualTo(expectedResult);

    // create expected payload

    TraceRequestMarshaler exportRequest = TraceRequestMarshaler.create(spans);
    RequestBody requestBody = new ProtoJsonRequestBody(exportRequest);
    BufferedSink bufferedSink = new Buffer();
    try {
      requestBody.writeTo(bufferedSink);
    } catch (IOException e) {
      // should not happen - ignore
    }
    byte[] payload = bufferedSink.buffer().readByteArray();

    return payload;
  }

  private static HttpResponse successResponse() {
    ExportTraceServiceResponse exportTraceServiceResponse =
        ExportTraceServiceResponse.newBuilder().build();
    return buildResponse(HttpStatus.OK, exportTraceServiceResponse);
  }

  private static <T extends Message> HttpResponse buildResponse(HttpStatus httpStatus, T message) {
    return HttpResponse.of(httpStatus, APPLICATION_JSON, message.toByteArray());
  }

  private static SpanData generateFakeSpan() {
    long duration = TimeUnit.MILLISECONDS.toNanos(900);
    long startNs = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis());
    long endNs = startNs + duration;
    return TestSpanData.builder()
        .setHasEnded(true)
        .setSpanContext(
            SpanContext.create(
                "00000000000000000000000000abc123",
                "0000000000def456",
                TraceFlags.getDefault(),
                TraceState.getDefault()))
        .setName("GET /api/endpoint")
        .setStartEpochNanos(startNs)
        .setEndEpochNanos(endNs)
        .setStatus(StatusData.ok())
        .setKind(SpanKind.SERVER)
        .setLinks(Collections.emptyList())
        .setTotalRecordedLinks(0)
        .setTotalRecordedEvents(0)
        .setInstrumentationLibraryInfo(
            InstrumentationLibraryInfo.create("testLib", "1.0", "http://url"))
        .build();
  }
}
