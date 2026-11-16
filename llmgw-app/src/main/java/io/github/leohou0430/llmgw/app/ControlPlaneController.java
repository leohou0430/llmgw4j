package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.control.ConfigValidator;
import io.github.leohou0430.llmgw.control.local.DefaultConfigValidator;
import io.github.leohou0430.llmgw.control.local.InMemoryRouteVersionStore;
import io.github.leohou0430.llmgw.control.local.RouteVersionService;
import io.github.leohou0430.llmgw.identity.Action;
import io.github.leohou0430.llmgw.identity.AuthorizationService;
import io.github.leohou0430.llmgw.identity.Role;
import io.github.leohou0430.llmgw.identity.Scope;
import io.github.leohou0430.llmgw.identity.Subject;
import io.github.leohou0430.llmgw.identity.Target;
import io.github.leohou0430.llmgw.identity.local.CredentialIssuer;
import io.github.leohou0430.llmgw.identity.local.RbacAuthorizationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only control-plane status surface. This wires the control-plane foundation
 * (identity RBAC + key issuance, route versioning, config validation) into the running
 * app so it is reachable over HTTP, and reports a safe invariant: provider credentials
 * are never readable by any role, including platform admin. No secrets are exposed.
 */
@RestController
@RequestMapping("/control")
public class ControlPlaneController {

    private final AuthorizationService authorization = new RbacAuthorizationService();
    private final CredentialIssuer credentials = new CredentialIssuer();
    private final RouteVersionService routeVersions;

    public ControlPlaneController(RouteVersionService routeVersions) {
        this.routeVersions = routeVersions;
    }
    private final ConfigValidator configValidator = new DefaultConfigValidator();

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("rbac", authorization.getClass().getSimpleName());
        status.put("keyLifecycle", credentials.getClass().getSimpleName());
        status.put("routeVersions", routeVersions.getClass().getSimpleName());
        status.put("configValidation", configValidator.getClass().getSimpleName());
        status.put("activeRouteVersion", routeVersions.activeVersion());

        Subject platformAdmin = new Subject(Role.PLATFORM_ADMIN, Scope.platform());
        boolean credentialReadDenied = !authorization
                .decide(platformAdmin, Action.READ_CREDENTIAL, new Target("t", "p")).allowed();
        status.put("credentialReadDenied", credentialReadDenied);
        return status;
    }
}
