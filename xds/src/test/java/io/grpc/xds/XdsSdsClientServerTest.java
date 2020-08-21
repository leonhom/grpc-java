/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.xds.XdsClientWrapperForServerSdsTest.buildFilterChainMatch;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.BAD_CLIENT_KEY_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.BAD_CLIENT_PEM_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.BAD_SERVER_KEY_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.BAD_SERVER_PEM_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CA_PEM_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CLIENT_KEY_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CLIENT_PEM_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.SERVER_1_KEY_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.SERVER_1_PEM_FILE;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator;
import io.grpc.netty.InternalProtocolNegotiators;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil;
import io.grpc.xds.internal.sds.SdsProtocolNegotiators;
import io.grpc.xds.internal.sds.XdsChannelBuilder;
import io.grpc.xds.internal.sds.XdsServerBuilder;
import io.netty.handler.ssl.NotSslRecordException;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLHandshakeException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link XdsChannelBuilder} and {@link XdsServerBuilder} for plaintext/TLS/mTLS
 * modes.
 */
@RunWith(JUnit4.class)
public class XdsSdsClientServerTest {

  @Rule public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();
  private int port;
  private FakeNameResolverFactory fakeNameResolverFactory;

  @Before
  public void setUp() throws IOException {
    port = findFreePort();
  }

  @After
  public void tearDown() {
    if (fakeNameResolverFactory != null) {
      NameResolverRegistry.getDefaultRegistry().deregister(fakeNameResolverFactory);
    }
  }

  @Test
  public void plaintextClientServer() throws IOException, URISyntaxException {
    buildServerWithTlsContext(/* downstreamTlsContext= */ null);

    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
        getBlockingStub(/* upstreamTlsContext= */ null, /* overrideAuthority= */ null);
    assertThat(unaryRpc("buddy", blockingStub)).isEqualTo("Hello buddy");
  }

  @Test
  public void plaintextClientServer_withDefaultTlsContext() throws IOException, URISyntaxException {
    DownstreamTlsContext defaultTlsContext =
        EnvoyServerProtoData.DownstreamTlsContext.fromEnvoyProtoDownstreamTlsContext(
            io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext
                .getDefaultInstance());
    buildServerWithTlsContext(/* downstreamTlsContext= */ defaultTlsContext);

    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
            getBlockingStub(/* upstreamTlsContext= */ null, /* overrideAuthority= */ null);
    assertThat(unaryRpc("buddy", blockingStub)).isEqualTo("Hello buddy");
  }

  @Test
  public void nullFallbackProtocolNegotiator_expectException()
      throws IOException, URISyntaxException {
    buildServerWithTlsContext(/* downstreamTlsContext= */ null,
        /* fallbackProtocolNegotiator= */ null);

    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
        getBlockingStub(/* upstreamTlsContext= */ null, /* overrideAuthority= */ null);
    try {
      unaryRpc("buddy", blockingStub);
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre.getStatus().getCode()).isEqualTo(Status.UNAVAILABLE.getCode());
    }
  }

  /** TLS channel - no mTLS. */
  @Test
  public void tlsClientServer_noClientAuthentication() throws IOException, URISyntaxException {
    DownstreamTlsContext downstreamTlsContext =
        CommonTlsContextTestsUtil.buildDownstreamTlsContextFromFilenames(
            SERVER_1_KEY_FILE, SERVER_1_PEM_FILE, null);
    buildServerWithTlsContext(downstreamTlsContext);

    // for TLS, client only needs trustCa
    UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContextFromFilenames(
            /* privateKey= */ null, /* certChain= */ null, CA_PEM_FILE);

    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
        getBlockingStub(upstreamTlsContext, /* overrideAuthority= */ "foo.test.google.fr");
    assertThat(unaryRpc(/* requestMessage= */ "buddy", blockingStub)).isEqualTo("Hello buddy");
  }

  @Test
  public void requireClientAuth_noClientCert_expectException()
      throws IOException, URISyntaxException {
    DownstreamTlsContext downstreamTlsContext =
        CommonTlsContextTestsUtil.buildDownstreamTlsContextFromFilenamesWithClientCertRequired(
            SERVER_1_KEY_FILE, SERVER_1_PEM_FILE, CA_PEM_FILE);
    buildServerWithTlsContext(downstreamTlsContext);

    // for TLS, client only uses trustCa
    UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContextFromFilenames(
            /* privateKey= */ null, /* certChain= */ null, CA_PEM_FILE);

    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
        getBlockingStub(upstreamTlsContext, /* overrideAuthority= */ "foo.test.google.fr");
    try {
      unaryRpc(/* requestMessage= */ "buddy", blockingStub);
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasCauseThat().isInstanceOf(SSLHandshakeException.class);
      assertThat(sre).hasCauseThat().hasMessageThat().contains("HANDSHAKE_FAILURE");
    }
  }

  @Test
  public void noClientAuth_sendBadClientCert_passes() throws IOException, URISyntaxException {
    DownstreamTlsContext downstreamTlsContext =
        CommonTlsContextTestsUtil.buildDownstreamTlsContextFromFilenames(
            SERVER_1_KEY_FILE, SERVER_1_PEM_FILE, /* trustCa= */ null);
    buildServerWithTlsContext(downstreamTlsContext);

    UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContextFromFilenames(
            BAD_CLIENT_KEY_FILE, BAD_CLIENT_PEM_FILE, CA_PEM_FILE);

    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
        getBlockingStub(upstreamTlsContext, /* overrideAuthority= */ "foo.test.google.fr");
    assertThat(unaryRpc("buddy", blockingStub)).isEqualTo("Hello buddy");
  }

  @Test
  public void mtls_badClientCert_expectException() throws IOException, URISyntaxException {
    UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContextFromFilenames(
            BAD_CLIENT_KEY_FILE, BAD_CLIENT_PEM_FILE, CA_PEM_FILE);
    try {
      XdsClient.ListenerWatcher unused = performMtlsTestAndGetListenerWatcher(upstreamTlsContext);
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasCauseThat().isInstanceOf(SSLHandshakeException.class);
      assertThat(sre).hasCauseThat().hasMessageThat().contains("HANDSHAKE_FAILURE");
    }
  }

  /** mTLS - client auth enabled. */
  @Test
  public void mtlsClientServer_withClientAuthentication() throws IOException, URISyntaxException {
    UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContextFromFilenames(
            CLIENT_KEY_FILE, CLIENT_PEM_FILE, CA_PEM_FILE);
    XdsClient.ListenerWatcher unused = performMtlsTestAndGetListenerWatcher(upstreamTlsContext);
  }

  @Test
  public void tlsServer_plaintextClient_expectException() throws IOException, URISyntaxException {
    DownstreamTlsContext downstreamTlsContext =
        CommonTlsContextTestsUtil.buildDownstreamTlsContextFromFilenames(
            SERVER_1_KEY_FILE, SERVER_1_PEM_FILE, null);
    buildServerWithTlsContext(downstreamTlsContext);

    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
        getBlockingStub(/* upstreamTlsContext= */ null, /* overrideAuthority= */ null);
    try {
      unaryRpc("buddy", blockingStub);
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre.getStatus().getCode()).isEqualTo(Status.UNAVAILABLE.getCode());
      assertThat(sre.getStatus().getDescription()).contains("Network closed");
    }
  }

  @Test
  public void plaintextServer_tlsClient_expectException() throws IOException, URISyntaxException {
    buildServerWithTlsContext(/* downstreamTlsContext= */ null);

    // for TLS, client only needs trustCa
    UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContextFromFilenames(
            /* privateKey= */ null, /* certChain= */ null, CA_PEM_FILE);

    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
        getBlockingStub(upstreamTlsContext, /* overrideAuthority= */ "foo.test.google.fr");
    try {
      unaryRpc("buddy", blockingStub);
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasCauseThat().isInstanceOf(NotSslRecordException.class);
      assertThat(sre).hasCauseThat().hasMessageThat().contains("not an SSL/TLS record");
    }
  }

  /** mTLS - client auth enabled then update server certs to untrusted. */
  @Test
  public void mtlsClientServer_changeServerContext_expectException()
      throws IOException, URISyntaxException {
    UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContextFromFilenames(
            CLIENT_KEY_FILE, CLIENT_PEM_FILE, CA_PEM_FILE);
    XdsClient.ListenerWatcher listenerWatcher =
        performMtlsTestAndGetListenerWatcher(upstreamTlsContext);
    DownstreamTlsContext downstreamTlsContext =
        CommonTlsContextTestsUtil.buildDownstreamTlsContextFromFilenames(
            BAD_SERVER_KEY_FILE, BAD_SERVER_PEM_FILE, CA_PEM_FILE);
    XdsClientWrapperForServerSdsTest.generateListenerUpdateToWatcher(
        port, downstreamTlsContext, listenerWatcher);
    try {
      SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
          getBlockingStub(upstreamTlsContext, "foo.test.google.fr");
      assertThat(unaryRpc("buddy", blockingStub)).isEqualTo("Hello buddy");
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasCauseThat().isInstanceOf(SSLHandshakeException.class);
      assertThat(sre).hasCauseThat().hasMessageThat().isEqualTo("General OpenSslEngine problem");
    }
  }

  private XdsClient.ListenerWatcher performMtlsTestAndGetListenerWatcher(
      UpstreamTlsContext upstreamTlsContext) throws IOException, URISyntaxException {
    DownstreamTlsContext downstreamTlsContext =
        CommonTlsContextTestsUtil.buildDownstreamTlsContextFromFilenamesWithClientCertRequired(
            SERVER_1_KEY_FILE, SERVER_1_PEM_FILE, CA_PEM_FILE);

    final XdsClientWrapperForServerSds xdsClientWrapperForServerSds =
        XdsClientWrapperForServerSdsTest.createXdsClientWrapperForServerSds(
            port, /* downstreamTlsContext= */ downstreamTlsContext);
    buildServerWithFallbackProtocolNegotiator(xdsClientWrapperForServerSds,
        InternalProtocolNegotiators.serverPlaintext());

    XdsClient.ListenerWatcher listenerWatcher = xdsClientWrapperForServerSds.getListenerWatcher();

    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
        getBlockingStub(upstreamTlsContext, "foo.test.google.fr");
    assertThat(unaryRpc("buddy", blockingStub)).isEqualTo("Hello buddy");
    return listenerWatcher;
  }

  private void buildServerWithTlsContext(DownstreamTlsContext downstreamTlsContext)
      throws IOException {
    buildServerWithTlsContext(downstreamTlsContext,
        InternalProtocolNegotiators.serverPlaintext());
  }

  private void buildServerWithTlsContext(DownstreamTlsContext downstreamTlsContext,
      ProtocolNegotiator fallbackProtocolNegotiator)
      throws IOException {
    final XdsClientWrapperForServerSds xdsClientWrapperForServerSds =
        XdsClientWrapperForServerSdsTest.createXdsClientWrapperForServerSds(
            port, /* downstreamTlsContext= */ downstreamTlsContext);
    buildServerWithFallbackProtocolNegotiator(xdsClientWrapperForServerSds,
        fallbackProtocolNegotiator);
  }

  private void buildServerWithFallbackProtocolNegotiator(
      XdsClientWrapperForServerSds xdsClientWrapperForServerSds,
      ProtocolNegotiator fallbackProtocolNegotiator) throws IOException {
    SdsProtocolNegotiators.ServerSdsProtocolNegotiator serverSdsProtocolNegotiator =
        new SdsProtocolNegotiators.ServerSdsProtocolNegotiator(xdsClientWrapperForServerSds,
            fallbackProtocolNegotiator);
    buildServer(port, serverSdsProtocolNegotiator);
  }

  private void buildServer(
      int port, SdsProtocolNegotiators.ServerSdsProtocolNegotiator serverSdsProtocolNegotiator)
      throws IOException {
    XdsServerBuilder builder = XdsServerBuilder.forPort(port).addService(new SimpleServiceImpl());
    cleanupRule.register(builder.buildServer(serverSdsProtocolNegotiator)).start();
  }

  private static int findFreePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }

  static EnvoyServerProtoData.Listener buildListener(
      String name, String address, int port, DownstreamTlsContext tlsContext) {
    EnvoyServerProtoData.FilterChainMatch filterChainMatch = buildFilterChainMatch(port, address);
    EnvoyServerProtoData.FilterChain filterChain1 =
        new EnvoyServerProtoData.FilterChain(filterChainMatch, tlsContext);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(name, address, Arrays.asList(filterChain1));
    return listener;
  }

  private SimpleServiceGrpc.SimpleServiceBlockingStub getBlockingStub(
      final UpstreamTlsContext upstreamTlsContext, String overrideAuthority)
      throws URISyntaxException {
    URI expectedUri = new URI("sdstest://localhost:" + port);
    fakeNameResolverFactory = new FakeNameResolverFactory.Builder(expectedUri).build();
    NameResolverRegistry.getDefaultRegistry().register(fakeNameResolverFactory);
    XdsChannelBuilder channelBuilder =
        XdsChannelBuilder.forTarget("sdstest://localhost:" + port);
    if (overrideAuthority != null) {
      channelBuilder = channelBuilder.overrideAuthority(overrideAuthority);
    }
    InetSocketAddress socketAddress =
        new InetSocketAddress(Inet4Address.getLoopbackAddress(), port);
    Attributes attrs =
        (upstreamTlsContext != null)
            ? Attributes.newBuilder()
                .set(XdsAttributes.ATTR_UPSTREAM_TLS_CONTEXT, upstreamTlsContext)
                .build()
            : Attributes.EMPTY;
    fakeNameResolverFactory.setServers(
        ImmutableList.of(new EquivalentAddressGroup(socketAddress, attrs)));
    return SimpleServiceGrpc.newBlockingStub(cleanupRule.register(channelBuilder.build()));
  }

  /** Say hello to server. */
  private static String unaryRpc(
      String requestMessage, SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub) {
    SimpleRequest request = SimpleRequest.newBuilder().setRequestMessage(requestMessage).build();
    SimpleResponse response = blockingStub.unaryRpc(request);
    return response.getResponseMessage();
  }

  private static class SimpleServiceImpl extends SimpleServiceGrpc.SimpleServiceImplBase {

    @Override
    public void unaryRpc(SimpleRequest req, StreamObserver<SimpleResponse> responseObserver) {
      SimpleResponse response =
          SimpleResponse.newBuilder()
              .setResponseMessage("Hello " + req.getRequestMessage())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  private static final class FakeNameResolverFactory extends NameResolverProvider {
    final URI expectedUri;
    List<EquivalentAddressGroup> servers = ImmutableList.of();
    final ArrayList<FakeNameResolver> resolvers = new ArrayList<>();

    FakeNameResolverFactory(URI expectedUri) {
      this.expectedUri = expectedUri;
    }

    void setServers(List<EquivalentAddressGroup> servers) {
      this.servers = servers;
    }

    @Override
    public NameResolver newNameResolver(final URI targetUri, NameResolver.Args args) {
      if (!expectedUri.equals(targetUri)) {
        return null;
      }
      FakeNameResolver resolver = new FakeNameResolver();
      resolvers.add(resolver);
      return resolver;
    }

    @Override
    public String getDefaultScheme() {
      return "sdstest";
    }

    @Override
    protected boolean isAvailable() {
      return true;
    }

    @Override
    protected int priority() {
      return 5;
    }

    final class FakeNameResolver extends NameResolver {
      Listener2 listener;

      @Override
      public String getServiceAuthority() {
        return expectedUri.getAuthority();
      }

      @Override
      public void start(Listener2 listener) {
        this.listener = listener;
        resolved();
      }

      @Override
      public void refresh() {
        resolved();
      }

      void resolved() {
        ResolutionResult.Builder builder = ResolutionResult.newBuilder().setAddresses(servers);
        listener.onResult(builder.build());
      }

      @Override
      public void shutdown() {
      }

      @Override
      public String toString() {
        return "FakeNameResolver";
      }
    }

    static final class Builder {
      final URI expectedUri;

      Builder(URI expectedUri) {
        this.expectedUri = expectedUri;
      }

      FakeNameResolverFactory build() {
        return new FakeNameResolverFactory(expectedUri);
      }
    }
  }
}
