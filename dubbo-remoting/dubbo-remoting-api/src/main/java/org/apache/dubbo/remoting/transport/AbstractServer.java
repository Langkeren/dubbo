/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.remoting.transport;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.manager.ExecutorRepository;
import org.apache.dubbo.common.utils.ExecutorUtil;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.RemotingServer;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.concurrent.ExecutorService;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_VALUE;
import static org.apache.dubbo.remoting.Constants.ACCEPTS_KEY;
import static org.apache.dubbo.remoting.Constants.DEFAULT_ACCEPTS;

/**
 * AbstractServer
 */
public abstract class AbstractServer extends AbstractEndpoint implements RemotingServer {

    protected static final String SERVER_THREAD_POOL_NAME = "DubboServerHandler";
    private static final Logger logger = LoggerFactory.getLogger(AbstractServer.class);
    ExecutorService executor;
    private InetSocketAddress localAddress;
    private InetSocketAddress bindAddress;
    private int accepts;

    private ExecutorRepository executorRepository = ExtensionLoader.getExtensionLoader(ExecutorRepository.class).getDefaultExtension();

    public AbstractServer(URL url, ChannelHandler handler) throws RemotingException {
        // 调用父类构造方法，这里就不用跟进去了，没什么复杂逻辑
        super(url, handler);
        localAddress = getUrl().toInetSocketAddress();

        // 获取 ip 和端口
        String bindIp = getUrl().getParameter(Constants.BIND_IP_KEY, getUrl().getHost());
        int bindPort = getUrl().getParameter(Constants.BIND_PORT_KEY, getUrl().getPort());
        if (url.getParameter(ANYHOST_KEY, false) || NetUtils.isInvalidLocalHost(bindIp)) {
            // 设置 ip 为 0.0.0.0
            bindIp = ANYHOST_VALUE;
        }
        bindAddress = new InetSocketAddress(bindIp, bindPort);
        // 获取最大可接受连接数
        this.accepts = url.getParameter(ACCEPTS_KEY, DEFAULT_ACCEPTS);
        // 创建服务服务后打开服务
        try {
            // 调用模板方法 doOpen 启动服务器
            doOpen();
            if (logger.isInfoEnabled()) {
                logger.info("Start " + getClass().getSimpleName() + " bind " + getBindAddress() + ", export " + getLocalAddress());
            }
        } catch (Throwable t) {
            throw new RemotingException(url.toInetSocketAddress(), null, "Failed to bind " + getClass().getSimpleName()
                    + " on " + getLocalAddress() + ", cause: " + t.getMessage(), t);
        }
        executor = executorRepository.createExecutorIfAbsent(url);
    }

    /**
     * dubbo 默认使用的 NettyServer 是基于 netty 3.x 版本实现的，比较老了。因此 Dubbo 另外提供了 netty 4.x 版本的 NettyServer，大家可在使用 Dubbo 的过程中按需进行配置。
     */
    protected abstract void doOpen() throws Throwable;

    protected abstract void doClose() throws Throwable;

    @Override
    public void reset(URL url) {
        if (url == null) {
            return;
        }

        try {
            if (url.hasParameter(ACCEPTS_KEY)) {
                int a = url.getParameter(ACCEPTS_KEY, 0);
                if (a > 0) {
                    this.accepts = a;
                }
            }
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }

        executorRepository.updateThreadpool(url, executor);
        super.setUrl(getUrl().addParameters(url.getParameters()));
    }

    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        Collection<Channel> channels = getChannels();
        for (Channel channel : channels) {
            if (channel.isConnected()) {
                channel.send(message, sent);
            }
        }
    }

    @Override
    public void close() {
        if (logger.isInfoEnabled()) {
            logger.info("Close " + getClass().getSimpleName() + " bind " + getBindAddress() + ", export " + getLocalAddress());
        }

        ExecutorUtil.shutdownNow(executor, 100);

        try {
            super.close();
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }

        try {
            doClose();
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
    }

    @Override
    public void close(int timeout) {
        ExecutorUtil.gracefulShutdown(executor, timeout);
        close();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return localAddress;
    }

    public InetSocketAddress getBindAddress() {
        return bindAddress;
    }

    public int getAccepts() {
        return accepts;
    }

    @Override
    public void connected(Channel ch) throws RemotingException {
        // If the server has entered the shutdown process, reject any new connection
        if (this.isClosing() || this.isClosed()) {
            logger.warn("Close new channel " + ch + ", cause: server is closing or has been closed. For example, receive a new connect request while in shutdown process.");
            ch.close();
            return;
        }

        if (accepts > 0 && getChannels().size() > accepts) {
            logger.error("Close channel " + ch + ", cause: The server " + ch.getLocalAddress() + " connections greater than max config " + accepts);
            ch.close();
            return;
        }
        super.connected(ch);
    }

    @Override
    public void disconnected(Channel ch) throws RemotingException {
        Collection<Channel> channels = getChannels();
        if (channels.isEmpty()) {
            logger.warn("All clients has disconnected from " + ch.getLocalAddress() + ". You can graceful shutdown now.");
        }
        super.disconnected(ch);
    }

}
