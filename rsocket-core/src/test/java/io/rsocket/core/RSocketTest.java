/*
 * Copyright 2015-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.rsocket.core;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.rsocket.AbstractRSocket;
import io.rsocket.Payload;
import io.rsocket.RSocket;
import io.rsocket.buffer.LeaksTrackingByteBufAllocator;
import io.rsocket.exceptions.ApplicationErrorException;
import io.rsocket.exceptions.CustomRSocketException;
import io.rsocket.frame.decoder.PayloadDecoder;
import io.rsocket.internal.subscriber.AssertSubscriber;
import io.rsocket.lease.RequesterLeaseHandler;
import io.rsocket.lease.ResponderLeaseHandler;
import io.rsocket.test.util.LocalDuplexConnection;
import io.rsocket.test.util.TestSubscriber;
import io.rsocket.util.DefaultPayload;
import io.rsocket.util.EmptyPayload;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.Assertions;
import org.hamcrest.MatcherAssert;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExternalResource;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.mockito.ArgumentCaptor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

public class RSocketTest {

  @Rule public final SocketRule rule = new SocketRule();

  public static void assertError(String s, String mode, ArrayList<Throwable> errors) {
    for (Throwable t : errors) {
      if (t.toString().equals(s)) {
        return;
      }
    }

    Assert.fail("Expected " + mode + " connection error: " + s + " other errors " + errors.size());
  }

  @Test(timeout = 2_000)
  public void testRequestReplyNoError() {
    StepVerifier.create(rule.crs.requestResponse(DefaultPayload.create("hello")))
        .expectNextCount(1)
        .expectComplete()
        .verify();
  }

  @Test(timeout = 2000)
  public void testHandlerEmitsError() {
    rule.setRequestAcceptor(
        new AbstractRSocket() {
          @Override
          public Mono<Payload> requestResponse(Payload payload) {
            return Mono.error(new NullPointerException("Deliberate exception."));
          }
        });
    Subscriber<Payload> subscriber = TestSubscriber.create();
    rule.crs.requestResponse(EmptyPayload.INSTANCE).subscribe(subscriber);
    verify(subscriber).onError(any(ApplicationErrorException.class));

    // Client sees error through normal API
    rule.assertNoClientErrors();

    rule.assertServerError("java.lang.NullPointerException: Deliberate exception.");
  }

  @Test(timeout = 2000)
  public void testHandlerEmitsCustomError() {
    rule.setRequestAcceptor(
        new AbstractRSocket() {
          @Override
          public Mono<Payload> requestResponse(Payload payload) {
            return Mono.error(
                new CustomRSocketException(0x00000501, "Deliberate Custom exception."));
          }
        });
    Subscriber<Payload> subscriber = TestSubscriber.create();
    rule.crs.requestResponse(EmptyPayload.INSTANCE).subscribe(subscriber);
    ArgumentCaptor<CustomRSocketException> customRSocketExceptionArgumentCaptor =
        ArgumentCaptor.forClass(CustomRSocketException.class);
    verify(subscriber).onError(customRSocketExceptionArgumentCaptor.capture());

    Assert.assertEquals(
        "Deliberate Custom exception.",
        customRSocketExceptionArgumentCaptor.getValue().getMessage());
    Assert.assertEquals(0x00000501, customRSocketExceptionArgumentCaptor.getValue().errorCode());

    // Client sees error through normal API
    rule.assertNoClientErrors();

    rule.assertServerError("CustomRSocketException (0x501): Deliberate Custom exception.");
  }

  @Test(timeout = 2000)
  public void testRequestPropagatesCorrectlyForRequestChannel() {
    rule.setRequestAcceptor(
        new AbstractRSocket() {
          @Override
          public Flux<Payload> requestChannel(Publisher<Payload> payloads) {
            return Flux.from(payloads)
                // specifically limits request to 3 in order to prevent 256 request from limitRate
                // hidden on the responder side
                .limitRequest(3);
          }
        });

    Flux.range(0, 3)
        .map(i -> DefaultPayload.create("" + i))
        .as(rule.crs::requestChannel)
        .as(publisher -> StepVerifier.create(publisher, 3))
        .expectSubscription()
        .expectNextCount(3)
        .expectComplete()
        .verify(Duration.ofMillis(5000));

    rule.assertNoClientErrors();
    rule.assertNoServerErrors();
  }

  @Test(timeout = 2000)
  public void testStream() throws Exception {
    Flux<Payload> responses = rule.crs.requestStream(DefaultPayload.create("Payload In"));
    StepVerifier.create(responses).expectNextCount(10).expectComplete().verify();
  }

  @Test(timeout = 2000)
  public void testChannel() throws Exception {
    Flux<Payload> requests =
        Flux.range(0, 10).map(i -> DefaultPayload.create("streaming in -> " + i));
    Flux<Payload> responses = rule.crs.requestChannel(requests);
    StepVerifier.create(responses).expectNextCount(10).expectComplete().verify();
  }

  @Test(timeout = 2000)
  public void testErrorPropagatesCorrectly() {
    AtomicReference<Throwable> error = new AtomicReference<>();
    rule.setRequestAcceptor(
        new AbstractRSocket() {
          @Override
          public Flux<Payload> requestChannel(Publisher<Payload> payloads) {
            return Flux.from(payloads).doOnError(error::set);
          }
        });
    Flux<Payload> requests = Flux.error(new RuntimeException("test"));
    Flux<Payload> responses = rule.crs.requestChannel(requests);
    StepVerifier.create(responses).expectErrorMessage("test").verify();
    Assertions.assertThat(error.get()).isNull();
  }

  @Test
  public void requestChannelCase_StreamIsTerminatedAfterBothSidesSentCompletion1() {
    TestPublisher<Payload> requesterPublisher = TestPublisher.create();
    AssertSubscriber<Payload> requesterSubscriber = new AssertSubscriber<>(0);

    AssertSubscriber<Payload> responderSubscriber = new AssertSubscriber<>(0);
    TestPublisher<Payload> responderPublisher = TestPublisher.create();

    initRequestChannelCase(
        requesterPublisher, requesterSubscriber, responderPublisher, responderSubscriber);

    nextFromRequesterPublisher(requesterPublisher, responderSubscriber);

    completeFromRequesterPublisher(requesterPublisher, responderSubscriber);

    nextFromResponderPublisher(responderPublisher, requesterSubscriber);

    completeFromResponderPublisher(responderPublisher, requesterSubscriber);
  }

  @Test
  public void requestChannelCase_StreamIsTerminatedAfterBothSidesSentCompletion2() {
    TestPublisher<Payload> requesterPublisher = TestPublisher.create();
    AssertSubscriber<Payload> requesterSubscriber = new AssertSubscriber<>(0);

    AssertSubscriber<Payload> responderSubscriber = new AssertSubscriber<>(0);
    TestPublisher<Payload> responderPublisher = TestPublisher.create();

    initRequestChannelCase(
        requesterPublisher, requesterSubscriber, responderPublisher, responderSubscriber);

    nextFromResponderPublisher(responderPublisher, requesterSubscriber);

    completeFromResponderPublisher(responderPublisher, requesterSubscriber);

    nextFromRequesterPublisher(requesterPublisher, responderSubscriber);

    completeFromRequesterPublisher(requesterPublisher, responderSubscriber);
  }

  @Test
  public void
      requestChannelCase_CancellationFromResponderShouldLeaveStreamInHalfClosedStateWithNextCompletionPossibleFromRequester() {
    TestPublisher<Payload> requesterPublisher = TestPublisher.create();
    AssertSubscriber<Payload> requesterSubscriber = new AssertSubscriber<>(0);

    AssertSubscriber<Payload> responderSubscriber = new AssertSubscriber<>(0);
    TestPublisher<Payload> responderPublisher = TestPublisher.create();

    initRequestChannelCase(
        requesterPublisher, requesterSubscriber, responderPublisher, responderSubscriber);

    nextFromRequesterPublisher(requesterPublisher, responderSubscriber);

    cancelFromResponderSubscriber(requesterPublisher, responderSubscriber);

    nextFromResponderPublisher(responderPublisher, requesterSubscriber);

    completeFromResponderPublisher(responderPublisher, requesterSubscriber);
  }

  @Test
  public void
      requestChannelCase_CompletionFromRequesterShouldLeaveStreamInHalfClosedStateWithNextCancellationPossibleFromResponder() {
    TestPublisher<Payload> requesterPublisher = TestPublisher.create();
    AssertSubscriber<Payload> requesterSubscriber = new AssertSubscriber<>(0);

    AssertSubscriber<Payload> responderSubscriber = new AssertSubscriber<>(0);
    TestPublisher<Payload> responderPublisher = TestPublisher.create();

    initRequestChannelCase(
        requesterPublisher, requesterSubscriber, responderPublisher, responderSubscriber);

    nextFromResponderPublisher(responderPublisher, requesterSubscriber);

    completeFromResponderPublisher(responderPublisher, requesterSubscriber);

    nextFromRequesterPublisher(requesterPublisher, responderSubscriber);

    cancelFromResponderSubscriber(requesterPublisher, responderSubscriber);
  }

  @Test
  public void
      requestChannelCase_ensureThatRequesterSubscriberCancellationTerminatesStreamsOnBothSides() {
    TestPublisher<Payload> requesterPublisher = TestPublisher.create();
    AssertSubscriber<Payload> requesterSubscriber = new AssertSubscriber<>(0);

    AssertSubscriber<Payload> responderSubscriber = new AssertSubscriber<>(0);
    TestPublisher<Payload> responderPublisher = TestPublisher.create();

    initRequestChannelCase(
        requesterPublisher, requesterSubscriber, responderPublisher, responderSubscriber);

    nextFromResponderPublisher(responderPublisher, requesterSubscriber);

    nextFromRequesterPublisher(requesterPublisher, responderSubscriber);

    // ensures both sides are terminated
    cancelFromRequesterSubscriber(
        requesterPublisher, requesterSubscriber, responderPublisher, responderSubscriber);
  }

  void initRequestChannelCase(
      TestPublisher<Payload> requesterPublisher,
      AssertSubscriber<Payload> requesterSubscriber,
      TestPublisher<Payload> responderPublisher,
      AssertSubscriber<Payload> responderSubscriber) {
    rule.setRequestAcceptor(
        new AbstractRSocket() {
          @Override
          public Flux<Payload> requestChannel(Publisher<Payload> payloads) {
            payloads.subscribe(responderSubscriber);
            return responderPublisher.flux();
          }
        });

    rule.crs.requestChannel(requesterPublisher).subscribe(requesterSubscriber);

    requesterPublisher.assertWasSubscribed();
    requesterSubscriber.assertSubscribed();

    responderSubscriber.assertNotSubscribed();
    responderPublisher.assertWasNotSubscribed();

    // firstRequest
    requesterSubscriber.request(1);
    requesterPublisher.assertMaxRequested(1);
    requesterPublisher.next(DefaultPayload.create("initialData", "initialMetadata"));

    responderSubscriber.assertSubscribed();
    responderPublisher.assertWasSubscribed();
  }

  void nextFromRequesterPublisher(
      TestPublisher<Payload> requesterPublisher, AssertSubscriber<Payload> responderSubscriber) {
    // ensures that outerUpstream and innerSubscriber is not terminated so the requestChannel
    requesterPublisher.assertSubscribers(1);
    responderSubscriber.assertNotTerminated();

    responderSubscriber.request(6);
    requesterPublisher.next(
        DefaultPayload.create("d1", "m1"),
        DefaultPayload.create("d2"),
        DefaultPayload.create("d3", "m3"),
        DefaultPayload.create("d4"),
        DefaultPayload.create("d5", "m5"));

    List<Payload> innerPayloads = responderSubscriber.awaitAndAssertNextValueCount(6).values();
    Assertions.assertThat(innerPayloads.stream().map(Payload::getDataUtf8))
        .containsExactly("initialData", "d1", "d2", "d3", "d4", "d5");
    Assertions.assertThat(innerPayloads.stream().map(Payload::hasMetadata))
        .containsExactly(true, true, false, true, false, true);
    Assertions.assertThat(innerPayloads.stream().map(Payload::getMetadataUtf8))
        .containsExactly("initialMetadata", "m1", "", "m3", "", "m5");
  }

  void completeFromRequesterPublisher(
      TestPublisher<Payload> requesterPublisher, AssertSubscriber<Payload> responderSubscriber) {
    // ensures that after sending complete upstream part is closed
    requesterPublisher.complete();
    responderSubscriber.assertTerminated();
    requesterPublisher.assertNoSubscribers();
  }

  void cancelFromResponderSubscriber(
      TestPublisher<Payload> requesterPublisher, AssertSubscriber<Payload> responderSubscriber) {
    // ensures that after sending complete upstream part is closed
    responderSubscriber.cancel();
    requesterPublisher.assertWasCancelled();
    requesterPublisher.assertNoSubscribers();
  }

  void nextFromResponderPublisher(
      TestPublisher<Payload> responderPublisher, AssertSubscriber<Payload> requesterSubscriber) {
    // ensures that downstream is not terminated so the requestChannel state is half-closed
    responderPublisher.assertSubscribers(1);
    requesterSubscriber.assertNotTerminated();

    // ensures responderPublisher can send messages and outerSubscriber can receive them
    requesterSubscriber.request(5);
    responderPublisher.next(
        DefaultPayload.create("rd1", "rm1"),
        DefaultPayload.create("rd2"),
        DefaultPayload.create("rd3", "rm3"),
        DefaultPayload.create("rd4"),
        DefaultPayload.create("rd5", "rm5"));

    List<Payload> outerPayloads = requesterSubscriber.awaitAndAssertNextValueCount(5).values();
    Assertions.assertThat(outerPayloads.stream().map(Payload::getDataUtf8))
        .containsExactly("rd1", "rd2", "rd3", "rd4", "rd5");
    Assertions.assertThat(outerPayloads.stream().map(Payload::hasMetadata))
        .containsExactly(true, false, true, false, true);
    Assertions.assertThat(outerPayloads.stream().map(Payload::getMetadataUtf8))
        .containsExactly("rm1", "", "rm3", "", "rm5");
  }

  void completeFromResponderPublisher(
      TestPublisher<Payload> responderPublisher, AssertSubscriber<Payload> requesterSubscriber) {
    // ensures that after sending complete inner upstream is closed
    responderPublisher.complete();
    requesterSubscriber.assertTerminated();
    responderPublisher.assertNoSubscribers();
  }

  void cancelFromRequesterSubscriber(
      TestPublisher<Payload> requesterPublisher,
      AssertSubscriber<Payload> requesterSubscriber,
      TestPublisher<Payload> responderPublisher,
      AssertSubscriber<Payload> responderSubscriber) {
    // ensures that after sending cancel the whole requestChannel is terminated
    requesterSubscriber.cancel();
    // error should be propagated
    responderSubscriber.assertTerminated();
    responderPublisher.assertWasCancelled();
    responderPublisher.assertNoSubscribers();
    // ensures that cancellation is propagated to the actual upstream
    requesterPublisher.assertWasCancelled();
    requesterPublisher.assertNoSubscribers();
  }

  public static class SocketRule extends ExternalResource {

    DirectProcessor<ByteBuf> serverProcessor;
    DirectProcessor<ByteBuf> clientProcessor;
    private RSocketRequester crs;

    @SuppressWarnings("unused")
    private RSocketResponder srs;

    private RSocket requestAcceptor;
    private ArrayList<Throwable> clientErrors = new ArrayList<>();
    private ArrayList<Throwable> serverErrors = new ArrayList<>();

    private LeaksTrackingByteBufAllocator allocator;

    @Override
    public Statement apply(Statement base, Description description) {
      return new Statement() {
        @Override
        public void evaluate() throws Throwable {
          init();
          base.evaluate();
        }
      };
    }

    public LeaksTrackingByteBufAllocator alloc() {
      return allocator;
    }

    protected void init() {
      allocator = LeaksTrackingByteBufAllocator.instrument(ByteBufAllocator.DEFAULT);
      serverProcessor = DirectProcessor.create();
      clientProcessor = DirectProcessor.create();

      LocalDuplexConnection serverConnection =
          new LocalDuplexConnection("server", allocator, clientProcessor, serverProcessor);
      LocalDuplexConnection clientConnection =
          new LocalDuplexConnection("client", allocator, serverProcessor, clientProcessor);

      requestAcceptor =
          null != requestAcceptor
              ? requestAcceptor
              : new AbstractRSocket() {
                @Override
                public Mono<Payload> requestResponse(Payload payload) {
                  return Mono.just(payload);
                }

                @Override
                public Flux<Payload> requestStream(Payload payload) {
                  return Flux.range(1, 10)
                      .map(
                          i -> DefaultPayload.create("server got -> [" + payload.toString() + "]"));
                }

                @Override
                public Flux<Payload> requestChannel(Publisher<Payload> payloads) {
                  Flux.from(payloads)
                      .map(
                          payload ->
                              DefaultPayload.create("server got -> [" + payload.toString() + "]"))
                      .subscribe();

                  return Flux.range(1, 10)
                      .map(
                          payload ->
                              DefaultPayload.create("server got -> [" + payload.toString() + "]"));
                }
              };

      srs =
          new RSocketResponder(
              serverConnection,
              requestAcceptor,
              PayloadDecoder.DEFAULT,
              throwable -> serverErrors.add(throwable),
              ResponderLeaseHandler.None,
              0);

      crs =
          new RSocketRequester(
              clientConnection,
              PayloadDecoder.DEFAULT,
              throwable -> clientErrors.add(throwable),
              StreamIdSupplier.clientSupplier(),
              0,
              0,
              0,
              null,
              RequesterLeaseHandler.None);
    }

    public void setRequestAcceptor(RSocket requestAcceptor) {
      this.requestAcceptor = requestAcceptor;
      init();
    }

    public void assertNoErrors() {
      assertNoClientErrors();
      assertNoServerErrors();
    }

    public void assertNoClientErrors() {
      MatcherAssert.assertThat(
          "Unexpected error on the client connection.", clientErrors, is(empty()));
    }

    public void assertNoServerErrors() {
      MatcherAssert.assertThat(
          "Unexpected error on the server connection.", serverErrors, is(empty()));
    }

    public void assertClientError(String s) {
      assertError(s, "client", this.clientErrors);
    }

    public void assertServerError(String s) {
      assertError(s, "server", this.serverErrors);
    }
  }
}
