package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.catalog.*;
import io.github.leohou0430.llmgw.catalog.local.LocalResourceCatalog;
import io.github.leohou0430.llmgw.control.local.AesGcmSecretCipher;
import io.github.leohou0430.llmgw.control.local.StaticRootKey;
import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;
import io.github.leohou0430.llmgw.core.routing.Route;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EncryptedResourceCatalogTest {
    @Test void credentialSnapshotIsCiphertextAtRestAndRecoverableWithTheSameRootKey() {
        var raw=new LocalResourceCatalog(project->true);
        var cipher=new AesGcmSecretCipher(new StaticRootKey(new byte[32]));
        var catalog=new EncryptedResourceCatalog(raw,cipher);
        var route=new Route("origin","*","*","","https://provider.example/v1","private-provider-credential","openai",1,List.of(),"");
        var entry=new ResourceEntry("file-1","p","files","file-1","origin","openai","1",1,"",Map.of("llmgw.sourceRoute",ProtocolCodec.toJson(route)),1,false);
        catalog.create(entry);
        var stored=raw.get("p","file-1").orElseThrow();
        assertFalse(stored.metadata().containsKey("llmgw.sourceRoute"));
        assertFalse(stored.metadata().toString().contains("private-provider-credential"));
        var restored=new EncryptedResourceCatalog(raw,new AesGcmSecretCipher(new StaticRootKey(new byte[32])));
        assertEquals(route,ProtocolCodec.mapper().readValue(restored.get("p","file-1").orElseThrow().metadata().get("llmgw.sourceRoute"),Route.class));
    }
}
