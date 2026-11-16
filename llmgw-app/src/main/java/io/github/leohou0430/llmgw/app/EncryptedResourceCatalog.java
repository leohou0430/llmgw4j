package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.catalog.*;
import io.github.leohou0430.llmgw.control.SecretCipher;
import io.github.leohou0430.llmgw.core.protocol.ProtocolCodec;
import java.util.*;

/** Provider routing snapshots remain private and ciphertext-at-rest when they carry credentials. */
public final class EncryptedResourceCatalog implements ResourceCatalog {
    private final ResourceCatalog delegate;
    private final SecretCipher cipher;
    public EncryptedResourceCatalog(ResourceCatalog delegate, SecretCipher cipher){this.delegate=delegate;this.cipher=cipher;}
    private ResourceEntry copy(ResourceEntry e, Map<String,String> metadata){return new ResourceEntry(e.gatewayId(),e.projectId(),e.type(),e.upstreamId(),e.sourceRoute(),e.provider(),e.configVersion(),e.createdAt(),e.observableStatus(),Map.copyOf(metadata),e.lastSyncedAt(),e.deleted());}
    private ResourceEntry encrypt(ResourceEntry e){
        var m=new HashMap<>(e.metadata());String snapshot=m.get("llmgw.sourceRoute");
        if(snapshot!=null && !ProtocolCodec.tryParse(snapshot).path("apiKey").asText("").isBlank()){
            m.remove("llmgw.sourceRoute");m.put("llmgw.encryptedSourceRoute",cipher.encrypt(snapshot));
        }
        return copy(e,m);
    }
    private ResourceEntry decrypt(ResourceEntry e){
        var m=new HashMap<>(e.metadata());String encrypted=m.remove("llmgw.encryptedSourceRoute");
        if(encrypted!=null)m.put("llmgw.sourceRoute",cipher.decrypt(encrypted));return copy(e,m);
    }
    @Override public ResourceEntry create(ResourceEntry e){delegate.create(encrypt(e));return e;}
    @Override public Optional<ResourceEntry> get(String p,String id){return delegate.get(p,id).map(this::decrypt);}
    @Override public boolean delete(String p,String id){return delegate.delete(p,id);}
    @Override public List<ResourceEntry> list(String p){return delegate.list(p).stream().map(this::decrypt).toList();}
    @Override public CatalogPage page(String t,String p,PageRequest r,String c){var page=delegate.page(t,p,r,c);return new CatalogPage(page.items().stream().map(this::decrypt).toList(),page.nextCursor());}
}
