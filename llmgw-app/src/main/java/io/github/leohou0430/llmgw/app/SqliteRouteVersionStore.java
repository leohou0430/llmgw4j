package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.control.*;
import io.github.leohou0430.llmgw.persistence.DatabaseContext;
import java.sql.*;
import java.util.*;

/** Durable immutable global route history; all access shares the database transaction owner. */
public final class SqliteRouteVersionStore implements RouteVersionStore {
    private final DatabaseContext database;
    public SqliteRouteVersionStore(DatabaseContext database){this.database=Objects.requireNonNull(database);}
    @Override public Optional<RouteVersion> latest(){return query("SELECT version,config,base_version FROM route_version ORDER BY version DESC LIMIT 1",null);}
    @Override public Optional<RouteVersion> find(long version){return query("SELECT version,config,base_version FROM route_version WHERE version=?",version);}
    @Override public List<RouteVersion> all(){
        try{return database.read(c->{var result=new ArrayList<RouteVersion>();try(var s=c.prepareStatement("SELECT version,config,base_version FROM route_version ORDER BY version");var rs=s.executeQuery()){while(rs.next())result.add(read(rs));}return List.copyOf(result);});}
        catch(SQLException e){throw new IllegalStateException("failed to read route versions",e);}
    }
    @Override public void append(RouteVersion version){
        try{database.transaction(c->{
            try(var s=c.prepareStatement("INSERT INTO route_version(version,config,base_version,created_at) SELECT ?,?,?,? WHERE ?=(SELECT COALESCE(MAX(version),0)+1 FROM route_version)")){
                s.setLong(1,version.version());s.setString(2,version.config());s.setLong(3,version.baseVersion());s.setLong(4,System.currentTimeMillis());s.setLong(5,version.version());
                if(s.executeUpdate()!=1)throw new VersionConflictException("route version is stale");
            }return null;
        });}catch(SQLException e){
            if(io.github.leohou0430.llmgw.persistence.sqlite.SqliteErrors.isStorageFull(e))
                throw new io.github.leohou0430.llmgw.persistence.sqlite.StorageLimitException("database or disk is full");
            throw new IllegalStateException("failed to persist route version",e);
        }
    }
    @Override public void afterCommit(Runnable action){database.afterCommit(action);}
    private Optional<RouteVersion> query(String sql,Long parameter){
        try{return database.read(c->{try(var s=c.prepareStatement(sql)){if(parameter!=null)s.setLong(1,parameter);try(var rs=s.executeQuery()){return rs.next()?Optional.of(read(rs)):Optional.empty();}}});}
        catch(SQLException e){throw new IllegalStateException("failed to read route version",e);}
    }
    private static RouteVersion read(ResultSet rs)throws SQLException{return new RouteVersion(rs.getLong(1),rs.getString(2),rs.getLong(3));}
}
