import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.governance.inmemory.JwtTokenValidator;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** CPU time and thread allocation for complete, valid HS256 validation; no network. */
class JwtValidationMicrobench {
    private static volatile long consumed;
    public static void main(String[] args) throws Exception {
        var p = new GatewayProperties();
        p.getAuth().setJwtSecret("benchmark-only-secret");
        p.getAuth().setJwtIssuer("benchmark");
        var validator = new JwtTokenValidator(p);
        var b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String claims = b64.encodeToString("{\"iss\":\"benchmark\",\"sub\":\"alice\",\"exp\":4102444800}".getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + claims;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("benchmark-only-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        String token = signingInput + "." + b64.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        bean.setThreadCpuTimeEnabled(true);
        long thread = Thread.currentThread().threadId();
        int count = 10000;
        for (int round = 0; round < 6; round++) {
            long allocated = bean.getThreadAllocatedBytes(thread), cpu = bean.getCurrentThreadCpuTime();
            long accepted = 0;
            for (int i = 0; i < count; i++) {
                accepted += validator.validate(token).orElseThrow().id().length();
            }
            long usedCpu = bean.getCurrentThreadCpuTime()-cpu;
            long usedBytes = bean.getThreadAllocatedBytes(thread)-allocated;
            consumed = accepted;
            if (round > 0) System.out.printf("%d,%.2f,%.2f%n",round,(double)usedCpu/count,(double)usedBytes/count);
        }
    }
}
