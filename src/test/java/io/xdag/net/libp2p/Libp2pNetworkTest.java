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

import io.libp2p.core.PeerId;
import io.libp2p.core.crypto.KEY_TYPE;
import io.libp2p.core.crypto.KeyKt;
import io.libp2p.core.crypto.PrivKey;
import io.libp2p.core.multiformats.Multiaddr;
import io.xdag.net.libp2p.peer.NodeId;
import java.net.InetSocketAddress;
import org.junit.Before;
import org.junit.Test;

public class Libp2pNetworkTest {

    //node 0
    PrivKey privKey0 = KeyKt.generateKeyPair(KEY_TYPE.SECP256K1, 0).getFirst();
    PeerId peerId0 = PeerId.fromPubKey(privKey0.publicKey());
    NodeId nodeId0 = new LibP2PNodeId(peerId0);
    Multiaddr advertisedAddr =
            Libp2pUtils.fromInetSocketAddress(
                    new InetSocketAddress("127.0.0.1", 11111), nodeId0);
    Libp2pNetwork node0 = new Libp2pNetwork(privKey0, advertisedAddr);

    // node 1
    PrivKey privKey1 = KeyKt.generateKeyPair(KEY_TYPE.SECP256K1, 0).getFirst();
    PeerId peerId1 = PeerId.fromPubKey(privKey1.publicKey());
    NodeId nodeId1 = new LibP2PNodeId(peerId1);
    Multiaddr advertisedAddr1 =
            Libp2pUtils.fromInetSocketAddress(
                    new InetSocketAddress("127.0.0.1", 12121), nodeId1);
    Libp2pNetwork node1 = new Libp2pNetwork(privKey1, advertisedAddr1);

    @Before
    public void startup() {
        node0.start();
        node1.start();
    }

    @Test
    public void testlibp2pconnect() throws InterruptedException {
        // Alternative connection format
        String peer0 = advertisedAddr.toString();
        peer0 = peer0.replaceAll("p2p", "ipfs");
        // connect
        node1.dail(peer0);
        // wait connect success
        Thread.sleep(1000);
//        assert node1.peerManager.getPeerCount() == 1;
        NonProtocol rpc = (NonProtocol) node1.getProtocol();
        assert rpc.getConnection().remoteAddress().toString().equals(advertisedAddr.toString().substring(0, 24));
    }
}