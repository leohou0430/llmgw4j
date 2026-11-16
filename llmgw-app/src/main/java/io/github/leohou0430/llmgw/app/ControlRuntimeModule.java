package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.extension.*;
import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.*;
import io.github.leohou0430.llmgw.core.routing.*;
import io.github.leohou0430.llmgw.persistence.DatabaseContext;
import io.github.leohou0430.llmgw.persistence.sqlite.*;
import io.github.leohou0430.llmgw.identity.*;
import io.github.leohou0430.llmgw.identity.local.*;
import io.github.leohou0430.llmgw.control.*;
import io.github.leohou0430.llmgw.control.local.*;
import io.github.leohou0430.llmgw.catalog.*;
import io.github.leohou0430.llmgw.catalog.local.SqliteResourceCatalog;
import java.nio.file.*;
import java.util.*;

/** Named application composition: HTTP exports this graph; it never creates a parallel control plane. */
public final class ControlRuntimeModule implements ExtensionModule {
    @Override public void configure(ExtensionContext c) {
        GatewayProperties p=c.get(GatewayProperties.class);
        c.bind(SqliteContext.class,()->open(databaseFile(p),c.get(StorageGuard.class)));
        c.bind(DatabaseContext.class,()->c.get(SqliteContext.class));
        c.bind(StorageGuard.class,()->new StorageGuard(number("LLMGW_CONTROL_DB_MAX_BYTES",512L<<20),number("LLMGW_CONTROL_DB_MIN_FREE_BYTES",256L<<20),databaseFile(p)));
        c.bind(SqliteKeyStore.class,()->new SqliteKeyStore(c.get(DatabaseContext.class),true));
        c.bind(KeyStore.class,()->c.get(SqliteKeyStore.class));
        c.bind(SqliteApiKeyRegistry.class,()->new SqliteApiKeyRegistry(p,c.get(DatabaseContext.class),c.get(SqliteKeyStore.class),new io.github.leohou0430.llmgw.governance.inmemory.JsonKeyStore(p)));
        c.replace(ApiKeyRegistry.class,()->c.get(SqliteApiKeyRegistry.class));
        c.bind(KeyAdminService.class,()->c.get(SqliteApiKeyRegistry.class));
        c.bind(SqliteUsageLedger.class,()->new SqliteUsageLedger(c.get(DatabaseContext.class)));
        c.replace(UsageLedger.class,()->c.get(SqliteUsageLedger.class));
        c.bind(PasswordHasher.class,()->Pbkdf2PasswordHasher.withIterations(120_000));
        c.bind(AccountStore.class,()->new SqliteAccountStore(c.get(DatabaseContext.class)));
        c.bind(SessionStore.class,()->new SqliteSessionStore(c.get(DatabaseContext.class)));
        c.bind(CsrfTokenService.class,HmacCsrfTokenService::randomKey);
        c.bind(AuthorizationService.class,RbacAuthorizationService::new);
        c.bind(SessionService.class,()->new SessionService(c.get(SessionStore.class),c.get(PasswordHasher.class),3_600_000));
        c.bind(RequestAuthorizer.class,()->new RequestAuthorizer(c.get(SessionStore.class),c.get(CsrfTokenService.class),c.get(AccountStore.class),c.get(AuthorizationService.class),System::currentTimeMillis));
        c.bind(ManagedKeyService.class,()->new ManagedKeyService(c.get(KeyStore.class),c.get(AuthorizationService.class),new CredentialIssuer()));
        c.bind(SecretCipher.class,()->cipher());
        c.bind(SecretStore.class,()->new SecretStore(c.get(DatabaseContext.class),c.get(SecretCipher.class),c.get(StorageGuard.class)::requireWritable));
        c.bind(OrgStore.class,()->new OrgStore(c.get(DatabaseContext.class)));
        c.bind(AuditLog.class,()->new AuditLog(c.get(DatabaseContext.class)));
        c.bind(ResourceCatalog.class,()->new EncryptedResourceCatalog(new SqliteResourceCatalog(c.get(DatabaseContext.class),project->true),c.get(SecretCipher.class)));
        c.bind(PlatformPolicy.class,PlatformPolicy::strict);
        c.bind(DefaultConfigValidator.class,DefaultConfigValidator::new);
        c.bind(RouteCandidateValidator.class,()->new RouteCandidateValidator(c.get(DefaultConfigValidator.class)));
        c.bind(VersionedRouteConfigSource.class,()->routeSource(p,c));
        c.replace(RouteConfigSource.class,()->c.get(VersionedRouteConfigSource.class));
        c.bind(RouteVersionService.class,()->{
            var source=c.get(VersionedRouteConfigSource.class);
            var service=new RouteVersionService(new SqliteRouteVersionStore(c.get(DatabaseContext.class)),source::prepare);
            if(service.activeVersion()==0)service.publish(initialConfig(p),0);
            return service;
        });
    }
    private static VersionedRouteConfigSource routeSource(GatewayProperties p,ExtensionContext c){
        var initial=InMemoryRouteConfigSource.fromProperties(p);
        return new VersionedRouteConfigSource(initial,(tenant,project,id)->{
            if(tenant.equals("__bootstrap") && project.equals("__bootstrap") && id.startsWith("env:"))
                return initial.stream().filter(r->r.id().equals(id.substring(4))).findFirst().orElseThrow(()->new IllegalArgumentException("unknown environment credential reference")).apiKey();
            return c.get(SecretStore.class).resolve(tenant,project,id);
        },!p.isProduction(),route->{
            if(!route.provider().isBlank())Extensions.load(io.github.leohou0430.llmgw.provider.UpstreamAuthenticator.class).getExtension(route.provider());
        });
    }
    private static String initialConfig(GatewayProperties p){
        var routes=InMemoryRouteConfigSource.fromProperties(p).stream().map(r->{
            Map<String,Object> m=new LinkedHashMap<>();m.put("id",r.id());m.put("protocol",r.protocol());m.put("model",r.model());m.put("targetModel",r.targetModel());m.put("targetUrl",r.targetUrl());m.put("provider",r.provider());m.put("weight",r.weight());m.put("fallbacks",r.fallbackIds());m.put("upstreamProtocol",r.upstreamProtocol());
            if(!r.apiKey().isBlank()){m.put("secretId","env:"+r.id());m.put("tenantId","__bootstrap");m.put("projectId","__bootstrap");}return m;
        }).toList();return io.github.leohou0430.llmgw.core.protocol.ProtocolCodec.toJson(Map.of("routes",routes));
    }
    static Path databaseFile(GatewayProperties properties){
        String v=System.getenv("LLMGW_CONTROL_DB");
        if(v!=null && !v.isBlank())return Path.of(v);
        String keys=properties.getKeysStorePath();
        Path parent=Path.of(keys==null||keys.isBlank()?"./data/llmgw-keys.json":keys).toAbsolutePath().getParent();
        return parent.resolve("llmgw-control.db");
    }
    private static long number(String name,long fallback){String v=System.getenv(name);return v==null||v.isBlank()?fallback:Long.parseLong(v);}
    private static SqliteContext open(Path path,StorageGuard guard){try{
        Files.createDirectories(path.toAbsolutePath().getParent());var db=new SqliteContext(path,2000);
        try{new SchemaMigrator(db).migrate(Migrations.all());db.setWriteGuard(guard::requireWritable);return db;}catch(Exception e){db.close();throw e;}
    }catch(Exception e){throw new IllegalStateException("cannot open control database",e);}}
    private static SecretCipher cipher(){String v=System.getenv("LLMGW_ROOT_KEY");return new AesGcmSecretCipher(v==null||v.isBlank()?StaticRootKey.missing():new StaticRootKey(Base64.getDecoder().decode(v)));}
}
