package io.github.leohou0430.llmgw.app;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

@RestControllerAdvice(
		assignableTypes = { ControlKeyController.class, ControlAccountController.class, ControlQuotaController.class,
				ControlCatalogController.class, ControlOrgController.class, ControlSecretController.class,
				ControlRouteController.class, ControlUsageController.class, ControlLoginController.class })
public class ControlExceptionHandler {

	@ExceptionHandler(io.github.leohou0430.llmgw.persistence.sqlite.StorageLimitException.class)
	@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
	Map<String, String> storage(RuntimeException failure) {
		return Map.of("error", failure.getMessage());
	}

	@ExceptionHandler(SecurityException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	Map<String, String> forbidden(SecurityException failure) {
		return Map.of("error", failure.getMessage());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	Map<String, String> invalid(IllegalArgumentException failure) {
		return Map.of("error", failure.getMessage());
	}

}
