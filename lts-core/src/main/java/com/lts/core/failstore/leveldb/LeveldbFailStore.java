package com.lts.core.failstore.leveldb;

import com.lts.core.commons.file.FileLock;
import com.lts.core.commons.file.FileUtils;
import com.lts.core.commons.utils.JSONUtils;
import com.lts.core.domain.KVPair;
import com.lts.core.failstore.FailStore;
import com.lts.core.failstore.FailStoreException;
import org.fusesource.leveldbjni.JniDBFactory;
import org.iq80.leveldb.*;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Only a single process (possibly multi-threaded) can access a particular database at a time
 * Robert HG (254963746@qq.com) on 5/21/15.
 */
public class LeveldbFailStore implements FailStore {

    // 文件锁 (同一时间只能有一个线程访问leveldb文件)
    private FileLock lock;
    /**
     * 数据库目录
     */
    private File dbPath;

    private DB db;

    private Options options;

    public LeveldbFailStore(String failStorePath) {
        failStorePath = failStorePath + "/leveldb/";
        dbPath = FileUtils.createDirIfNotExist(failStorePath);
        options = new Options();
        options.createIfMissing(true);
//        options.compressionType(CompressionType.NONE);
        options.cacheSize(100 * 1024 * 1024);   // 100M
//        options.logger(new Logger() {
//            @Override
//            public void log(String message) {
//                System.out.println(message);
//            }
//        });
        lock = new FileLock(failStorePath + "___db.lock");
    }

    @Override
    public void open() throws FailStoreException {
        try {
            lock.tryLock();
            JniDBFactory.factory.repair(dbPath, options);
            db = JniDBFactory.factory.open(dbPath, options);
        } catch (IOException e) {
            throw new FailStoreException(e);
        }
    }

    @Override
    public void put(String key, Object value) throws FailStoreException {
        try {
            String valueString = JSONUtils.toJSONString(value);
            db.put(key.getBytes("UTF-8"), valueString.getBytes("UTF-8"));
        } catch (Exception e) {
            throw new FailStoreException(e);
        }
    }

    @Override
    public void delete(String key) throws FailStoreException {
        try {
            if (key == null) {
                return;
            }
            db.delete(key.getBytes("UTF-8"));
        } catch (Exception e) {
            throw new FailStoreException(e);
        }
    }

    @Override
    public void delete(List<String> keys) throws FailStoreException {
        if (keys == null || keys.size() == 0) {
            return;
        }
        WriteBatch batch = db.createWriteBatch();
        try {

            for (String key : keys) {
                batch.delete(key.getBytes("UTF-8"));
            }
            db.write(batch);
        } catch (UnsupportedEncodingException e) {
            throw new FailStoreException(e);
        } finally {
            try {
                batch.close();
            } catch (IOException e) {
                throw new FailStoreException(e);
            }
        }
    }

    @Override
    public <T> List<KVPair<String, T>> fetchTop(int size, Type type) throws FailStoreException {
        Snapshot snapshot = db.getSnapshot();
        DBIterator iterator = null;
        try {
            List<KVPair<String, T>> list = new ArrayList<KVPair<String, T>>(size);
            ReadOptions options=new ReadOptions();
            options.snapshot(snapshot);
            iterator = db.iterator(options);
            for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
                Map.Entry<byte[], byte[]> entry = iterator.peekNext();
                String key = new String(entry.getKey(), "UTF-8");
                T value = JSONUtils.parse(new String(entry.getValue(), "UTF-8"), type);
                KVPair<String, T> pair = new KVPair<String, T>(key, value);
                list.add(pair);
                if (list.size() >= size) {
                    break;
                }
            }
            return list;
        } catch (Exception e) {
            throw new FailStoreException(e);
        } finally {
            if (iterator != null) {
                try {
                    iterator.close();
                } catch (IOException e) {
                    throw new FailStoreException(e);
                }
            }
            try {
                snapshot.close();
            } catch (IOException e) {
                throw new FailStoreException(e);
            }
        }
    }

    @Override
    public void close() throws FailStoreException {
        try {
            if (db != null) {
                db.close();
            }
        } catch (IOException e) {
            throw new FailStoreException(e);
        } finally {
            lock.release();
        }
    }

    public void destroy() throws FailStoreException {
        try {
            JniDBFactory.factory.destroy(dbPath, options);
        } catch (IOException e) {
            throw new FailStoreException(e);
        } finally {
            lock.delete();
        }
    }
}
