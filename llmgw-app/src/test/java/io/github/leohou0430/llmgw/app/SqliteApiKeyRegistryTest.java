package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.*;
import io.github.leohou0430.llmgw.identity.*;
import io.github.leohou0430.llmgw.identity.local.*;
import io.github.leohou0430.llmgw.persistence.sqlite.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SqliteApiKeyRegistryTest {
    @TempDir Path folder;
    @Test void managementAndAdmissionShareCommittedKeysAcrossRestart() throws Exception {
        String replacement;
        Path path=folder.resolve("keys.db");
        try(var db=new SqliteContext(path,2000)) {
            new SchemaMigrator(db).migrate(Migrations.all());
            var store=new SqliteKeyStore(db,true);
            var registry=new SqliteApiKeyRegistry(new GatewayProperties(),db,store,null);
            var service=new ManagedKeyService(store,new RbacAuthorizationService(),new CredentialIssuer());
            var admin=new Subject(Role.PLATFORM_ADMIN,Scope.platform());var target=new Target("t","p");
            String issued=db.transaction(c -> service.create(admin,target,new ApiKeyRegistry.ApiKey("k","name",100,Set.of()))).plaintext();
            assertEquals("p",registry.find(issued).orElseThrow().metadata().get("llmgw.projectId"));
            replacement=db.transaction(c -> service.rotate(admin,target,"k")).plaintext();
            assertTrue(registry.find(issued).isEmpty());assertTrue(registry.find(replacement).isPresent());
            assertThrows(IllegalArgumentException.class,()->db.transaction(c -> {
                service.create(admin,target,new ApiKeyRegistry.ApiKey("rollback","name",100,Set.of()));
                throw new IllegalArgumentException("audit fails");
            }));
            assertTrue(registry.byId("rollback").isEmpty());
        }
        try(var db=new SqliteContext(path,2000)) {
            var store=new SqliteKeyStore(db,true);var registry=new SqliteApiKeyRegistry(new GatewayProperties(),db,store,null);
            assertTrue(registry.find(replacement).isPresent());
            assertTrue(registry.revoke("k"));assertTrue(registry.find(replacement).isEmpty());
            assertTrue(registry.revoke("demo"));
        }
        try(var db=new SqliteContext(path,2000)) {
            var registry=new SqliteApiKeyRegistry(new GatewayProperties(),db,new SqliteKeyStore(db,true),null);
            assertTrue(registry.find(replacement).isEmpty());assertTrue(registry.find("sk-demo").isEmpty());
        }
    }
    @Test void legacyPlaintextAndHashedCredentialsMigrateWithoutPlaintextOrLostOverlap() throws Exception {
        for(boolean hashed : List.of(false,true)) {
            var p = new GatewayProperties();p.getAuth().setHashKeysAtRest(hashed);
            p.setKeysStorePath(folder.resolve("legacy-"+hashed+".json").toString());
            var legacy = new io.github.leohou0430.llmgw.governance.inmemory.JsonKeyStore(p);
            var key = new ApiKeyRegistry.ApiKey("legacy","legacy",100,Set.of());
            legacy.upsert("sk-old",key);legacy.upsert("sk-new",key);
            legacy.rotate("sk-old","sk-new",System.currentTimeMillis()+60_000);
            try(var db=new SqliteContext(folder.resolve("legacy-"+hashed+".db"),2000)) {
                new SchemaMigrator(db).migrate(Migrations.all());var store=new SqliteKeyStore(db,true);
                var registry=new SqliteApiKeyRegistry(p,db,store,legacy);
                assertTrue(registry.find("sk-old").isPresent());assertTrue(registry.find("sk-new").isPresent());
                assertTrue(store.load().keySet().stream().allMatch(k->k.matches("[0-9a-f]{64}")));
                registry.revoke("legacy");assertTrue(registry.find("sk-old").isEmpty());
            }
        }
    }
    @Test void overlapAndRevocationApplyToEveryRotatedToken() throws Exception {
        try(var db=new SqliteContext(folder.resolve("rotation.db"),2000)) {
            new SchemaMigrator(db).migrate(Migrations.all());
            var registry=new SqliteApiKeyRegistry(new GatewayProperties(),db,new SqliteKeyStore(db,true),null);
            var old=registry.create("n",100,Set.of(),null);var fresh=registry.rotate(old.key().id(),60);
            assertTrue(registry.find(old.token()).isPresent());assertTrue(registry.find(fresh.token()).isPresent());
            registry.revoke(old.key().id());assertTrue(registry.find(old.token()).isEmpty());assertTrue(registry.find(fresh.token()).isEmpty());
        }
    }
}
