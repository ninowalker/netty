/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.netty.handler.codec.http2;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.Future;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static io.netty.handler.codec.http.HttpMethod.*;
import static io.netty.handler.codec.http.HttpVersion.*;
import static io.netty.handler.codec.http2.Http2CodecUtil.*;
import static io.netty.handler.codec.http2.Http2TestUtil.*;
import static io.netty.util.CharsetUtil.*;
import static java.util.concurrent.TimeUnit.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Testing the {@link Http2ToHttpConnectionHandler} for {@link FullHttpRequest} objects into HTTP/2 frames
 */
public class DefaultHttp2ToHttpConnectionHandlerTest {

    @Mock
    private Http2FrameListener clientListener;

    @Mock
    private Http2FrameListener serverListener;

    private ServerBootstrap sb;
    private Bootstrap cb;
    private Channel serverChannel;
    private Channel clientChannel;
    private volatile CountDownLatch requestLatch;
    private FrameCountDown serverFrameCountDown;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    @After
    public void teardown() throws Exception {
        serverChannel.close().sync();
        Future<?> serverGroup = sb.group().shutdownGracefully(0, 0, MILLISECONDS);
        Future<?> serverChildGroup = sb.childGroup().shutdownGracefully(0, 0, MILLISECONDS);
        Future<?> clientGroup = cb.group().shutdownGracefully(0, 0, MILLISECONDS);
        serverGroup.sync();
        serverChildGroup.sync();
        clientGroup.sync();
    }

    @Test
    public void testJustHeadersRequest() throws Exception {
        bootstrapEnv(3);
        final FullHttpRequest request = new DefaultFullHttpRequest(HTTP_1_1, GET, "/example");
        try {
            final HttpHeaders httpHeaders = request.headers();
            httpHeaders.setInt(HttpUtil.ExtensionHeaderNames.STREAM_ID.text(), 5);
            httpHeaders.set(HttpHeaderNames.HOST,
                    "http://my-user_name@www.example.org:5555/example");
            httpHeaders.set(HttpUtil.ExtensionHeaderNames.AUTHORITY.text(), "www.example.org:5555");
            httpHeaders.set(HttpUtil.ExtensionHeaderNames.SCHEME.text(), "http");
            httpHeaders.add("foo", "goo");
            httpHeaders.add("foo", "goo2");
            httpHeaders.add("foo2", "goo2");
            final Http2Headers http2Headers =
                    new DefaultHttp2Headers().method(as("GET")).path(as("/example"))
                            .authority(as("www.example.org:5555")).scheme(as("http"))
                            .add(as("foo"), as("goo")).add(as("foo"), as("goo2"))
                            .add(as("foo2"), as("goo2"));
            ChannelPromise writePromise = newPromise();
            ChannelFuture writeFuture = clientChannel.writeAndFlush(request, writePromise);

            writePromise.awaitUninterruptibly(2, SECONDS);
            assertTrue(writePromise.isSuccess());
            writeFuture.awaitUninterruptibly(2, SECONDS);
            assertTrue(writeFuture.isSuccess());
            awaitRequests();
            verify(serverListener).onHeadersRead(any(ChannelHandlerContext.class), eq(5),
                    eq(http2Headers), eq(0), anyShort(), anyBoolean(), eq(0), eq(true));
            verify(serverListener, never()).onDataRead(any(ChannelHandlerContext.class), anyInt(),
                    any(ByteBuf.class), anyInt(), anyBoolean());
        } finally {
            request.release();
        }
    }

    @Test
    public void testRequestWithBody() throws Exception {
        final String text = "foooooogoooo";
        final ByteBuf data = Unpooled.copiedBuffer(text, UTF_8);
        final List<String> receivedBuffers = Collections.synchronizedList(new ArrayList<String>());
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock in) throws Throwable {
                receivedBuffers.add(((ByteBuf) in.getArguments()[2]).toString(UTF_8));
                return null;
            }
        }).when(serverListener).onDataRead(any(ChannelHandlerContext.class), eq(3),
                any(ByteBuf.class), eq(0), eq(true));
        bootstrapEnv(4);
        try {
            final HttpRequest request = new DefaultFullHttpRequest(HTTP_1_1, POST, "/example", data.retain());
            final HttpHeaders httpHeaders = request.headers();
            httpHeaders.set(HttpHeaderNames.HOST, "http://your_user-name123@www.example.org:5555/example");
            httpHeaders.add("foo", "goo");
            httpHeaders.add("foo", "goo2");
            httpHeaders.add("foo2", "goo2");
            final Http2Headers http2Headers =
                    new DefaultHttp2Headers().method(as("POST")).path(as("/example"))
                            .authority(as("www.example.org:5555")).scheme(as("http"))
                            .add(as("foo"), as("goo")).add(as("foo"), as("goo2"))
                            .add(as("foo2"), as("goo2"));
            ChannelPromise writePromise = newPromise();
            ChannelFuture writeFuture = clientChannel.writeAndFlush(request, writePromise);

            writePromise.awaitUninterruptibly(2, SECONDS);
            assertTrue(writePromise.isSuccess());
            writeFuture.awaitUninterruptibly(2, SECONDS);
            assertTrue(writeFuture.isSuccess());
            awaitRequests();
            verify(serverListener).onHeadersRead(any(ChannelHandlerContext.class), eq(3), eq(http2Headers), eq(0),
                    anyShort(), anyBoolean(), eq(0), eq(false));
            verify(serverListener).onDataRead(any(ChannelHandlerContext.class), eq(3), any(ByteBuf.class), eq(0),
                    eq(true));
            assertEquals(1, receivedBuffers.size());
            assertEquals(text, receivedBuffers.get(0));
        } finally {
            data.release();
        }
    }

    private void bootstrapEnv(int requestCountDown) throws Exception {
        requestLatch = new CountDownLatch(requestCountDown);

        sb = new ServerBootstrap();
        cb = new Bootstrap();

        sb.group(new NioEventLoopGroup(), new NioEventLoopGroup());
        sb.channel(NioServerSocketChannel.class);
        sb.childHandler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ChannelPipeline p = ch.pipeline();
                serverFrameCountDown = new FrameCountDown(serverListener, requestLatch);
                p.addLast(new Http2ToHttpConnectionHandler(true, serverFrameCountDown));
                p.addLast(ignoreSettingsHandler());
            }
        });

        cb.group(new NioEventLoopGroup());
        cb.channel(NioSocketChannel.class);
        cb.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ChannelPipeline p = ch.pipeline();
                p.addLast(new Http2ToHttpConnectionHandler(false, clientListener));
                p.addLast(ignoreSettingsHandler());
            }
        });

        serverChannel = sb.bind(new InetSocketAddress(0)).sync().channel();
        int port = ((InetSocketAddress) serverChannel.localAddress()).getPort();

        ChannelFuture ccf = cb.connect(new InetSocketAddress(NetUtil.LOCALHOST, port));
        assertTrue(ccf.awaitUninterruptibly().isSuccess());
        clientChannel = ccf.channel();
    }

    private void awaitRequests() throws Exception {
        assertTrue(requestLatch.await(2, SECONDS));
    }

    private ChannelHandlerContext ctx() {
        return clientChannel.pipeline().firstContext();
    }

    private ChannelPromise newPromise() {
        return ctx().newPromise();
    }
}
