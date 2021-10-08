/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.exporter.otlp.internal;

import java.io.IOException;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;

/**
 * A {@link RequestBody} for reading from a {@link Marshaler}.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
public final class ProtoJsonRequestBody extends RequestBody {

  private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

  private final Marshaler marshaler;

  /** Creates a new {@link ProtoJsonRequestBody}. */
  public ProtoJsonRequestBody(Marshaler marshaler) {
    this.marshaler = marshaler;

  }

  @Override
  public MediaType contentType() {
    return JSON_MEDIA_TYPE;
  }

  @Override
  public void writeTo(BufferedSink bufferedSink) throws IOException {
    marshaler.writeJsonTo(bufferedSink.outputStream());
  }
}
