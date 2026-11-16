package io.github.leohou0430.llmgw.persistence.sqlite;

import io.github.leohou0430.llmgw.core.governance.UsageLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SqliteUsageLedgerTest {
    @TempDir Path directory;
    private static void schema(SqliteContext db) throws Exception {
        db.transaction(c -> {try(var s=c.createStatement()){for(String ddl:SqliteUsageLedger.SCHEMA)s.execute(ddl);}return null;});
    }
    private static UsageLedger.Reservation reservation(String id,String key,long tokens) {
        return new UsageLedger.Reservation(id,key,"t","p",tokens,100);
    }
    private static UsageLedger.UsageRecord usage(String key,int prompt,int completion) {
        return new UsageLedger.UsageRecord(key,"model",prompt,completion,1,true,Instant.now(),5);
    }
    @Test void restartRetainsUnknownAndSettlementIsIdempotent() throws Exception {
        Path file=directory.resolve("quota.db");
        try(var db=new SqliteContext(file,1000)) {
            schema(db);var ledger=new SqliteUsageLedger(db);
            ledger.setProjectQuota("t","p",10,"admin","initial allowance");
            assertTrue(ledger.tryReserve(reservation("a","k1",5)));
            ledger.settle("a",usage("k1",1,1),false);
            assertEquals(1,ledger.snapshot().get("k1").errors());
            assertEquals(5,ledger.used("k1"));
        }
        try(var db=new SqliteContext(file,1000)) {
            var ledger=new SqliteUsageLedger(db);
            assertEquals(5,ledger.projectUsed("t","p"));
            assertFalse(ledger.tryReserve(reservation("b","k2",6)));
            ledger.settle("a",usage("k1",1,1),true);
            ledger.settle("a",usage("k1",8,8),true);
            assertEquals(2,ledger.used("k1"));
            assertEquals(1,ledger.snapshot().get("k1").requests());
            assertEquals(1,ledger.snapshot("t","p").get("k1").requests());
            assertTrue(ledger.snapshot("other","p").isEmpty());
            assertTrue(ledger.tryReserve(reservation("b","k2",8)));
            ledger.setProjectQuota("t","p",1,"admin","reduce allowance");
            assertEquals(10,ledger.projectUsed("t","p"));
            assertFalse(ledger.tryReserve(reservation("c","new-key",1)));
        }
    }
    @Test void projectAndKeyReservationsAreAtomicAcrossConcurrentKeys() throws Exception {
        try(var db=new SqliteContext(directory.resolve("atomic.db"),1000)) {
            schema(db);var ledger=new SqliteUsageLedger(db);
            ledger.setProjectQuota("t","p",10,"admin","initial");
            ledger.setKeyQuota("limited",2,"admin","key cap");
            assertFalse(ledger.tryReserve(reservation("limited","limited",3)));
            try(var pool=Executors.newFixedThreadPool(8)) {
                var start=new CountDownLatch(1);var futures=new java.util.ArrayList<Future<Boolean>>();
                for(int i=0;i<20;i++){final int n=i;futures.add(pool.submit(()->{start.await();return ledger.tryReserve(reservation("r"+n,"k"+n,1));}));}
                start.countDown();int admitted=0;for(var result:futures)if(result.get())admitted++;
                assertEquals(10,admitted);assertEquals(10,ledger.projectUsed("t","p"));
            }
        }
    }
    @Test void unscopedConfigurationKeysRemainCompatibleButPartialScopeIsRejected() throws Exception {
        try(var db=new SqliteContext(directory.resolve("compatibility.db"),1000)) {
            schema(db);var ledger=new SqliteUsageLedger(db);
            assertTrue(ledger.tryReserve(new UsageLedger.Reservation("legacy","configured","","",3,5)));
            ledger.settle("legacy",usage("configured",1,1),true);
            assertEquals(2,ledger.used("configured"));
            assertThrows(IllegalArgumentException.class,()->ledger.tryReserve(new UsageLedger.Reservation("invalid","key","t","",1,5)));
        }
    }
    @Test void reconciliationNeverReleasesAnInflightReservation() throws Exception {
        try(var db=new SqliteContext(directory.resolve("reconcile.db"),1000)) {
            schema(db);var ledger=new SqliteUsageLedger(db);
            ledger.setProjectQuota("t","p",10,"admin","initial");
            assertTrue(ledger.tryReserve(reservation("a","key",5)));
            assertThrows(IllegalArgumentException.class,()->ledger.reconcile("t","p","a",0,0,"admin","verified"));
            assertEquals(5,ledger.projectUsed("t","p"));
            ledger.settle("a",usage("key",0,0),false);
            assertThrows(IllegalArgumentException.class,()->ledger.reconcile("other","p","a",0,0,"admin","verified"));
            ledger.reconcile("t","p","a",1,1,"admin","upstream verified");
            assertEquals(2,ledger.projectUsed("t","p"));
            assertThrows(IllegalArgumentException.class,()->ledger.reconcile("t","p","a",0,0,"admin","duplicate"));
        }
    }
    @Test void quotaAdjustmentAndItsAuditRollbackTogether() throws Exception {
        try(var db=new SqliteContext(directory.resolve("audit.db"),1000)) {
            schema(db);var ledger=new SqliteUsageLedger(db);
            db.transaction(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER fail_quota_audit BEFORE INSERT ON token_quota_audit BEGIN SELECT RAISE(ABORT,'audit unavailable'); END");}return null;});
            assertThrows(IllegalStateException.class,()->ledger.setProjectQuota("t","p",10,"admin","initial"));
            assertFalse(ledger.tryReserve(reservation("a","key",1)));
        }
    }
}
