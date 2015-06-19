/*
 * Copyright 2012 Matt Corallo.
 * Copyright 2014 Andreas Schildbach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.bitcoin.core;

import com.google.bitcoin.net.NioClient;
import com.google.bitcoin.params.RegTestParams;
import com.google.bitcoin.store.BlockStoreException;
import com.google.bitcoin.store.FullPrunedBlockStore;
import com.google.bitcoin.store.H2FullPrunedBlockStore;
import com.google.bitcoin.store.MemoryBlockStore;
import com.google.bitcoin.utils.BlockFileLoader;
import com.google.bitcoin.utils.BriefLogFormatter;
import com.google.bitcoin.utils.Threading;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.Uninterruptibles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.TimeUnit;

/**
 * A tool for comparing the blocks which are accepted/rejected by bitcoind/bitcoinj
 * It is designed to run as a testnet-in-a-box network between a single bitcoind node and bitcoinj
 * It is not an automated unit-test because it requires a bit more set-up...read comments below
 */
public class BitcoindComparisonTool {
    private static final Logger log = LoggerFactory.getLogger(BitcoindComparisonTool.class);

    private static NetworkParameters params;

    public static void main(String[] args) throws Exception {
        BriefLogFormatter.init();
        System.out.println("USAGE: bitcoinjBlockStoreLocation runLargeReorgs(1/0) [port=18444]");

        params = RegTestParams.get();

        VersionMessage ver = new VersionMessage(params, 42);
        ver.appendToSubVer("BlockAcceptanceComparisonTool", "1.1", null);
        ver.localServices = VersionMessage.NODE_NETWORK;
        final Peer bitcoind = new Peer(params, ver, new BlockChain(params, new MemoryBlockStore(params)), new PeerAddress(InetAddress.getLoopbackAddress()));

        final SettableFuture<Void> connectedFuture = SettableFuture.create();
        bitcoind.addEventListener(new AbstractPeerEventListener() {
            @Override
            public void onPeerConnected(Peer peer, int peerCount) {
                log.info("bitcoind connected");
                // Make sure bitcoind has no blocks
                bitcoind.setDownloadParameters(0, false);
                bitcoind.startBlockChainDownload();
                connectedFuture.set(null);
            }

            @Override
            public void onPeerDisconnected(Peer peer, int peerCount) {
                log.error("bitcoind node disconnected!");
                System.exit(1);
            }
        }, Threading.SAME_THREAD);

        // bitcoind MUST be on localhost or we will get banned as a DoSer
        new NioClient(new InetSocketAddress(InetAddress.getLoopbackAddress(), args.length > 2 ? Integer.parseInt(args[2]) : params.getPort()), bitcoind, 1000);

        connectedFuture.get();

        for (long i = 0; i < 500000; ++i) {
            ListenableFuture<Long> future = bitcoind.ping(i);
            if (future != null) {
                future.get();
            } else {
                log.error("ERROR: ping() with nonce {} returned null", i);
                System.exit(1);
            }
        }

        log.info("Done testing.");
        System.exit(0);
    }
}
