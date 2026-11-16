package io.github.leohou0430.llmgw.persistence.sqlite;

import io.github.leohou0430.llmgw.core.governance.UsageLedger;
import io.github.leohou0430.llmgw.persistence.DatabaseContext;
import java.sql.*;
import java.util.*;

/** Durable cumulative token quota. Pending/unknown requests remain charged across restarts. */
public final class SqliteUsageLedger implements UsageLedger {
    public static final List<String> SCHEMA = List.of(
        "CREATE TABLE IF NOT EXISTS token_quota (scope TEXT PRIMARY KEY, quota INTEGER NOT NULL CHECK(quota>=0))",
        "CREATE TABLE IF NOT EXISTS token_reservation (id TEXT PRIMARY KEY, key_id TEXT NOT NULL, project_scope TEXT NOT NULL, tokens INTEGER NOT NULL CHECK(tokens>=0), settled INTEGER NOT NULL DEFAULT 0, prompt INTEGER NOT NULL DEFAULT 0, completion INTEGER NOT NULL DEFAULT 0, success INTEGER NOT NULL DEFAULT 0)",
        "CREATE INDEX IF NOT EXISTS token_reservation_key ON token_reservation(key_id)",
        "CREATE INDEX IF NOT EXISTS token_reservation_project ON token_reservation(project_scope)",
        "CREATE TABLE IF NOT EXISTS token_quota_audit (id INTEGER PRIMARY KEY, scope TEXT NOT NULL, quota INTEGER NOT NULL, actor TEXT NOT NULL, reason TEXT NOT NULL, at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)"
    );
    private final DatabaseContext db;
    // Only current-process in-flight requests are active. Restarted or completed-unknown
    // reservations stay durable but may be reconciled by an authorized operator.
    private final Set<String> active = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public SqliteUsageLedger(DatabaseContext db) { this.db = Objects.requireNonNull(db); }
    private static String project(String tenant, String project) {
        return "project:" + tenant.length() + ":" + tenant + project;
    }
    public void setProjectQuota(String tenant, String project, long quota, String actor, String reason) {
        if (tenant == null || tenant.isBlank() || project == null || project.isBlank()) throw new IllegalArgumentException("project scope required");
        adjust(project(tenant, project), quota, actor, reason);
    }
    public void setKeyQuota(String keyId, long quota, String actor, String reason) {
        if (keyId == null || keyId.isBlank()) throw new IllegalArgumentException("key required");
        adjust("key:" + keyId, quota, actor, reason);
    }
    private void adjust(String scope, long quota, String actor, String reason) {
        if (quota < 0 || actor == null || actor.isBlank() || reason == null || reason.isBlank()) throw new IllegalArgumentException("quota, actor and reason required");
        try { db.transaction(c -> {
            execute(c,"INSERT INTO token_quota(scope,quota) VALUES(?,?) ON CONFLICT(scope) DO UPDATE SET quota=excluded.quota",scope,quota);
            execute(c,"INSERT INTO token_quota_audit(scope,quota,actor,reason) VALUES(?,?,?,?)",scope,quota,actor,reason);
            return null;
        }); } catch(SQLException e) { throw failure(e); }
    }
    public List<Map<String,Object>> pending(String tenant,String project) {
        String scope=project(tenant,project);
        try { return db.read(c -> {
            var result=new ArrayList<Map<String,Object>>();
            try(var q=c.prepareStatement("SELECT id,key_id,tokens FROM token_reservation WHERE project_scope=? AND settled=0 ORDER BY rowid LIMIT 200")) {
                q.setString(1,scope);try(var rs=q.executeQuery()) {
                    while(rs.next())result.add(Map.of("id",rs.getString(1),"keyId",rs.getString(2),"reservedTokens",rs.getLong(3),"active",active.contains(rs.getString(1))));
                }
            }
            return List.copyOf(result);
        }); } catch(SQLException e) { throw failure(e); }
    }
    public void reconcile(String tenant,String project,String id,long prompt,long completion,String actor,String reason) {
        if(prompt<0 || completion<0 || actor==null || actor.isBlank() || reason==null || reason.isBlank())
            throw new IllegalArgumentException("verified usage, actor and reconciliation reason required");
        if(prompt>Long.MAX_VALUE-completion) throw new IllegalArgumentException("verified token total is too large");
        String scope=project(tenant,project);
        try { db.transaction(c -> {
            if(active.contains(id)) throw new IllegalArgumentException("in-flight reservation cannot be reconciled");
            try(var q=c.prepareStatement("UPDATE token_reservation SET settled=1,prompt=?,completion=?,success=1 WHERE id=? AND project_scope=? AND settled=0")) {
                q.setLong(1,prompt);q.setLong(2,completion);q.setString(3,id);q.setString(4,scope);
                if(q.executeUpdate()!=1)throw new IllegalArgumentException("reservation not pending in authorized project");
            }
            execute(c,"INSERT INTO token_quota_audit(scope,quota,actor,reason) VALUES(?,?,?,?)",scope,prompt+completion,actor,"reconcile "+id+": "+reason);
            return null;
        }); } catch(SQLException e) { throw failure(e); }
    }
    @Override public boolean tryReserve(Reservation r) {
        if (r.tokens()<0 || r.keyQuota()<0 || r.id()==null || r.id().isBlank()) throw new IllegalArgumentException("invalid reservation");
        if (r.tenantId() == null || r.projectId() == null || r.tenantId().isBlank() != r.projectId().isBlank())
            throw new IllegalArgumentException("complete project scope required");
        String scope = r.tenantId().isBlank() || r.projectId().isBlank() ? "" : project(r.tenantId(),r.projectId());
        try { return db.transaction(c -> {
            // A reservation ID is internal and single-use; duplicates cannot create credit.
            if (scalar(c,"SELECT COUNT(*) FROM token_reservation WHERE id=?",r.id()) != 0) return false;
            long keyQuota = quota(c,"key:"+r.keyId(),r.keyQuota());
            if (!admits(total(c,"key_id",r.keyId()),r.tokens(),keyQuota)) return false;
            // Scoped keys require an explicit project allowance; adding keys cannot mint quota.
            if (!scope.isEmpty() && !admits(total(c,"project_scope",scope),r.tokens(),quota(c,scope,0))) return false;
            execute(c,"INSERT INTO token_reservation(id,key_id,project_scope,tokens) VALUES(?,?,?,?)",r.id(),r.keyId(),scope,r.tokens());
            active.add(r.id());
            return true;
        }); } catch(SQLException e) { throw failure(e); }
    }
    @Override public void settle(String id, UsageRecord r, boolean outcomeKnown) {
        if (id == null || id.isBlank()) { record(r); return; }
        if (!outcomeKnown) {
            try { db.transaction(c -> {
                execute(c,"UPDATE token_reservation SET success=-1 WHERE id=? AND key_id=? AND settled=0",id,r.keyId());
                active.remove(id); return null;
            }); } catch(SQLException e) { throw failure(e); }
            return;
        }
        if (r.promptTokens()<0 || r.completionTokens()<0) throw new IllegalArgumentException("negative usage");
        try { db.transaction(c -> {
            execute(c,"UPDATE token_reservation SET settled=1,prompt=?,completion=?,success=? WHERE id=? AND key_id=? AND settled=0",r.success()?r.promptTokens():0,r.success()?r.completionTokens():0,r.success()?1:0,id,r.keyId());
            active.remove(id);
            return null;
        }); } catch(SQLException e) { throw failure(e); }
    }
    @Override public Map<String,UsageSummary> snapshot() { return snapshotScope(null); }
    public Map<String,UsageSummary> snapshot(String tenant,String project) { return snapshotScope(project(tenant,project)); }
    private Map<String,UsageSummary> snapshotScope(String scope) {
        try { return db.read(c -> {
            Map<String,UsageSummary> out=new HashMap<>();
            String sql="SELECT key_id,COUNT(*),SUM(prompt),SUM(completion),SUM(CASE WHEN success<>1 THEN 1 ELSE 0 END) FROM token_reservation WHERE (settled=1 OR success=-1)"
                    +(scope==null?"":" AND project_scope=?")+" GROUP BY key_id";
            try(var s=c.prepareStatement(sql)) {
                if(scope!=null)s.setString(1,scope);
                try(var rs=s.executeQuery()) {while(rs.next())out.put(rs.getString(1),new UsageSummary(rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5)));}
            } return Map.copyOf(out);
        }); } catch(SQLException e) { throw failure(e); }
    }
    @Override public long used(String key) {
        try { return db.read(c -> total(c,"key_id",key)); } catch(SQLException e) { throw failure(e); }
    }
    public long projectUsed(String tenant,String project) {
        try { return db.read(c -> total(c,"project_scope",project(tenant,project))); } catch(SQLException e) { throw failure(e); }
    }
    @Override public boolean tryReserve(String key,long tokens,long quota) {
        throw new UnsupportedOperationException("durable quota requires a reservation identity");
    }
    @Override public void record(UsageRecord r) { throw new UnsupportedOperationException("durable usage requires a reservation identity"); }
    @Override public void load(Map<String,UsageSummary> data) { throw new UnsupportedOperationException("durable usage cannot be replaced by snapshots"); }
    private static boolean admits(long total,long want,long quota) { return total>=0 && total<=quota && want<=quota-total; }
    private static long total(Connection c,String column,String scope)throws SQLException {
        return scalar(c,"SELECT COALESCE(SUM(CASE WHEN settled=0 THEN tokens ELSE prompt+completion END),0) FROM token_reservation WHERE "+column+"=?",scope);
    }
    private static long quota(Connection c,String scope,long fallback)throws SQLException {
        try(var s=c.prepareStatement("SELECT quota FROM token_quota WHERE scope=?")) { s.setString(1,scope);try(var rs=s.executeQuery()){return rs.next()?rs.getLong(1):fallback;} }
    }
    private static long scalar(Connection c,String sql,Object...args)throws SQLException {
        try(var s=c.prepareStatement(sql)){bind(s,args);try(var rs=s.executeQuery()){return rs.next()?rs.getLong(1):0;}}
    }
    private static void execute(Connection c,String sql,Object...args)throws SQLException { try(var s=c.prepareStatement(sql)){bind(s,args);s.executeUpdate();} }
    private static void bind(PreparedStatement s,Object...args)throws SQLException {for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);}
    private static IllegalStateException failure(SQLException e) {return new IllegalStateException("durable token ledger failed",e);}
}
