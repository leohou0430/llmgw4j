package io.github.leohou0430.llmgw.provider.http.jdk;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.upstream.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;

class NativeUpstreamApiTest {
    @Test void streamingContractUsesTheJdkFlowApi() {
        var client = new JdkHttpUpstream(new GatewayProperties());
        try {
            Object publisher = client.streamRaw(new UpstreamRequest("{}","http://127.0.0.1:9","","m",true,1000),HeaderListener.none());
            assertInstanceOf(Flow.Publisher.class,publisher);
        } finally { client.close(); }
    }
}
