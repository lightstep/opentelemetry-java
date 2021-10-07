/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.exporter.otlp.internal;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;
import org.apache.commons.io.output.CountingOutputStream;
import org.apache.commons.io.output.NullOutputStream;

import java.io.IOException;

/**
 * A {@link RequestBody} for reading from a {@link Marshaler}.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
public final class ProtoJsonRequestBody extends RequestBody {

  private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

  private final Marshaler marshaler;
//  private final int contentLength;

  /** Creates a new {@link ProtoJsonRequestBody}. */
  public ProtoJsonRequestBody(Marshaler marshaler) {
    this.marshaler = marshaler;

//    // compute content length by writing content to temporary buffer
//
//    CountingOutputStream countingOutputStream = new CountingOutputStream(new NullOutputStream());
//
//    try {
//      this.marshaler.writeJsonTo(countingOutputStream);
//    } catch (IOException e) {
//      e.printStackTrace();
//    } finally {
//      try {
//        countingOutputStream.close();
//      } catch (IOException e) {
//        e.printStackTrace();
//      }
//    }
//
//    this.contentLength = countingOutputStream.getCount();
//
  }

//  @Override
//  public long contentLength() {
//    return contentLength;
//  }
//
  @Override
  public MediaType contentType() {
    return JSON_MEDIA_TYPE;
  }

  @Override
  public void writeTo(BufferedSink bufferedSink) throws IOException {
    marshaler.writeJsonTo(bufferedSink.outputStream());
  }
}
