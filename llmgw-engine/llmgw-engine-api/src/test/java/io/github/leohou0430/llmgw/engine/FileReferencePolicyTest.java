package io.github.leohou0430.llmgw.engine;

import io.github.leohou0430.llmgw.core.protocol.*;
import io.github.leohou0430.llmgw.core.routing.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FileReferencePolicyTest {
    private final Route route = new Route("r","*","","http://upstream","",1,List.of());
    @Test void unicodeEscapedReferenceCannotBypassOwnership() {
        var store = new InMemoryTaskRouteSticky(10,60);
        store.bind("owner","files","f","r");
        String body = "{\"model\":\"m\",\"metadata\":{\"" + "\\" + "u0066ile_id\":\"f\"}}";
        var parsed = ProtocolCodec.parse(ProtocolKind.CHAT,body);
        assertFalse(EngineRoutes.ownsReferencedFiles(parsed,"other",route,store));
        assertTrue(EngineRoutes.ownsReferencedFiles(parsed,"owner",route,store));
    }
    @Test void nestedArrayReferencesStillRequireTheOwner() {
        var store = new InMemoryTaskRouteSticky(10,60);
        var parsed = ProtocolCodec.parse(ProtocolKind.CHAT,
                "{\"model\":\"m\",\"messages\":[{\"content\":[{\"file_id\":\"unknown\"}]}]}");
        assertFalse(EngineRoutes.ownsReferencedFiles(parsed,"owner",route,store));
    }
    @Test void referenceWordsInsideTextAreNotResourceIds() {
        var parsed = ProtocolCodec.parse(ProtocolKind.CHAT,
                "{\"model\":\"m\",\"messages\":[{\"content\":\"file_id is just text\"}]}");
        assertTrue(EngineRoutes.ownsReferencedFiles(parsed,"owner",route,new InMemoryTaskRouteSticky(10,60)));
    }
}
