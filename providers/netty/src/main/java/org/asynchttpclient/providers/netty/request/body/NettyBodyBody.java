/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.providers.netty.request.body;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Body;
import org.asynchttpclient.BodyGenerator;
import org.asynchttpclient.RandomAccessBody;
import org.asynchttpclient.providers.netty.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.providers.netty.channel.ChannelManager;
import org.asynchttpclient.providers.netty.channel.Channels;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.request.ProgressListener;
import org.asynchttpclient.providers.netty.request.body.FeedableBodyGenerator.FeedListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelProgressiveFuture;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedWriteHandler;

import java.io.IOException;

public class NettyBodyBody implements NettyBody {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyBodyBody.class);

    private final Body body;
    private final NettyAsyncHttpProviderConfig nettyConfig;

    public NettyBodyBody(Body body, NettyAsyncHttpProviderConfig nettyConfig) {
        this.body = body;
        this.nettyConfig = nettyConfig;
    }

    public Body getBody() {
        return body;
    }

    @Override
    public long getContentLength() {
        return body.getContentLength();
    }

    @Override
    public String getContentType() {
        return null;
    };

    @Override
    public void write(final Channel channel, NettyResponseFuture<?> future, AsyncHttpClientConfig config) throws IOException {

        Object msg;
        if (!ChannelManager.isSslHandlerConfigured(channel.pipeline()) && body instanceof RandomAccessBody && !nettyConfig.isDisableZeroCopy()) {
            msg = new BodyFileRegion((RandomAccessBody) body);

        } else {
            msg = new BodyChunkedInput(body);

            BodyGenerator bg = future.getRequest().getBodyGenerator();
            if (bg instanceof FeedableBodyGenerator) {
                FeedableBodyGenerator.class.cast(bg).setListener(new FeedListener() {
                    @Override
                    public void onContentAdded() {
                        channel.pipeline().get(ChunkedWriteHandler.class).resumeTransfer();
                    }
                });
            }
        }
        ChannelFuture writeFuture = channel.write(msg, channel.newProgressivePromise());

        writeFuture.addListener(new ProgressListener(config, future.getAsyncHandler(), future, false, getContentLength()) {
            public void operationComplete(ChannelProgressiveFuture cf) {
                try {
                    body.close();
                } catch (IOException e) {
                    LOGGER.warn("Failed to close request body: {}", e.getMessage(), e);
                }
                super.operationComplete(cf);
            }
        });
        channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    }
}
