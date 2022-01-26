package io.xdag.snapshot.db;

import static io.xdag.config.Constants.BI_APPLIED;
import static io.xdag.config.Constants.BI_MAIN_REF;
import static io.xdag.config.Constants.BI_OURS;
import static io.xdag.config.Constants.BI_REF;
import static io.xdag.snapshot.config.SnapShotKeys.SNAPSHOT_KEY_STATS_MAIN;
import static io.xdag.snapshot.config.SnapShotKeys.SNAPSHOT_PRE_SEED;
import static io.xdag.snapshot.config.SnapShotKeys.getMutableBytesByKey;
import static io.xdag.snapshot.config.SnapShotKeys.getMutableBytesByKey_;
import static io.xdag.utils.BasicUtils.amount2xdag;
import static io.xdag.utils.BasicUtils.getHashlowByHash;
import static java.nio.ByteBuffer.allocateDirect;
import static org.lmdbjava.DbiFlags.MDB_CREATE;
import static org.lmdbjava.DbiFlags.MDB_INTEGERKEY;
import static org.lmdbjava.Env.create;
import static org.lmdbjava.EnvFlags.MDB_FIXEDMAP;
import static org.lmdbjava.EnvFlags.MDB_NOSYNC;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.xdag.core.Block;
import io.xdag.core.XdagBlock;
import io.xdag.crypto.ECDSASignature;
import io.xdag.crypto.ECKeyPair;
import io.xdag.crypto.Hash;
import io.xdag.db.KVSource;
import io.xdag.db.execption.DeserializationException;
import io.xdag.db.execption.SerializationException;
import io.xdag.snapshot.core.BalanceData;
import io.xdag.snapshot.core.SnapshotUnit;
import io.xdag.snapshot.core.StatsBlock;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.FileUtils;
import io.xdag.utils.Numeric;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;
import org.bouncycastle.util.Arrays;
import org.bouncycastle.util.encoders.Hex;
import org.lmdbjava.CursorIterable;
import org.lmdbjava.CursorIterable.KeyVal;
import org.lmdbjava.Dbi;
import org.lmdbjava.Env;
import org.lmdbjava.KeyRange;
import org.lmdbjava.Txn;
import org.xerial.snappy.Snappy;

@Slf4j
public class SnapshotChainStoreImpl implements SnapshotChainStore {

    public static final byte SNAPSHOT_UNIT = 0x10;
    public static final byte SNAPSHOT_STATS = 0x20;
    public static final byte SNAPSHOT_GLOBAL_BALANCE = 0x30;
    public static final byte PRE_SEED = 0x40;
    public static final String BALACNE_KEY = "balance";
    public static final String STATS_KEY = "stats";
    public static final String PUB_KEY = "pubkey";
    public static final String SIG_KEY = "signature";
    public static final String BLOCK_KEY = "block";

    private final Kryo kryo;

    /**
     * <prefix-key,value>
     */
    private final KVSource<byte[], byte[]> snapshotSource;

    public SnapshotChainStoreImpl(KVSource<byte[], byte[]> snapshotSource) {
        this.snapshotSource = snapshotSource;
        this.kryo = new Kryo();
        kryoRegister();
    }

    private void kryoRegister() {
        kryo.register(SnapshotUnit.class);
        kryo.register(BalanceData.class);
        kryo.register(StatsBlock.class);
        kryo.register(byte[].class);
        kryo.register(BigInteger.class);
    }

    private byte[] serialize(final Object obj) throws SerializationException {
        synchronized (kryo) {
            try {
                final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                final Output output = new Output(outputStream);
                kryo.writeObject(output, obj);
                output.flush();
                output.close();
                return outputStream.toByteArray();
            } catch (final IllegalArgumentException | KryoException exception) {
                throw new SerializationException(exception.getMessage(), exception);
            }
        }
    }

    private Object deserialize(final byte[] bytes, Class<?> type) throws DeserializationException {
        synchronized (kryo) {
            try {
                final ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
                final Input input = new Input(inputStream);
                return kryo.readObject(input, type);
            } catch (final IllegalArgumentException | KryoException | NullPointerException exception) {
                log.debug("Deserialize data:{}", Hex.toHexString(bytes));
                throw new DeserializationException(exception.getMessage(), exception);
            }
        }
    }

    @Override
    public void init() {
        snapshotSource.init();
    }

    @Override
    public void reset() {
        snapshotSource.reset();
    }

    @Override
    public void saveSnapshotUnit(byte[] hashOrHashlow, SnapshotUnit snapshotUnit) {
        byte[] value = null;
        try {
            value = serialize(snapshotUnit);
        } catch (SerializationException e) {
            log.error(e.getMessage(), e);
        }
        snapshotSource.put(BytesUtils.merge(SNAPSHOT_UNIT, getHashlowByHash(hashOrHashlow)), value);
    }

    @Override
    public void saveGlobalBalance(long balance) {
        snapshotSource.put(new byte[]{SNAPSHOT_GLOBAL_BALANCE}, BytesUtils.longToBytes(balance, false));
    }

    @Override
    public long getGlobalBalance() {
        if (snapshotSource.get(new byte[]{SNAPSHOT_GLOBAL_BALANCE}) == null) {
            return 0;
        } else {
            return BytesUtils.bytesToLong(snapshotSource.get(new byte[]{SNAPSHOT_GLOBAL_BALANCE}), 0, false);
        }
    }

    @Override
    public SnapshotUnit getSnapshotUnit(byte[] hashOrHashlow) {
        SnapshotUnit snapshotUnit = null;
        byte[] data = snapshotSource.get(BytesUtils.merge(SNAPSHOT_UNIT, getHashlowByHash(hashOrHashlow)));
        if (data == null) {
            return null;
        }
        try {
            snapshotUnit = (SnapshotUnit) deserialize(data, SnapshotUnit.class);
        } catch (DeserializationException e) {
            log.error(e.getMessage(), e);
        }
        return snapshotUnit;
    }

    @Override
    public List<SnapshotUnit> getAllSnapshotUnit() {
        List<SnapshotUnit> snapshotUnits = new ArrayList<>();
        List<byte[]> datas = snapshotSource.prefixValueLookup(new byte[]{SNAPSHOT_UNIT});
        for (byte[] data : datas) {
            SnapshotUnit snapshotUnit;
            try {
                snapshotUnit = (SnapshotUnit) deserialize(data, SnapshotUnit.class);
                snapshotUnits.add(snapshotUnit);
            } catch (DeserializationException e) {
                log.error(e.getMessage(), e);
            }
        }
        return snapshotUnits;
    }

    public List<StatsBlock> getSnapshotStatsBlock() {
        List<StatsBlock> statsBlocks = new ArrayList<>();
        List<byte[]> datas = snapshotSource.prefixValueLookup(new byte[]{SNAPSHOT_STATS});
        for (byte[] data : datas) {
            StatsBlock statsBlock;
            try {
                statsBlock = (StatsBlock) deserialize(data, StatsBlock.class);
                statsBlocks.add(statsBlock);
            } catch (DeserializationException e) {
                log.error(e.getMessage(), e);
            }
        }
        return statsBlocks;
    }

    public void saveSnapshotPreSeed(Bytes bytes) {
        System.out.println("pre seed:" + bytes);
        snapshotSource.put(new byte[]{PRE_SEED}, bytes.toArray());
    }

    public byte[] getSnapshotPreSeed() {
        return snapshotSource.get(new byte[]{PRE_SEED});
    }

    public void saveSnaptshotStatsBlock(int i, StatsBlock statsBlock) {
        byte[] value = null;
        try {
            value = serialize(statsBlock);
        } catch (SerializationException e) {
            log.error(e.getMessage(), e);
        }
        snapshotSource.put(BytesUtils.merge(SNAPSHOT_STATS, BytesUtils.intToBytes(i, false)), value);
    }

    public StatsBlock getStatsBlockByIndex(int i) {
        StatsBlock statsBlock = null;
        byte[] data = snapshotSource.get(BytesUtils.merge(SNAPSHOT_STATS, BytesUtils.intToBytes(i, false)));
        if (data == null) {
            return null;
        }
        try {
            statsBlock = (StatsBlock) deserialize(data, StatsBlock.class);
        } catch (DeserializationException e) {
            log.error(e.getMessage(), e);
        }
        return statsBlock;
    }

    public StatsBlock getLatestStatsBlock() {
        return getStatsBlockByIndex(0);
    }


    /**
     * 从lmdb文件中加载到rocksdb
     *
     * @param filePath
     * @param mainLag 用于判断是主网的randomx lag还是测试网的randomx_lag 暂时固定128
     * @return
     */
    public boolean loadFromSnapshotData(String filePath, boolean mainLag, List<ECKeyPair> ecKeyPairs) {
        // 1. 初始保存
        Set<Bytes32> set = new HashSet<>();
        Map<Bytes32, BalanceData> balanceDataMap = new HashMap<>();
        Map<Bytes32, byte[]> ecKeyPairHashMap = new HashMap<>();
        Map<Bytes32, ECDSASignature> signatureHashMap = new HashMap<>();
        Map<Bytes32, Block> blockHashMap = new HashMap<>();
        // 初始余额
        long ourBalance = 0;

        // 2. 查找snapshot文件
        String balanceFilePath = filePath + "/balance";
        String pubFilePath = filePath + "/pubkey";
        List<File> dirs = FileUtils.listFilesInDirWithFilter(balanceFilePath, "data.mdb", true);
        List<File> pubDirs = FileUtils.listFilesInDirWithFilter(pubFilePath, "data.mdb", true);
        File balanceFile = dirs.get(0).getParentFile();
        File pubFile = pubDirs.get(0).getParentFile();

        // 3. 读取lmdb文件
        Env<ByteBuffer> env = create()
                .setMaxReaders(8)
                .setMapSize(268435456)
                .setMaxDbs(4)
                .open(balanceFile, MDB_FIXEDMAP, MDB_NOSYNC);
        Env<ByteBuffer> env_pub = create()
                .setMaxReaders(1)
                .setMapSize(268435456)
                .setMaxDbs(4)
                .open(pubFile, MDB_NOSYNC);

        final Dbi<ByteBuffer> balance_db = env.openDbi(BALACNE_KEY, MDB_CREATE, MDB_INTEGERKEY);
        Txn<ByteBuffer> balance_txn = env.txnRead();

        // balance
        try (CursorIterable<ByteBuffer> ci = balance_db.iterate(balance_txn, KeyRange.all())) {
            for (final KeyVal<ByteBuffer> kv : ci) {
                BalanceData data = BalanceData.parse(Bytes.wrapByteBuffer(kv.key()), Bytes.wrapByteBuffer(kv.val()));
                //
                set.add(Bytes32.wrap(data.getHash()));
                balanceDataMap.put(Bytes32.wrap(data.getHash()), data);
            }
        } catch (Exception e) {
            env.close();
            env_pub.close();
            return false;
        } finally {
            balance_txn.close();
        }

        // pub
        final Dbi<ByteBuffer> pub_db = env_pub.openDbi(PUB_KEY, MDB_CREATE);
        Txn<ByteBuffer> pub_txn = env_pub.txnRead();
        try (CursorIterable<ByteBuffer> ci = pub_db.iterate(pub_txn, KeyRange.all())) {

            for (final KeyVal<ByteBuffer> kv : ci) {
                Bytes ecKeyPair = Bytes.wrapByteBuffer(kv.val());
                set.add(Bytes32.wrap(Arrays.reverse(Bytes.wrapByteBuffer(kv.key()).toArray())));
                ecKeyPairHashMap.put(Bytes32.wrap(Arrays.reverse(Bytes.wrapByteBuffer(kv.key()).toArray())),
                        ecKeyPair.toArray());
            }
        } catch (Exception e) {
            env.close();
            env_pub.close();
            return false;
        } finally {
            pub_txn.close();
        }

        final Dbi<ByteBuffer> sig_db = env_pub.openDbi(SIG_KEY, MDB_CREATE);
        Txn<ByteBuffer> sig_txn = env_pub.txnRead();
        try (CursorIterable<ByteBuffer> ci = sig_db.iterate(sig_txn, KeyRange.all())) {
            for (final KeyVal<ByteBuffer> kv : ci) {
                BigInteger r;
                BigInteger s;
                r = Numeric.toBigInt(Bytes.wrapByteBuffer(kv.val()).slice(0, 32).toArray());
                s = Numeric.toBigInt(Bytes.wrapByteBuffer(kv.val()).slice(32, 32).toArray());
                ECDSASignature ecdsaSignature = new ECDSASignature(r, s);
                //
                set.add(Bytes32.wrap(Arrays.reverse(Bytes.wrapByteBuffer(kv.key()).toArray())));
                signatureHashMap
                        .put(Bytes32.wrap(Arrays.reverse(Bytes.wrapByteBuffer(kv.key()).toArray())), ecdsaSignature);
            }
        } catch (Exception e) {
            env.close();
            env_pub.close();
            return false;
        } finally {
            sig_txn.close();
        }

        final Dbi<ByteBuffer> block_db = env_pub.openDbi(BLOCK_KEY, MDB_CREATE);
        Txn<ByteBuffer> block_txn = env_pub.txnRead();
        try (CursorIterable<ByteBuffer> ci = block_db.iterate(block_txn, KeyRange.all())) {
            for (final KeyVal<ByteBuffer> kv : ci) {
                Bytes bytes = Bytes.wrapByteBuffer(kv.val());
                byte[] uncompress = Snappy.uncompress(bytes.toArray());
                Block block = new Block(new XdagBlock(uncompress));
                //
                set.add(Bytes32.wrap(block.getHash()));
                blockHashMap.put(Bytes32.wrap(block.getHash()), block);
            }
        } catch (Exception e) {
            env.close();
            env_pub.close();
            return false;
        } finally {
            block_txn.close();
        }

        // 4. 组装
        for (Bytes32 bytes32 : set) {
            BalanceData balanceData = balanceDataMap.get(bytes32);
            byte[] ecKeyPair = ecKeyPairHashMap.get(bytes32);
            ECDSASignature signature = signatureHashMap.get(bytes32);
            Block block = blockHashMap.get(bytes32);
            if (balanceData == null) {
                balanceData = new BalanceData();
                // 没钱有交易的区块认为已经被accepted
                balanceData.setFlags(BI_REF | BI_MAIN_REF | BI_APPLIED);
                if (ecKeyPair != null) {
                    balanceData.setHash(bytes32.toArray());
                }
            }
            byte[] data = null;
            if (block == null && signature != null) {
                data = createXdagBlock(signature, balanceData);
            } else if (block != null) {
                data = block.getXdagBlock().getData().toArray();
            }

            // 4.1 如果公钥存在，对比公钥
            if (ecKeyPair != null) {
                for (ECKeyPair key : ecKeyPairs) {
                    // 如果有相等的说明是我们的区块
                    if (Bytes.wrap(key.getCompressPubKeyBytes()).compareTo(Bytes.wrap(ecKeyPair)) == 0) {
                        int flag = balanceData.getFlags();
                        flag |= BI_OURS;
                        balanceData.setFlags(flag);
                        // TODO: 添加我们的balance
                        ourBalance += balanceData.getAmount();
                    }
                }
            } else { // 4.2 否则对比签名
                Block tmpBlock = new Block(new XdagBlock(data));
                ECDSASignature outsig = tmpBlock.getOutsig();
                for (int i = 0; i < ecKeyPairs.size(); i++) {
                    ECKeyPair ecKey = ecKeyPairs.get(i);
                    byte[] publicKeyBytes = ecKey.getCompressPubKeyBytes();
                    Bytes digest = Bytes
                            .wrap(tmpBlock.getSubRawData(tmpBlock.getOutsigIndex() - 2), Bytes.wrap(publicKeyBytes));
                    Bytes32 hash = Hash.hashTwice(Bytes.wrap(digest));
//                    if (ecKey.verify(hash.toArray(), outsig)) { //verify耗时较长
                    if (ECKeyPair.verify(hash.toArray(), outsig.toCanonicalised(), publicKeyBytes)) { // 耗时短点
                        int flag = balanceData.getFlags();
                        flag |= BI_OURS;
                        balanceData.setFlags(flag);
//                         TODO: 添加我们的balance
                        ourBalance += balanceData.getAmount();
                    }
                }
            }

            // 4.1 保存snapshot单元用于生成blockinfo
            saveSnapshotUnit(bytes32.toArray(), new SnapshotUnit(
                    ecKeyPair, balanceData, data,
                    bytes32.toArray()));
        }
        // 保存balance
        saveGlobalBalance(ourBalance);
        System.out.println("快照点时 余额为：" + amount2xdag(ourBalance));

        final Dbi<ByteBuffer> stats_db = env.openDbi(STATS_KEY, MDB_CREATE);
        Txn<ByteBuffer> stats_txn = env.txnRead();

        ByteBuffer snapshotHeightKey = allocateDirect(getMutableBytesByKey(SNAPSHOT_KEY_STATS_MAIN).size());
        snapshotHeightKey.put(getMutableBytesByKey(SNAPSHOT_KEY_STATS_MAIN).toArray()).flip();
        ByteBuffer snapshotHeight = stats_db.get(stats_txn, snapshotHeightKey);

        StatsBlock snapshotHeightBlock = StatsBlock
                .parse(Bytes.wrapByteBuffer(snapshotHeightKey), Bytes.wrapByteBuffer(snapshotHeight));
        saveSnaptshotStatsBlock(0, snapshotHeightBlock);

        for (int i = 1; i <= 128; i++) {
            String key = SNAPSHOT_KEY_STATS_MAIN + "_" + i;
            ByteBuffer snapshotey = allocateDirect(getMutableBytesByKey_(key).size());
            snapshotey.put(getMutableBytesByKey_(key).toArray()).flip();
            ByteBuffer snapshot = stats_db.get(stats_txn, snapshotey);
            // 4.2 保存statsblock用于randomx
            saveSnaptshotStatsBlock(i, StatsBlock
                    .parse(Bytes.wrapByteBuffer(snapshotey), Bytes.wrapByteBuffer(snapshot)));
        }

        String key = SNAPSHOT_PRE_SEED;
        ByteBuffer snapshotey = allocateDirect(getMutableBytesByKey_(key).size());
        snapshotey.put(getMutableBytesByKey_(key).toArray()).flip();
        ByteBuffer snapshot = stats_db.get(stats_txn, snapshotey);
        saveSnapshotPreSeed(Bytes.wrapByteBuffer(snapshot));

        stats_txn.close();
        env.close();
        env_pub.close();
        return true;
    }

    private byte[] createXdagBlock(ECDSASignature signature, BalanceData balanceData) {
        MutableBytes mutableBytes = MutableBytes.create(512);
        byte[] transportHeader = BytesUtils.longToBytes(0, true);
        byte[] type = BytesUtils.longToBytes(1368, true);
        byte[] time = BytesUtils.longToBytes(balanceData.getTime(), true);
        byte[] fee = BytesUtils.longToBytes(0, true);
        byte[] sig = BytesUtils.subArray(signature.toByteArray(), 0, 64);
        mutableBytes.set(0, Bytes.wrap(transportHeader));
        mutableBytes.set(8, Bytes.wrap(type));
        mutableBytes.set(16, Bytes.wrap(time));
        mutableBytes.set(24, Bytes.wrap(fee));
        mutableBytes.set(32, Bytes.wrap(sig));
        return mutableBytes.toArray();
    }
}
