/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020-2030 The XdagJ Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.xdag.net.libp2p;

import io.libp2p.core.Connection;
import io.libp2p.core.multiformats.Multiaddr;
import io.libp2p.core.multiformats.Protocol;
import io.xdag.Kernel;
import io.xdag.core.BlockWrapper;
import io.xdag.net.Channel;
import io.xdag.net.handler.Xdag;
import io.xdag.net.message.MessageQueue;
import io.xdag.net.node.Node;
import java.net.InetSocketAddress;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.math.NumberUtils;

/**
 * @author wawa
 */
@Slf4j
@Getter
public class Libp2pChannel extends Channel {

    private final Connection connection;
    private final Libp2pXdagProtocol protocol;

    public Libp2pChannel(Connection connection, Libp2pXdagProtocol protocol, Kernel kernel) {
        this.connection = connection;
        this.protocol = protocol;
        this.kernel = kernel;

        Multiaddr multiaddr = connection.remoteAddress();
        String ip = Protocol.IP4.bytesToAddress(multiaddr.getComponent(Protocol.IP4));
        String port = Protocol.TCP.bytesToAddress(multiaddr.getComponent(Protocol.TCP));
        this.inetSocketAddress = new InetSocketAddress(ip, NumberUtils.toInt(port));
        this.node = new Node(connection.secureSession().getRemoteId().getBytes(), ip, NumberUtils.toInt(port));
        this.messageQueue = new MessageQueue(this);
        log.debug("Initwith Node host:" + ip + " port:" + port + " node:" + node.getHexId());
    }

    @Override
    public void sendNewBlock(BlockWrapper blockWrapper) {
        protocol.getLibp2PXdagController().sendNewBlock(blockWrapper.getBlock(), blockWrapper.getTtl());
    }

    @Override
    public void onDisconnect() {
        isDisconnected = true;
    }

    @Override
    public boolean isDisconnected() {
        return isDisconnected;
    }

    @Override
    public MessageQueue getMessageQueue() {
        return messageQueue;
    }

    @Override
    public Kernel getKernel() {
        return kernel;
    }

    @Override
    public InetSocketAddress getInetSocketAddress() {
        return inetSocketAddress;
    }

    @Override
    public boolean isActive() {
        return isActive;
    }

    @Override
    public void setActive(boolean b) {
        isActive = b;
    }

    @Override
    public Node getNode() {
        return node;
    }

    @Override
    public void dropConnection() {
        connection.close();
    }

    @Override
    public Xdag getXdag() {
        return protocol.getLibp2PXdagController();
    }
}
