import io.github.leohou0430.llmgw.control.local.*;
import io.github.leohou0430.llmgw.persistence.*;
import io.github.leohou0430.llmgw.persistence.sqlite.*;
import io.github.leohou0430.llmgw.core.governance.*;
import io.github.leohou0430.llmgw.app.*;
import io.github.leohou0430.llmgw.catalog.*;
import io.github.leohou0430.llmgw.catalog.local.*;
import java.nio.file.*;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

class StructuralProbe {
  static ResourceEntry entry(String id) {
    return new ResourceEntry(id,"p","file",id,"route","mock","1",System.currentTimeMillis(),"ready",Map.of(),0L,false);
  }
  public static void main(String[] args) throws Exception {
    var routes = new RouteVersionService(new InMemoryRouteVersionStore());
    routes.saveDraft("request-A");
    routes.saveDraft("request-B");
    System.out.println("draft_A_publish_result=" + routes.publish(0).config());
    Path db = Path.of(args[0], "concurrency2.db");
    try (var ctx = new SqliteContext(db, 2000)) {
      new SchemaMigrator(ctx).migrate(Migrations.all());
      var begun = new CountDownLatch(1);
      var written = new CountDownLatch(1);
      Connection connection = ctx.connection();
      Connection blocked = (Connection) Proxy.newProxyInstance(StructuralProbe.class.getClassLoader(),
        new Class<?>[]{Connection.class}, (proxy, method, params) -> {
          if (method.getName().equals("prepareStatement") && ((String) params[0]).startsWith("INSERT INTO key_store")) {
            begun.countDown();
            if (!written.await(10, TimeUnit.SECONDS)) throw new SQLException("probe timeout");
            throw new SQLException("injected key write failure");
          }
          try { return method.invoke(connection, params); }
          catch (InvocationTargetException e) { throw e.getCause(); }
        });
      DatabaseContext wrapped = new DatabaseContext() {
        public Connection connection() { return blocked; }
        public void close() { }
      };
      var keys = new SqliteKeyStore(wrapped, true);
      Thread keyWrite = Thread.ofVirtual().start(() -> {
        try { keys.upsert("probe-token", new ApiKeyRegistry.ApiKey("k", "k", 1000, Set.of())); }
        catch (IllegalStateException expected) { System.out.println("key_write_failed=true"); }
      });
      if (!begun.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("not begun");
      new OrgStore(ctx).createTenant("successful-other-request", "tenant");
      System.out.println("org_write_returned_success=true");
      written.countDown(); keyWrite.join();
      System.out.println("org_present_after_other_request_rollback=" + new OrgStore(ctx).tenants().size());
      var catalog=new SqliteResourceCatalog(ctx,p -> true);
      catalog.create(entry("file-1"));catalog.create(entry("file-2"));catalog.create(entry("file-3"));
      var first=catalog.page("t","p",new PageRequest(1,null),null);
      catalog.delete("p","file-3");catalog.create(entry("new-after-first-page"));
      var second=catalog.page("t","p",new PageRequest(10,null),first.nextCursor());
      System.out.println("catalog_old_cursor_includes_new_rows="+second.items().stream().anyMatch(e -> e.gatewayId().equals("new-after-first-page")));
    }
    Path walDb=Path.of(args[0],"wal2.db");
    try (var ctx=new SqliteContext(walDb,2000)) {
      try(var s=ctx.connection().createStatement()) {
        s.execute("PRAGMA wal_autocheckpoint=0");
        s.execute("CREATE TABLE probe(data BLOB)");
        s.execute("INSERT INTO probe VALUES(zeroblob(2097152))");
      }
      new StorageGuard(1024*1024,0,walDb).requireWritable();
      System.out.println("storage_guard_allowed=true db_bytes="+Files.size(walDb)+" wal_bytes="+Files.size(Path.of(walDb+"-wal")));
    }
  }
}
