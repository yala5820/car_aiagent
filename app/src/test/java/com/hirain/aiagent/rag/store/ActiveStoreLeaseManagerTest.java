package com.hirain.aiagent.rag.store;
import org.junit.Test;import static org.junit.Assert.*;
/** 新 Store 激活不能中断既有查询；Lease 归还后才关闭旧 Store。 */
public class ActiveStoreLeaseManagerTest {
 static final class Store implements AutoCloseable{boolean closed;@Override public void close(){closed=true;}}
 @Test public void retiresOldStoreOnlyAfterLeaseReturn(){var manager=new ActiveStoreLeaseManager<Store>();var oldStore=new Store();var nextStore=new Store();manager.activate(oldStore);var lease=manager.acquire();manager.activate(nextStore);assertFalse(oldStore.closed);lease.close();assertTrue(oldStore.closed);assertFalse(nextStore.closed);}
}
