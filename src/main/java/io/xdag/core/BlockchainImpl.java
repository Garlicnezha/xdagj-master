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

package io.xdag.core;

import static io.xdag.config.Constants.BI_APPLIED;
import static io.xdag.config.Constants.BI_EXTRA;
import static io.xdag.config.Constants.BI_MAIN;
import static io.xdag.config.Constants.BI_MAIN_CHAIN;
import static io.xdag.config.Constants.BI_MAIN_REF;
import static io.xdag.config.Constants.BI_OURS;
import static io.xdag.config.Constants.BI_REF;
import static io.xdag.config.Constants.MAIN_BIG_PERIOD_LOG;
import static io.xdag.config.Constants.MAIN_CHAIN_PERIOD;
import static io.xdag.config.Constants.MAX_ALLOWED_EXTRA;
import static io.xdag.config.Constants.MessageType.NEW_LINK;
import static io.xdag.config.Constants.MessageType.PRE_TOP;
import static io.xdag.config.Constants.SYNC_FIX_HEIGHT;
import static io.xdag.core.ImportResult.IMPORTED_BEST;
import static io.xdag.core.ImportResult.IMPORTED_NOT_BEST;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_HEAD;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_HEAD_TEST;
import static io.xdag.utils.BasicUtils.getDiffByHash;
import static io.xdag.utils.BasicUtils.getHashlowByHash;
import static io.xdag.utils.BytesUtils.equalBytes;

import com.google.common.collect.Lists;
import com.google.common.primitives.UnsignedLong;
import io.xdag.Kernel;
import io.xdag.config.MainnetConfig;
import io.xdag.crypto.ECDSASignature;
import io.xdag.crypto.ECKeyPair;
import io.xdag.crypto.Hash;
import io.xdag.crypto.Sign;
import io.xdag.db.DatabaseName;
import io.xdag.db.rocksdb.RocksdbFactory;
import io.xdag.db.store.BlockStore;
import io.xdag.db.store.OrphanPool;
import io.xdag.listener.Listener;
import io.xdag.listener.Message;
import io.xdag.randomx.RandomX;
import io.xdag.snapshot.core.SnapshotInfo;
import io.xdag.snapshot.core.SnapshotUnit;
import io.xdag.snapshot.core.StatsBlock;
import io.xdag.snapshot.db.SnapshotChainStore;
import io.xdag.snapshot.db.SnapshotChainStoreImpl;
import io.xdag.utils.ByteArrayWrapper;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.XdagTime;
import io.xdag.wallet.Wallet;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.util.Arrays;
import org.bouncycastle.util.encoders.Hex;


@Slf4j
@Getter
public class BlockchainImpl implements Blockchain {

    private static final ThreadFactory factory = new ThreadFactory() {
        private final AtomicInteger cnt = new AtomicInteger(0);

        @Override
        public Thread newThread(@Nonnull Runnable r) {
            return new Thread(r, "check main-" + cnt.getAndIncrement());
        }
    };

    private final Wallet wallet;
    private final BlockStore blockStore;
    /**
     * ???Extra orphan??????
     */
    private final OrphanPool orphanPool;

    private final LinkedHashMap<ByteArrayWrapper, Block> memOrphanPool = new LinkedHashMap<>();
    private final Map<ByteArrayWrapper, Integer> memOurBlocks = new ConcurrentHashMap<>();
    private final XdagStats xdagStats;
    private final Kernel kernel;


    private final XdagTopStatus xdagTopStatus;

    private final ScheduledExecutorService checkLoop;
    private final RandomX randomXUtils;
    private final List<Listener> listeners = new ArrayList<>();
    private ScheduledFuture<?> checkLoopFuture;
    @Setter
    private SnapshotChainStore snapshotChainStore;
    private long snapshotHeight;

    @Getter
    private byte[] preSeed;

    public BlockchainImpl(Kernel kernel) {
        this.kernel = kernel;
        this.wallet = kernel.getWallet();

        // 1. init chain state from rocksdb
        this.blockStore = kernel.getBlockStore();
        this.orphanPool = kernel.getOrphanPool();

        // 2. if enable snapshot, init snapshot from rocksdb
        if (kernel.getConfig().getSnapshotSpec().isSnapshotEnabled()
                && kernel.getConfig().getSnapshotSpec().getSnapshotHeight() > 0
                // ?????????????????????
                && !blockStore.isSnapshotBoot()) {
            this.xdagStats = new XdagStats();
            this.xdagTopStatus = new XdagTopStatus();
            snapshotHeight = kernel.getConfig().getSnapshotSpec().getSnapshotHeight();
            this.snapshotChainStore = new SnapshotChainStoreImpl(
                    new RocksdbFactory(kernel.getConfig()).getDB(DatabaseName.SNAPSHOT));
            initSnapshot();
            // ???????????????????????????
            blockStore.saveXdagTopStatus(xdagTopStatus);
            blockStore.saveXdagStatus(xdagStats);
        } else {
            XdagStats storedStats = blockStore.getXdagStatus();
            if (storedStats != null) {
                storedStats.setNwaitsync(0);
                this.xdagStats = storedStats;
                this.xdagStats.nextra = 0;
            } else {
                this.xdagStats = new XdagStats();
            }
            XdagTopStatus storedTopStatus = blockStore.getXdagTopStatus();
            this.xdagTopStatus = Objects.requireNonNullElseGet(storedTopStatus, XdagTopStatus::new);
            preSeed = blockStore.getPreSeed();
        }

        // add randomx utils
        randomXUtils = kernel.getRandomXUtils();
        if (randomXUtils != null) {
            randomXUtils.setBlockchain(this);
        }

        checkLoop = new ScheduledThreadPoolExecutor(1, factory);
        // ???????????????
        this.startCheckMain(1024);
    }

    public void initSnapshot() {
        long start = System.currentTimeMillis();
        initSnapshotChain();
        initStats();
        // TODO ??????snapshot?????????
        cleanSnapshotChain();
        long end = System.currentTimeMillis();
        System.out.println("init snapshot done");
        System.out.println("?????????" + (end - start) + "ms");
    }


    @Override
    public void registerListener(Listener listener) {
        this.listeners.add(listener);
    }

    public void initStats() {
        this.xdagStats.setTotalnmain(snapshotHeight);
        this.xdagStats.setNmain(snapshotHeight);
        this.xdagStats.setMaxdifficulty(snapshotChainStore.getLatestStatsBlock().getDifficulty());
        this.xdagStats.setDifficulty(snapshotChainStore.getLatestStatsBlock().getDifficulty());
        this.xdagStats.setNwaitsync(0);
        this.xdagStats.setNnoref(0);
        this.xdagStats.setNextra(0);

        this.xdagStats.setBalance(snapshotChainStore.getGlobalBalance());
//        this.xdagStats.setGlobalMiner();
        this.xdagStats.setTotalnblocks(0);
        this.xdagStats.setNblocks(0);

        this.xdagTopStatus.setPreTop(getHashlowByHash(snapshotChainStore.getLatestStatsBlock().getHash()));
        this.xdagTopStatus.setTop(getHashlowByHash(snapshotChainStore.getLatestStatsBlock().getHash()));

        this.xdagTopStatus.setPreTopDiff(snapshotChainStore.getLatestStatsBlock().getDifficulty());
        this.xdagTopStatus.setTopDiff(snapshotChainStore.getLatestStatsBlock().getDifficulty());
    }

    public void initSnapshotChain() {
        log.info("Snapshot Store init.");
        snapshotChainStore.init();
        getBlockFromSnapshot(snapshotChainStore, blockStore);
    }

    public void cleanSnapshotChain() {
        snapshotChainStore.reset();
    }

    protected void getBlockFromSnapshot(SnapshotChainStore snapshotChainStore, BlockStore blockStore) {
        List<SnapshotUnit> snapshotUnits = snapshotChainStore.getAllSnapshotUnit();
        for (SnapshotUnit snapshotUnit : snapshotUnits) {
            blockStore.saveBlockInfo(SnapshotUnit.trasferToBlockInfo(snapshotUnit));
        }

        List<StatsBlock> statsBlocks = snapshotChainStore.getSnapshotStatsBlock();
        for (StatsBlock statsBlock : statsBlocks) {
            if (blockStore.hasBlockInfo(Bytes32.wrap(getHashlowByHash(statsBlock.getHash())))) {
                BlockInfo blockInfo = blockStore
                        .getBlockInfoByHash(Bytes32.wrap(getHashlowByHash(statsBlock.getHash()))).getInfo();
                blockInfo.setDifficulty(statsBlock.getDifficulty());
                blockInfo.setHeight(statsBlock.getHeight());
                blockStore.saveBlockInfo(blockInfo);
            }
        }
        preSeed = snapshotChainStore.getSnapshotPreSeed();
        blockStore.savePreSeed(preSeed);
    }

    public List<String> getFileName(long time) {
        List<String> file = new ArrayList<>();
        file.add("");
        StringBuilder stringBuffer = new StringBuilder(
                Hex.toHexString(BytesUtils.byteToBytes((byte) ((time >> 40) & 0xff), true)));
        stringBuffer.append("/");
        file.add(String.valueOf(stringBuffer));
        stringBuffer.append(
                Hex.toHexString(BytesUtils.byteToBytes((byte) ((time >> 32) & 0xff), true)));
        stringBuffer.append("/");
        file.add(String.valueOf(stringBuffer));
        stringBuffer.append(
                Hex.toHexString(BytesUtils.byteToBytes((byte) ((time >> 24) & 0xff), true)));
        stringBuffer.append("/");
        file.add(String.valueOf(stringBuffer));
        return file;
    }

    /**
     * ????????????????????????
     */
    @Override
    public synchronized ImportResult tryToConnect(Block block) {

        // TODO: if current height is snapshot height, we need change logic to process new block

        try {
            ImportResult result = ImportResult.IMPORTED_NOT_BEST;

            long type = block.getType() & 0xf;
            if (kernel.getConfig() instanceof MainnetConfig) {
                if (type != XDAG_FIELD_HEAD.asByte()) {
                    result = ImportResult.ERROR;
                    result.setErrorInfo("Block type error, is not a mainnet block");
                    return result;
                }
            } else {
                if (type != XDAG_FIELD_HEAD_TEST.asByte()) {
                    result = ImportResult.ERROR;
                    result.setErrorInfo("Block type error, is not a testnet block");
                    return result;
                }
            }

            if (block.getTimestamp() > (XdagTime.getCurrentTimestamp() + MAIN_CHAIN_PERIOD / 4)
                    || block.getTimestamp() < kernel.getConfig().getXdagEra()
//                    || (limit && timestamp - tmpNodeBlock.time > limit)
            ) {
                result = ImportResult.INVALID_BLOCK;
                result.setErrorInfo("Block's time is illegal");
                return result;
            }

            if (isExist(block.getHashLow())) {
                return ImportResult.EXIST;
            }

            if (isExtraBlock(block)) {
                updateBlockFlag(block, BI_EXTRA, true);
            }

            List<Address> all = block.getLinks().stream().distinct().collect(Collectors.toList());
            // ??????????????????????????????????????????,?????????input???output??????block????????????pending???db?????????
            for (Address ref : all) {
                if (ref != null) {
                    Block refBlock = getBlockByHash(ref.getHashLow(), false);
                    if (refBlock == null) {
//                        log.debug("No Parent " + Hex.toHexString(ref.getHashLow()));
                        result = ImportResult.NO_PARENT;
                        result.setHashlow(ref.getHashLow());
                        result.setErrorInfo("Block have no parent for " + result.getHashlow().toHexString());
                        return result;
                    } else {
                        // ?????????????????????????????????????????????????????????????????????
                        if (refBlock.getTimestamp() >= block.getTimestamp()) {
                            result = ImportResult.INVALID_BLOCK;
                            result.setHashlow(refBlock.getHashLow());
                            result.setErrorInfo("Ref block's time >= block's time");
                            return result;
                        }

//                        if (!ref.getAmount().equals(BigInteger.ZERO)) {
//                            updateBlockFlag(block, BI_EXTRA, false);
//                        }
                    }

                }
            }

            // remove links
            for (Address ref : all) {
                removeOrphan(ref.getHashLow(),
                        (block.getInfo().flags & BI_EXTRA) != 0
                                ? OrphanRemoveActions.ORPHAN_REMOVE_EXTRA
                                : OrphanRemoveActions.ORPHAN_REMOVE_NORMAL);
                // TODO:add backref
                // if(!all.get(i).getAmount().equals(BigInteger.ZERO)){
                // Block blockRef = getBlockByHash(all.get(i).getHashLow(),false);
                // }
            }

            // ??????????????????
            checkNewMain();

            // ????????????????????? ??????input???????????????
            if (!canUseInput(block)) {
                result = ImportResult.INVALID_BLOCK;
                result.setHashlow(block.getHashLow());
                result.setErrorInfo("Block's input can't be used");
                return ImportResult.INVALID_BLOCK;
            }

            // ????????????????????????
            if (checkMineAndAdd(block)) {
                log.debug("A block hash:" + block.getHashLow().toHexString() + " become mine");
                updateBlockFlag(block, BI_OURS, true);
            }

            // ?????????????????????maxDiffLink
            calculateBlockDiff(block);

            // ??????preTop
            setPreTop(block);
            setPreTop(getBlockByHash(xdagTopStatus.getTop() == null ? null : Bytes32.wrap(xdagTopStatus.getTop()),
                    false));

            // ??????XdagPoW ???pretop??????
            onNewPretop();

            // TODO:extra ??????
            processExtraBlock();
//            if (memOrphanPool.size() > MAX_ALLOWED_EXTRA) {
//                Block reuse = getHead(memOrphanPool).getValue();
//                log.debug("remove when extra too big");
//                removeOrphan(reuse.getHashLow(), OrphanRemoveActions.ORPHAN_REMOVE_REUSE);
//                xdagStats.nblocks--;
//                xdagStats.totalnblocks = Math.max(xdagStats.nblocks,xdagStats.totalnblocks);
//
//                if ((reuse.getInfo().flags & BI_OURS) != 0) {
//                    removeOurBlock(reuse);
//                }
//            }

            // ????????????????????????
            // ??????????????????????????????????????????????????????topMainChain
            if (block.getInfo().getDifficulty().compareTo(xdagTopStatus.getTopDiff()) > 0) {
                // ???????????? fork
                long currentHeight = xdagStats.nmain;
                // ??????????????????blockref
                Block blockRef = findAncestor(block, isSyncFixFork(xdagStats.nmain));
                // ??????????????????blockRef
                unWindMain(blockRef);
                // ???????????????
                updateNewChain(block, isSyncFixFork(xdagStats.nmain));
                // ????????????
                if (currentHeight - xdagStats.nmain > 1) {
                    log.info("XDAG:Before unwind, height = {}, After unwind, height = {}, unwind number = {}",
                            currentHeight, xdagStats.nmain, currentHeight - xdagStats.nmain);
                }
                xdagTopStatus.setTopDiff(block.getInfo().getDifficulty());
                xdagTopStatus.setTop(block.getHashLow().toArray());
                result = ImportResult.IMPORTED_BEST;
            }

            // ????????????
            xdagStats.nblocks++;
            xdagStats.totalnblocks = Math.max(xdagStats.nblocks, xdagStats.totalnblocks);
//            if (xdagStats.getTotalnblocks() < xdagStats.getNblocks()) {
//                xdagStats.setTotalnblocks(xdagStats.getNblocks());
//            }

            //orphan (hash , block)
//            log.debug("======New block waiting to link======,{}",Hex.toHexString(block.getHashLow()));
            if ((block.getInfo().flags & BI_EXTRA) != 0) {
//                log.debug("block:{} is extra, put it into memOrphanPool waiting to link.", Hex.toHexString(block.getHashLow()));
                memOrphanPool.put(new ByteArrayWrapper(block.getHashLow().toArray()), block);
                xdagStats.nextra++;
            } else {
//                log.debug("block:{} is extra, put it into orphanPool waiting to link.", Hex.toHexString(block.getHashLow()));
                saveBlock(block);
                orphanPool.addOrphan(block);
                xdagStats.nnoref++;
            }
            blockStore.saveXdagStatus(xdagStats);

            // ????????????????????????0??????????????????
            if (block.getInputs().size() != 0) {
                if ((block.getInfo().getFlags() & BI_OURS) != 0) {
                    log.info("XDAG:pool transaction(reward). block hash:{}", block.getHash().toHexString());
                }
            }

            return result;
        } catch (Throwable e) {
            log.error(e.getMessage(), e);
            return ImportResult.ERROR;
        }
    }


    /**
     * ??????????????????????????????????????????????????????
     *
     * @return
     */
    // TODO: ??????syncFixHeight ?????? ??????????????????
    // TODO: paulochen ???????????????????????????????????????
    public boolean isSyncFixFork(long currentHeight) {
        long syncFixHeight = SYNC_FIX_HEIGHT;
        return currentHeight >= syncFixHeight;
    }

    public Block findAncestor(Block block, boolean isFork) {
        // ???????????? fork
        Block blockRef;
        Block blockRef0 = null;
        // ?????????????????????????????????????????????????????????????????????????????? ????????????????????????????????????
        for (blockRef = block;
                blockRef != null && ((blockRef.getInfo().flags & BI_MAIN_CHAIN) == 0);
                blockRef = getMaxDiffLink(blockRef, false)) {
            Block tmpRef = getMaxDiffLink(blockRef, false);
            if (
                    (tmpRef == null
                            || blockRef.getInfo().getDifficulty().compareTo(calculateBlockDiff(tmpRef)) > 0) &&
                            (blockRef0 == null || XdagTime.getEpoch(blockRef0.getTimestamp()) > XdagTime
                                    .getEpoch(blockRef.getTimestamp()))
            ) {
                if (!isFork) {
                    updateBlockFlag(blockRef, BI_MAIN_CHAIN, true);
                }
                blockRef0 = blockRef;
            }
        }
        // ?????????
        if (blockRef != null
                && blockRef0 != null
                && !blockRef.equals(blockRef0)
                && XdagTime.getEpoch(blockRef.getTimestamp()) == XdagTime.getEpoch(blockRef0.getTimestamp())) {
            blockRef = getMaxDiffLink(blockRef, false);
        }
        return blockRef;
    }

    public void updateNewChain(Block block, boolean isFork) {
        if (!isFork) {
            return;
        }
        Block blockRef;
        Block blockRef0 = null;
        // ?????????????????????????????????????????????????????????????????????????????? ????????????????????????????????????
        for (blockRef = block;
                blockRef != null && ((blockRef.getInfo().flags & BI_MAIN_CHAIN) == 0);
                blockRef = getMaxDiffLink(blockRef, false)) {
            Block tmpRef = getMaxDiffLink(blockRef, false);
            if (
                    (tmpRef == null
                            || blockRef.getInfo().getDifficulty().compareTo(calculateBlockDiff(tmpRef)) > 0) &&
                            (blockRef0 == null || XdagTime.getEpoch(blockRef0.getTimestamp()) > XdagTime
                                    .getEpoch(blockRef.getTimestamp()))
            ) {
                updateBlockFlag(blockRef, BI_MAIN_CHAIN, true);
                blockRef0 = blockRef;
            }
        }
    }

    public void processExtraBlock() {
        if (memOrphanPool.size() > MAX_ALLOWED_EXTRA) {
            Block reuse = memOrphanPool.entrySet().iterator().next().getValue();
            log.debug("Remove when extra too big");
            removeOrphan(reuse.getHashLow(), OrphanRemoveActions.ORPHAN_REMOVE_REUSE);
            xdagStats.nblocks--;
            xdagStats.totalnblocks = Math.max(xdagStats.nblocks, xdagStats.totalnblocks);

            if ((reuse.getInfo().flags & BI_OURS) != 0) {
                removeOurBlock(reuse);
            }
        }
    }

    protected void onNewPretop() {
        for (Listener listener : listeners) {
            listener.onMessage(new Message(Bytes.wrap(xdagTopStatus.getPreTop())), PRE_TOP);
        }
    }

    protected void onNewBlock(Block block) {
        for (Listener listener : listeners) {
            listener.onMessage(new Message(Bytes.wrap(block.getXdagBlock().getData())), NEW_LINK);
        }
    }

    /**
     * ?????????????????? *
     */
    @Override
    public synchronized void checkNewMain() {
        Block p = null;
        int i = 0;
        // TODO: ???????????????????????????????????????????????????????????????????????????????????????
        if (xdagTopStatus.getTop() != null) {
            for (Block block = getBlockByHash(Bytes32.wrap(xdagTopStatus.getTop()), false); block != null
                    && ((block.getInfo().flags & BI_MAIN) == 0);
                    block = getMaxDiffLink(getBlockByHash(block.getHashLow(), true), true)) {

                if ((block.getInfo().flags & BI_MAIN_CHAIN) != 0) {
                    p = block;
                    ++i;
                }
            }
        }
        long ct = XdagTime.getCurrentTimestamp();
        if (p != null
                && ((p.getInfo().flags & BI_REF) != 0)
                && i > 1
                && ct >= p.getTimestamp() + 2 * 1024) {
//            log.info("setMain success block:{}", Hex.toHexString(p.getHashLow()));
            setMain(p);
        }
    }

    /**
     * ???????????????block *
     */
    public void unWindMain(Block block) {
        log.debug("Unwind main to block,{}", block == null ? "null" : block.getHashLow().toHexString());
//        log.debug("xdagTopStatus.getTop(),{}",xdagTopStatus.getTop()==null?"null":Hex.toHexString(xdagTopStatus.getTop()));
        if (xdagTopStatus.getTop() != null) {
            for (Block tmp = getBlockByHash(Bytes32.wrap(xdagTopStatus.getTop()), true); tmp != null
                    && !blockEqual(block, tmp); tmp = getMaxDiffLink(tmp, true)) {
                updateBlockFlag(tmp, BI_MAIN_CHAIN, false);
                // ???????????????flag??????
                if ((tmp.getInfo().flags & BI_MAIN) != 0) {
                    unSetMain(tmp);
                    // Fix: paulochen ??????????????????????????????????????????????????? ??????height 210729
                    blockStore.saveBlockInfo(tmp.getInfo());
                }
            }
        }
    }


    private boolean blockEqual(Block block1, Block block2) {
        if (block1 == null) {
            return block2 == null;
        } else {
            return block2.equals(block1);
        }
    }

    /**
     * ?????????????????????????????? *
     */
    private UnsignedLong applyBlock(Block block) {
        UnsignedLong sumIn = UnsignedLong.ZERO;
        UnsignedLong sumOut = UnsignedLong.ZERO; // sumOut???????????????????????????link?????????????????? ????????????0

        // ?????????
        if ((block.getInfo().flags & BI_MAIN_REF) != 0) {
            return UnsignedLong.ZERO.minus(UnsignedLong.ONE);
        }
        // ??????????????????
        updateBlockFlag(block, BI_MAIN_REF, true);

        List<Address> links = block.getLinks();
        if (links == null || links.size() == 0) {
            updateBlockFlag(block, BI_APPLIED, true);
            return UnsignedLong.ZERO;
        }

        for (Address link : links) {
            // ???????????????????????????????????????
            Block ref = getBlockByHash(link.getHashLow(), false);
            UnsignedLong ret;
            // ???????????????
            if ((ref.getInfo().flags & BI_MAIN_REF) != 0) {
                ret = UnsignedLong.ZERO.minus(UnsignedLong.ONE);
            } else {
                ref = getBlockByHash(link.getHashLow(), true);
                ret = applyBlock(ref);
            }
            if (ret.compareTo(UnsignedLong.ZERO.minus(UnsignedLong.ONE)) == 0) {
                continue;
            }
            updateBlockRef(ref, new Address(block));
            if (UnsignedLong.valueOf(block.getInfo().getAmount()).plus(ret).longValue() >=
                    block.getInfo().getAmount()) {
                acceptAmount(block, ret);
            }
        }

        for (Address link : links) {
            if (link.getType() == XdagField.FieldType.XDAG_FIELD_IN) {
                Block ref = getBlockByHash(link.getHashLow(), false);

                if (ref.getInfo().getAmount() < link.getAmount().longValue()) {
                    log.debug("This input ref doesn't have enough amount,hash:{},amount:{},need:{}",
                            Hex.toHexString(ref.getInfo().getHashlow()), ref.getInfo().getAmount(),
                            link.getAmount().longValue());
                    return UnsignedLong.ZERO;
                }
                if (sumIn.plus(UnsignedLong.valueOf(link.getAmount())).longValue() < sumIn.longValue()) {
                    log.debug("This input ref's amount less than 0");
                    return UnsignedLong.ZERO;
                }
                sumIn = sumIn.plus(UnsignedLong.valueOf(link.getAmount()));
            } else {
                if (sumOut.plus(UnsignedLong.valueOf(link.getAmount())).longValue() < sumOut.longValue()) {
                    log.debug("This output ref's amount less than 0");
                    return UnsignedLong.ZERO;
                }
                sumOut = sumOut.plus(UnsignedLong.valueOf(link.getAmount()));
            }
        }

        if (UnsignedLong.valueOf(block.getInfo().getAmount()).plus(sumIn).longValue() < sumOut.longValue()
                || UnsignedLong.valueOf(block.getInfo().getAmount()).plus(sumIn).longValue() < sumIn.longValue()) {
            log.debug("exec fail!");
            return UnsignedLong.ZERO;
        }

        for (Address link : links) {
            Block ref = getBlockByHash(link.getHashLow(), false);
            if (link.getType() == XdagField.FieldType.XDAG_FIELD_IN) {
                acceptAmount(ref, UnsignedLong.ZERO.minus(UnsignedLong.valueOf(link.getAmount())));
            } else {
                acceptAmount(ref, UnsignedLong.valueOf(link.getAmount()));
            }
//            blockStore.saveBlockInfo(ref.getInfo()); // TODO???acceptAmount?????????????????? ????????????????????????
        }

        // ???????????????0 ??????????????????????????????
        UnsignedLong remain = sumIn.minus(sumOut);
        acceptAmount(block, remain);
        updateBlockFlag(block, BI_APPLIED, true);
        return UnsignedLong.ZERO;
    }

    // TODO: unapply block which in snapshot
    public UnsignedLong unApplyBlock(Block block) {
        List<Address> links = block.getLinks();
        if ((block.getInfo().flags & BI_APPLIED) != 0) {
            UnsignedLong sum = UnsignedLong.ZERO;
            for (Address link : links) {
                Block ref = getBlockByHash(link.getHashLow(), false);
                if (link.getType() == XdagField.FieldType.XDAG_FIELD_IN) {
                    acceptAmount(ref, UnsignedLong.valueOf(link.getAmount()));
                    sum = sum.minus(UnsignedLong.valueOf(link.getAmount()));
                } else {
                    acceptAmount(ref, UnsignedLong.ZERO.minus(UnsignedLong.valueOf(link.getAmount())));
                    sum = sum.plus(UnsignedLong.valueOf(link.getAmount()));
                }
            }
            acceptAmount(block, sum);
            updateBlockFlag(block, BI_APPLIED, false);
        }
        updateBlockFlag(block, BI_MAIN_REF, false);
        updateBlockRef(block, null);

        for (Address link : links) {
            Block ref = getBlockByHash(link.getHashLow(), false);
            if (ref.getInfo().getRef() != null
                    && equalBytes(ref.getInfo().getRef(), block.getHashLow().toArray())
                    && ((ref.getInfo().flags & BI_MAIN_REF) != 0)) {
                acceptAmount(block, unApplyBlock(getBlockByHash(ref.getHashLow(), true)));
            }
        }
        return UnsignedLong.ZERO;
    }

    /**
     * ?????????block?????????????????? ???????????? ???????????? *
     */
    public void setMain(Block block) {
        // ????????????
        long mainNumber = xdagStats.nmain + 1;
        log.debug("mainNumber = {},hash = {}", mainNumber, Hex.toHexString(block.getInfo().getHash()));
        long reward = getReward(mainNumber);
        block.getInfo().setHeight(mainNumber);
        updateBlockFlag(block, BI_MAIN, true);

        // ????????????
        acceptAmount(block, UnsignedLong.valueOf(reward));
        xdagStats.nmain++;

        // ????????????????????????????????? ??????????????????
        acceptAmount(block, applyBlock(block));
        // ??????REF????????????
        // TODO:???????????????
        updateBlockRef(block, new Address(block));

        if (randomXUtils != null) {
            randomXUtils.randomXSetForkTime(block);
        }

    }

    /**
     * ??????Block???????????? *
     */
    public void unSetMain(Block block) {

        log.debug("UnSet main,{}, mainnumber = {}", block.getHash().toHexString(), xdagStats.nmain);

        long amount = getReward(xdagStats.nmain);
        updateBlockFlag(block, BI_MAIN, false);

        xdagStats.nmain--;

        // ????????????????????????????????????
        acceptAmount(block, UnsignedLong.ZERO.minus(UnsignedLong.valueOf(amount)));
        acceptAmount(block, unApplyBlock(block));

        if (randomXUtils != null) {
            randomXUtils.randomXUnsetForkTime(block);
        }
        // ????????????????????????
        block.getInfo().setHeight(0);
    }

    @Override
    public Block createNewBlock(Map<Address, ECKeyPair> pairs, List<Address> to, boolean mining, String remark) {

        int hasRemark = remark == null ? 0 : 1;

        if (pairs == null && to == null) {
            if (mining) {
                return createMainBlock();
            } else {
                return createLinkBlock(remark);
            }
        }
        int defKeyIndex = -1;

        // ????????????key ???????????????defKey
        assert pairs != null;
        List<ECKeyPair> keys = new ArrayList<>(Set.copyOf(pairs.values()));
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).equals(wallet.getDefKey())) {
                defKeyIndex = i;
            }
        }

        List<Address> all = Lists.newArrayList();
        all.addAll(pairs.keySet());
        all.addAll(to);

        // TODO: ??????pair???????????????
        int res = 1 + pairs.size() + to.size() + 3 * keys.size() + (defKeyIndex == -1 ? 2 : 0) + hasRemark;

        // TODO : ????????????????????????
        if (res > 16) {
            return null;
        }

        long sendTime = XdagTime.getCurrentTimestamp();

        List<Address> refs = Lists.newArrayList();

        Address preTop = null;
        Bytes32 pretopHash = getPreTopMainBlockForLink(sendTime);
        if (pretopHash != null) {
            if (getBlockByHash(pretopHash, false).getTimestamp() < sendTime) {
                preTop = new Address(Bytes32.wrap(pretopHash), XdagField.FieldType.XDAG_FIELD_OUT);
                res++;
            }
        }

        if (res < 16) {
            if (preTop != null) {
                refs.add(preTop);
            }
            List<Address> orphan = getBlockFromOrphanPool(16 - res, sendTime);
            if (orphan != null && orphan.size() != 0) {
                refs.addAll(orphan);
            }
            return new Block(kernel.getConfig(), sendTime, all, refs, mining, keys, remark, defKeyIndex);
        }

        return new Block(kernel.getConfig(), sendTime, all, refs, mining, keys, remark, defKeyIndex);
    }

    public Block createMainBlock() {
        // <header + remark + outsig + nonce>
        int res = 1 + 1 + 2 + 1;

        long sendTime = XdagTime.getMainTime();
        Address preTop = null;
        Bytes32 pretopHash = getPreTopMainBlockForLink(sendTime);
        if (pretopHash != null) {
            preTop = new Address(Bytes32.wrap(pretopHash), XdagField.FieldType.XDAG_FIELD_OUT);
            res++;
        }
        List<Address> refs = Lists.newArrayList();
        if (preTop != null) {
            refs.add(preTop);
        }
        List<Address> orphans = getBlockFromOrphanPool(16 - res, sendTime);
        if (CollectionUtils.isNotEmpty(orphans)) {
            refs.addAll(orphans);
        }
        return new Block(kernel.getConfig(), sendTime, null, refs, true, null,
                kernel.getConfig().getPoolSpec().getPoolTag(), -1);
    }

    public Block createLinkBlock(String remark) {
        // <header + remark + outsig + nonce>
        int hasRemark = remark == null ? 0 : 1;
        int res = 1 + hasRemark + 2;

        long sendTime = XdagTime.getCurrentTimestamp();
        Address preTop = null;
        Bytes32 pretopHash = getPreTopMainBlockForLink(sendTime);
        if (pretopHash != null) {
            preTop = new Address(Bytes32.wrap(pretopHash), XdagField.FieldType.XDAG_FIELD_OUT);
            res++;
        }
        List<Address> refs = Lists.newArrayList();
        if (preTop != null) {
            refs.add(preTop);
        }
        List<Address> orphans = getBlockFromOrphanPool(16 - res, sendTime);
        if (CollectionUtils.isNotEmpty(orphans)) {
            refs.addAll(orphans);
        }
        return new Block(kernel.getConfig(), sendTime, null, refs, false, null,
                remark, -1);
    }

    /**
     * @return java.util.List<io.xdag.core.Address>
     * @Description ???orphan????????????????????????orphan?????????link
     * @Param [num]
     **/
    public List<Address> getBlockFromOrphanPool(int num, long sendtime) {
        return orphanPool.getOrphan(num, sendtime);
    }

    public Bytes32 getPreTopMainBlockForLink(long sendTime) {
        long mainTime = XdagTime.getEpoch(sendTime);
        Block topInfo;
        if (xdagTopStatus.getTop() == null) {
            return null;
        }

        topInfo = getBlockByHash(Bytes32.wrap(xdagTopStatus.getTop()), false);
        if (topInfo == null) {
            return null;
        }
        if (XdagTime.getEpoch(topInfo.getTimestamp()) == mainTime) {
            return Bytes32.wrap(xdagTopStatus.getPreTop());
        } else {
            return Bytes32.wrap(xdagTopStatus.getTop());
        }
    }

    public void setPreTop(Block block) {
        if (block == null) {
            return;
        }
        if (XdagTime.getEpoch(block.getTimestamp()) > XdagTime.getCurrentEpoch()) {
            return;
        }
        BigInteger blockDiff = calculateBlockDiff(block);
        if (xdagTopStatus.getPreTop() == null) {
            xdagTopStatus.setPreTop(block.getHashLow().toArray());
            xdagTopStatus.setPreTopDiff(blockDiff);
            block.setPretopCandidate(true);
            block.setPretopCandidateDiff(blockDiff);
            return;
        }

        if (blockDiff.compareTo(xdagTopStatus.getPreTopDiff()) > 0) {
            xdagTopStatus.setPreTop(block.getHashLow().toArray());
            xdagTopStatus.setPreTopDiff(blockDiff);
            block.setPretopCandidate(true);
            block.setPretopCandidateDiff(blockDiff);
        }
    }

    /**
     * ?????????????????????????????? ?????????????????? ????????????????????? ????????????????????? *
     */
    public BigInteger calculateBlockDiff(Block block) {

        if (block.getInfo().getDifficulty() != null) {
            return block.getInfo().getDifficulty();
        }

        BigInteger diff0;
        // ??????????????????????????????
        if (randomXUtils != null && randomXUtils.isRandomxFork(XdagTime.getEpoch(block.getTimestamp()))
                && XdagTime.isEndOfEpoch(block.getTimestamp())) {
            diff0 = getDiffByRandomXHash(block);
        } else {
            diff0 = getDiffByRawHash(block.getHash());
        }
        block.getInfo().setDifficulty(diff0);

        BigInteger maxDiff = diff0;
        Address maxDiffLink = null;

        // ????????????
        Block tmpBlock;
        if (block.getLinks().size() == 0) {
            return diff0;
        }

        // ????????????link ???maxLink
        List<Address> links = block.getLinks();
        for (Address ref : links) {
            Block refBlock = getBlockByHash(ref.getHashLow(), false);
            if (refBlock == null) {
                break;
            }
            // ???????????????????????????epoch ??????????????????????????????
            if (XdagTime.getEpoch(refBlock.getTimestamp()) < XdagTime.getEpoch(block.getTimestamp())) {
                // ????????????????????????????????????
                BigInteger refDifficulty = refBlock.getInfo().getDifficulty();
                if (refDifficulty == null) {
                    refDifficulty = BigInteger.ZERO;
                }
                BigInteger curDiff = refDifficulty.add(diff0);
                if (curDiff.compareTo(maxDiff) > 0) {
                    maxDiff = curDiff;
                    maxDiffLink = ref;
                }
            } else {
                // ???????????????diff
                // 1. ????????????epoch???maxDiff+diff0
                // 2. ??????epoch???maxDiff
                tmpBlock = refBlock; // tmpBlock???link??????
                BigInteger curDiff = refBlock.getInfo().getDifficulty();
                while ((tmpBlock != null)
                        && XdagTime.getEpoch(tmpBlock.getTimestamp()) == XdagTime.getEpoch(block.getTimestamp())) {
                    tmpBlock = getMaxDiffLink(tmpBlock, false);
                }
                if (tmpBlock != null
                        && (XdagTime.getEpoch(tmpBlock.getTimestamp()) < XdagTime.getEpoch(block.getTimestamp()))
                        && tmpBlock.getInfo().getDifficulty().add(diff0).compareTo(curDiff) > 0
                ) {
                    curDiff = tmpBlock.getInfo().getDifficulty().add(diff0);
                }
                if (curDiff == null) {
                    curDiff = BigInteger.ZERO;
                }
                if (curDiff.compareTo(maxDiff) > 0) {
                    maxDiff = curDiff;
                    maxDiffLink = ref;
                }
            }
        }

        block.getInfo().setDifficulty(maxDiff);

        if (maxDiffLink != null) {
            block.getInfo().setMaxDiffLink(maxDiffLink.getHashLow().toArray());
        }
        return maxDiff;
    }

    public BigInteger getDiffByRandomXHash(Block block) {
        long epoch = XdagTime.getEpoch(block.getTimestamp());
//        byte[] data = new byte[64];
        MutableBytes data = MutableBytes.create(64);
//        Bytes32 rxHash = Hash.sha256(Bytes.wrap(BytesUtils.subArray(block.getXdagBlock().getData(),0,512-32)));
        Bytes32 rxHash = Hash.sha256(block.getXdagBlock().getData().slice(0, 512 - 32));
//        System.arraycopy(rxHash.toArray(),0,data,0,32);
        data.set(0, rxHash);
//        System.arraycopy(block.getXdagBlock().getField(15).getData().toArray(),0,data,32,32);
        data.set(32, block.getXdagBlock().getField(15).getData());
//        byte[] hash = Arrays.reverse(randomXUtils.randomXBlockHash(data.toArray(), data.size(), epoch));
        // Fix: paulochen ?????? 210729
        if (randomXUtils.randomXBlockHash(data.toArray(), data.size(), epoch) != null) {
            Bytes32 hash = Bytes32
                    .wrap(Arrays.reverse(randomXUtils.randomXBlockHash(data.toArray(), data.size(), epoch)));
            return getDiffByRawHash(hash);

        }
//        Bytes32 hash = Bytes32.wrap(Arrays.reverse(randomXUtils.randomXBlockHash(data.toArray(), data.size(), epoch)));
//        if (hash != null) {
//            return getDiffByRawHash(hash);
//        }
        return getDiffByRawHash(block.getHash());
    }

    public BigInteger getDiffByRawHash(Bytes32 hash) {
        return getDiffByHash(hash);
    }

    // ADD: ?????????-????????????????????????
    public Block getBlockByHeightNew(long height) {
        // TODO: if snapshto enabled, need height > snapshotHeight - 128
        if (kernel.getConfig().getSnapshotSpec().isSnapshotEnabled() && (height < snapshotHeight - 128)) {
            return null;
        }
        // ??????????????????0????????????
        if (height > xdagStats.nmain || height <= 0) {
            return null;
        }
        return blockStore.getBlockByHeight(height);
    }

    // REMOVE: ?????????-????????????????????????
    public Block getBlockByHeightOrigin(long height) {
        // TODO: if snapshto enabled, need height > snapshotHeight - 128
        if (kernel.getConfig().getSnapshotSpec().isSnapshotEnabled() && (height < snapshotHeight - 128)) {
            return null;
        }

        if (height > xdagStats.nmain) {
            return null;
        }

        Block block;
        int i = 0;
        for (block = getBlockByHash(Bytes32.wrap(xdagTopStatus.getTop()), false);
                block != null && (i < xdagStats.nmain);
                block = getBlockByHash(Bytes32.wrap(block.getInfo().getMaxDiffLink()), false)) {
            if ((block.getInfo().getFlags() & BI_MAIN) != 0) {
                if (height == block.getInfo().getHeight()) {
                    break;
                }
                ++i;
            }
        }
        return block;
    }

    @Override
    public Block getBlockByHeight(long height) {
        // ADD: ?????????????????????
        return getBlockByHeightNew(height);
//        return getBlockByHeightOrigin(height);
    }

    @Override
    public Block getBlockByHash(Bytes32 hashlow, boolean isRaw) {
        if (hashlow == null) {
            return null;
        }
        ByteArrayWrapper key = new ByteArrayWrapper(hashlow.toArray());
        Block b = memOrphanPool.get(key);
        if (b == null) {
            b = blockStore.getBlockByHash(hashlow, isRaw);
        }
        return b;
    }

    public Block getMaxDiffLink(Block block, boolean isRaw) {
        if (block.getInfo().getMaxDiffLink() != null) {
            return getBlockByHash(Bytes32.wrap(block.getInfo().getMaxDiffLink()), isRaw);
        }
        return null;
    }

    public void removeOrphan(Bytes32 hashlow, OrphanRemoveActions action) {
        Block b = getBlockByHash(hashlow, false);
        // TODO: snapshot
        if (b.getInfo().isSnapshot()) {
            return;
        }
        if (b != null && ((b.getInfo().flags & BI_REF) == 0) && (action != OrphanRemoveActions.ORPHAN_REMOVE_EXTRA
                || (b.getInfo().flags & BI_EXTRA) != 0)) {
            // ??????removeBlock???BI_EXTRA
            if ((b.getInfo().flags & BI_EXTRA) != 0) {
//                log.debug("??????Extra");
                // ???removeBlockInfo???????????????
                // ???MemOrphanPool?????????
                ByteArrayWrapper key = new ByteArrayWrapper(b.getHashLow().toArray());
                Block removeBlockRaw = memOrphanPool.get(key);
                memOrphanPool.remove(key);
                if (action != OrphanRemoveActions.ORPHAN_REMOVE_REUSE) {
                    // ???????????????
                    saveBlock(removeBlockRaw);
                    memOrphanPool.remove(key);
                    // ????????????EXTRA???????????????
                    if (removeBlockRaw != null) {
                        List<Address> all = removeBlockRaw.getLinks();
                        for (Address addr : all) {
                            removeOrphan(addr.getHashLow(), OrphanRemoveActions.ORPHAN_REMOVE_NORMAL);
                        }
                    }
                }
                // ??????removeBlockRaw???flag
                // nextra???1
                updateBlockFlag(removeBlockRaw, BI_EXTRA, false);
                xdagStats.nextra--;
            } else {
                orphanPool.deleteByHash(b.getHashLow().toArray());
                xdagStats.nnoref--;
            }
            // ??????????????????flag
            updateBlockFlag(b, BI_REF, true);
        }
    }

    public void updateBlockFlag(Block block, byte flag, boolean direction) {
        if (block == null) {
            return;
        }
        if (direction) {
            block.getInfo().setFlags(block.getInfo().flags |= flag);
        } else {
            block.getInfo().setFlags(block.getInfo().flags &= ~flag);
        }
        if (block.isSaved) {
            blockStore.saveBlockInfo(block.getInfo());
        }
    }

    public void updateBlockRef(Block block, Address ref) {
        if (ref == null) {
            block.getInfo().setRef(null);
        } else {
            block.getInfo().setRef(ref.getHashLow().toArray());
        }
        if (block.isSaved) {
            blockStore.saveBlockInfo(block.getInfo());
        }
    }

    public void saveBlock(Block block) {
        if (block == null) {
            return;
        }
        block.isSaved = true;
        blockStore.saveBlock(block);
        // ????????????????????????
        if (memOurBlocks.containsKey(new ByteArrayWrapper(block.getHash().toArray()))) {
//            log.info("new account:{}", Hex.toHexString(block.getHash()));
            if (xdagStats.getOurLastBlockHash() == null) {
//                log.info("Global miner");
                xdagStats.setGlobalMiner(block.getHash().toArray());
                blockStore.saveXdagStatus(xdagStats);
            }
            addOurBlock(memOurBlocks.get(new ByteArrayWrapper(block.getHash().toArray())), block);
            memOurBlocks.remove(new ByteArrayWrapper(block.getHash().toArray()));
        }

        if (block.isPretopCandidate()) {
            xdagTopStatus.setPreTop(block.getHashLow().toArray());
            xdagTopStatus.setPreTopDiff(block.getPretopCandidateDiff());
            blockStore.saveXdagTopStatus(xdagTopStatus);
        }

    }

    public boolean isExtraBlock(Block block) {
        return (block.getTimestamp() & 0xffff) == 0xffff && block.getNonce() != null && !block.isSaved();
    }

    @Override
    public XdagStats getXdagStats() {
        return this.xdagStats;
    }

    public boolean canUseInput(Block block) {
//        boolean canUse = false;
        List<ECKeyPair> ecKeys = block.verifiedKeys();
        List<Address> inputs = block.getInputs();
        if (inputs == null || inputs.size() == 0) {
            return true;
        }
        for (Address in : inputs) {
            if (!verifySignature(in, ecKeys)) {
                return false;
            }
        }
        return true;
    }

    private boolean verifySignature(Address in, List<ECKeyPair> ecKeys) {
        // TODO: ??????in?????????snapshot?????????, ??????isRaw???false???????????????blockinfo
        Block block = getBlockByHash(in.getHashLow(), false);
        boolean isSnapshotBlock = block.getInfo().isSnapshot();
        if (isSnapshotBlock) {
            return verifySignatureFromSnapshot(in, ecKeys);
        } else {
            Block inBlock = getBlockByHash(in.getHashLow(), true);
            MutableBytes subdata = inBlock.getSubRawData(inBlock.getOutsigIndex() - 2);
//            log.debug("verify encoded:{}", Hex.toHexString(subdata));
            ECDSASignature sig = inBlock.getOutsig();
            return verifySignature(subdata, sig, ecKeys);
        }
    }

    // TODO: ????????????snapshot??????????????????????????????snapshot????????????????????????
    private boolean verifySignatureFromSnapshot(Address in, List<ECKeyPair> ecKeyPairs) {
        BlockInfo blockInfo = blockStore.getBlockInfoByHash(in.getHashLow()).getInfo();
        SnapshotInfo snapshotInfo = blockInfo.getSnapshotInfo();
        if (snapshotInfo.getType()) {
            BigInteger xBn = Bytes.wrap(snapshotInfo.getData()).slice(1, 32).toUnsignedBigInteger();
            boolean yBit = snapshotInfo.getData()[0] == 0x03;
            ECPoint point = Sign.decompressKey(xBn, yBit);
            // ??????????????????????????? ??????
            byte[] encodePub = point.getEncoded(false);
            ECKeyPair targetECKey = new ECKeyPair(null,
                    new BigInteger(1, java.util.Arrays.copyOfRange(encodePub, 1, encodePub.length)));
            for (ECKeyPair ecKeyPair : ecKeyPairs) {
                if (ecKeyPair.getPublicKey().compareTo(targetECKey.getPublicKey()) == 0) {
                    return true;
                }
            }
            return false;
        } else {
            Block block = getBlockByHash(in.getHashLow(), false);
            block.setXdagBlock(new XdagBlock(snapshotInfo.getData()));
            block.setParsed(false);
            block.parse();
            MutableBytes subdata = block.getSubRawData(block.getOutsigIndex() - 2);
            ECDSASignature sig = block.getOutsig();
            return verifySignature(subdata, sig, ecKeyPairs);
        }


    }

    private boolean verifySignature(MutableBytes subdata, ECDSASignature sig, List<ECKeyPair> ecKeys) {
        for (ECKeyPair ecKey : ecKeys) {
            byte[] publicKeyBytes = ecKey.getCompressPubKeyBytes();
            Bytes digest = Bytes.wrap(subdata, Bytes.wrap(publicKeyBytes));
//            log.debug("verify encoded:{}", Hex.toHexString(digest));
            Bytes32 hash = Hash.hashTwice(digest);
            if (ECKeyPair.verify(hash.toArray(), sig.toCanonicalised(), publicKeyBytes)) {
                return true;
            }
        }
        return false;
    }

    public boolean checkMineAndAdd(Block block) {
        List<ECKeyPair> ourkeys = wallet.getAccounts();
        // ????????????????????????
        ECDSASignature signature = block.getOutsig();
        // ????????????key
        for (int i = 0; i < ourkeys.size(); i++) {
            ECKeyPair ecKey = ourkeys.get(i);
            // TODO: ??????
            byte[] publicKeyBytes = ecKey.getCompressPubKeyBytes();
            Bytes digest = Bytes.wrap(block.getSubRawData(block.getOutsigIndex() - 2), Bytes.wrap(publicKeyBytes));
            Bytes32 hash = Hash.hashTwice(Bytes.wrap(digest));
//            if (ecKey.verify(hash.toArray(), signature)) { // TODO: ?????????
            if (ECKeyPair.verify(hash.toArray(), signature.toCanonicalised(), publicKeyBytes)) {
                log.debug("Validate Success");
                addOurBlock(i, block);
                return true;
            }
        }
        return false;
    }

    public void addOurBlock(int keyIndex, Block block) {
        xdagStats.setOurLastBlockHash(block.getHash().toArray());
        if (!block.isSaved()) {
            memOurBlocks.put(new ByteArrayWrapper(block.getHash().toArray()), keyIndex);
        } else {
            blockStore.saveOurBlock(keyIndex, block.getInfo().getHashlow());
        }
    }

    public void removeOurBlock(Block block) {
        if (!block.isSaved) {
            memOurBlocks.remove(new ByteArrayWrapper(block.getHash().toArray()));
        } else {
            blockStore.removeOurBlock(block.getHashLow().toArray());
        }
    }

    public long getReward(long nmain) {
        long start = getStartAmount(nmain);
        return start >> (nmain >> MAIN_BIG_PERIOD_LOG);
    }

    @Override
    public long getSupply(long nmain) {
        UnsignedLong res = UnsignedLong.valueOf(0L);
        long amount = getStartAmount(nmain);
        long current_nmain = nmain;
        while ((current_nmain >> MAIN_BIG_PERIOD_LOG) > 0) {
            res = res.plus(UnsignedLong.fromLongBits(1L << MAIN_BIG_PERIOD_LOG).times(UnsignedLong.valueOf(amount)));
            current_nmain -= 1L << MAIN_BIG_PERIOD_LOG;
            amount >>= 1;
        }
        res = res.plus(UnsignedLong.valueOf(current_nmain).times(UnsignedLong.valueOf(amount)));
        long fork_height = kernel.getConfig().getApolloForkHeight();
        if (nmain >= fork_height) {
            // add before apollo amount
            long diff = kernel.getConfig().getMainStartAmount() - kernel.getConfig().getApolloForkAmount();
            res = res.plus(UnsignedLong.valueOf(fork_height - 1).times(UnsignedLong.valueOf(diff)));
        }
        return res.longValue();
    }

    @Override
    public List<Block> getBlocksByTime(long starttime, long endtime) {
        return blockStore.getBlocksUsedTime(starttime, endtime);
    }

    @Override
    public void startCheckMain(long period) {
        if (checkLoop == null) {
            return;
        }
        checkLoopFuture = checkLoop.scheduleAtFixedRate(this::checkState, 0, period, TimeUnit.MILLISECONDS);
    }

    public void checkState() {
        // TODO:??????extra
//        checkExtra();
        checkMain();
    }

    public void checkExtra() {
        long nblk = xdagStats.nextra / 11;
        if (nblk > 0) {
            boolean b = (nblk % 61) > (RandomUtils.nextLong() % 61);
            nblk = nblk / 61 + (b ? 1 : 0);
        }
        while (nblk-- > 0) {
            Block linkBlock = createNewBlock(null, null, false, kernel.getConfig().getPoolSpec().getPoolTag());
            linkBlock.signOut(kernel.getWallet().getDefKey());
            ImportResult result = this.tryToConnect(linkBlock);
            if (result == IMPORTED_NOT_BEST || result == IMPORTED_BEST) {
                onNewBlock(linkBlock);
            }
        }
    }

    public void checkMain() {
        try {
            checkNewMain();
            // checkNewMain???xdagStats?????????????????????
            blockStore.saveXdagStatus(xdagStats);
        } catch (Throwable e) {
            log.error(e.getMessage(), e);
        }
    }

    @Override
    public void stopCheckMain() {
        try {

            if (checkLoopFuture != null) {
                checkLoopFuture.cancel(true);
            }
            // ???????????????
            checkLoop.shutdownNow();
            checkLoop.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public long getStartAmount(long nmain) {
        long startAmount;
        long forkHeight = kernel.getConfig().getApolloForkHeight();
        if (nmain >= forkHeight) {
            startAmount = kernel.getConfig().getApolloForkAmount();
        } else {
            startAmount = kernel.getConfig().getMainStartAmount();
        }

        return startAmount;
    }

    /**
     * ?????????block??????amount?????? *
     */
    // TODO : accept amount to block which in snapshot
    private void acceptAmount(Block block, UnsignedLong amount) {
        block.getInfo().setAmount(UnsignedLong.valueOf(block.getInfo().getAmount()).plus(amount).longValue());
        if (block.isSaved) {
            blockStore.saveBlockInfo(block.getInfo());
        }
        if ((block.getInfo().flags & BI_OURS) != 0) {
            xdagStats.setBalance(amount.plus(UnsignedLong.valueOf(xdagStats.getBalance())).longValue());
        }
    }

    /**
     * ????????????????????????????????? *
     */
    public boolean isExist(Bytes32 hashlow) {
        return memOrphanPool.containsKey(new ByteArrayWrapper(hashlow.toArray())) ||
                blockStore.hasBlock(hashlow) || isExitInSnapshot(hashlow);
    }

    /**
     * ?????????????????????snapshot
     **/
    public boolean isExitInSnapshot(Bytes32 hashlow) {
        if (kernel.getConfig().getSnapshotSpec().isSnapshotEnabled()) {
            // ?????????????????????????????????????????????
            return blockStore.hasBlockInfo(hashlow);
        } else {
            return false;
        }
    }


    // ADD: ?????????????????????????????????
    public List<Block> listMainBlocksByHeight(int count) {
        List<Block> res = new ArrayList<>();
        long currentHeight = xdagStats.nmain;
        for (int i = 0; i < count; i++) {
            Block block = getBlockByHeightNew(currentHeight - i);
            if (block != null) {
                res.add(block);
            }
        }
        return res;
    }


    public List<Block> listMainBlocksByOrigin(int count) {
        Block temp = getBlockByHash(Bytes32.wrap(xdagTopStatus.getTop()), false);
        if (temp == null) {
            temp = getBlockByHash(Bytes32.wrap(xdagTopStatus.getPreTop()), false);
        }
        List<Block> res = new ArrayList<>();
        while (count > 0) {
            if (temp == null) {
                break;
            }
            if ((temp.getInfo().flags & BI_MAIN) != 0) {
                count--;
                res.add((Block) temp.clone());
            }
            // ??????maxdifflink
            if (temp.getInfo().getMaxDiffLink() == null) {
                break;
            }
            temp = getBlockByHash(Bytes32.wrap(temp.getInfo().getMaxDiffLink()), false);
        }
        return res;
    }

    @Override
    public List<Block> listMainBlocks(int count) {
        return listMainBlocksByHeight(count);
//        return listMainBlocksByOrigin(count);
    }

    // TODO: ???????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
    @Override
    public List<Block> listMinedBlocks(int count) {
        Block temp = getBlockByHash(Bytes32.wrap(xdagTopStatus.getTop()), false);
        if (temp == null) {
            temp = getBlockByHash(Bytes32.wrap(xdagTopStatus.getPreTop()), false);
            //                log.error("Pretop is null");
        }
        List<Block> res = new ArrayList<>();
        while (count > 0) {
            if (temp == null) {
                break;
            }
            if ((temp.getInfo().flags & BI_MAIN) != 0 && (temp.getInfo().flags & BI_OURS) != 0) {
                count--;
                res.add((Block) temp.clone());
            }
            if (temp.getInfo().getMaxDiffLink() == null) {
                break;
            }
            temp = getBlockByHash(Bytes32.wrap(temp.getInfo().getMaxDiffLink()), false);
        }
        return res;
    }

    public Map<ByteArrayWrapper, Integer> getMemOurBlocks() {
        return memOurBlocks;
    }

    enum OrphanRemoveActions {
        ORPHAN_REMOVE_NORMAL, ORPHAN_REMOVE_REUSE, ORPHAN_REMOVE_EXTRA
    }
}
