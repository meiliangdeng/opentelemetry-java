/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.extension.trace.jaeger.sampler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.common.io.Closer;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.sdk.extension.trace.jaeger.proto.api_v2.Sampling;
import io.opentelemetry.sdk.extension.trace.jaeger.proto.api_v2.Sampling.RateLimitingSamplingStrategy;
import io.opentelemetry.sdk.extension.trace.jaeger.proto.api_v2.Sampling.SamplingStrategyParameters;
import io.opentelemetry.sdk.extension.trace.jaeger.proto.api_v2.Sampling.SamplingStrategyType;
import io.opentelemetry.sdk.extension.trace.jaeger.proto.api_v2.SamplingManagerGrpc;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

class JaegerRemoteSamplerTest {

  private static final String SERVICE_NAME = "my-service";
  private static final int RATE = 999;

  private final String serverName = InProcessServerBuilder.generateName();
  private final ManagedChannel inProcessChannel =
      InProcessChannelBuilder.forName(serverName).directExecutor().build();

  private final SamplingManagerGrpc.SamplingManagerImplBase service =
      mock(
          SamplingManagerGrpc.SamplingManagerImplBase.class,
          delegatesTo(new MockSamplingManagerService()));

  static class MockSamplingManagerService extends SamplingManagerGrpc.SamplingManagerImplBase {

    @Override
    public void getSamplingStrategy(
        Sampling.SamplingStrategyParameters request,
        StreamObserver<Sampling.SamplingStrategyResponse> responseObserver) {

      Sampling.SamplingStrategyResponse response =
          Sampling.SamplingStrategyResponse.newBuilder()
              .setStrategyType(SamplingStrategyType.RATE_LIMITING)
              .setRateLimitingSampling(
                  RateLimitingSamplingStrategy.newBuilder().setMaxTracesPerSecond(RATE).build())
              .build();

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  private final Closer closer = Closer.create();

  @BeforeEach
  public void before() throws IOException {
    Server server =
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(service)
            .build()
            .start();
    closer.register(server::shutdownNow);
    closer.register(inProcessChannel::shutdownNow);
  }

  @AfterEach
  void tearDown() throws Exception {
    closer.close();
  }

  @Test
  void connectionWorks() {
    ArgumentCaptor<SamplingStrategyParameters> requestCaptor =
        ArgumentCaptor.forClass(Sampling.SamplingStrategyParameters.class);

    JaegerRemoteSampler sampler =
        JaegerRemoteSampler.builder()
            .setChannel(inProcessChannel)
            .setServiceName(SERVICE_NAME)
            .build();

    await().atMost(Duration.ofSeconds(10)).until(samplerIsType(sampler, RateLimitingSampler.class));

    // verify
    verify(service).getSamplingStrategy(requestCaptor.capture(), ArgumentMatchers.any());
    assertThat(requestCaptor.getValue().getServiceName()).isEqualTo(SERVICE_NAME);
    assertThat(sampler.getSampler()).isInstanceOf(RateLimitingSampler.class);
    assertThat(((RateLimitingSampler) sampler.getSampler()).getMaxTracesPerSecond())
        .isEqualTo(RATE);
  }

  @Test
  void description() {
    JaegerRemoteSampler sampler =
        JaegerRemoteSampler.builder()
            .setChannel(inProcessChannel)
            .setServiceName(SERVICE_NAME)
            .build();
    assertThat(sampler.getDescription())
        .isEqualTo("JaegerRemoteSampler{TraceIdRatioBased{0.001000}}");

    // wait until the sampling strategy is retrieved before exiting test method
    await().atMost(Duration.ofSeconds(10)).until(samplerIsType(sampler, RateLimitingSampler.class));
  }

  static Callable<Boolean> samplerIsType(
      final JaegerRemoteSampler sampler, final Class<? extends Sampler> expected) {
    return () -> sampler.getSampler().getClass().equals(expected);
  }
}