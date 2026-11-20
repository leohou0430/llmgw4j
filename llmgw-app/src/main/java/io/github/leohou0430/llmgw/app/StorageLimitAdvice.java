package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.persistence.sqlite.StorageLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Any write that hits the cap/free-space guard or a full SQLite database answers 503. */
@RestControllerAdvice
public class StorageLimitAdvice {

	@ExceptionHandler(StorageLimitException.class)
	public ResponseEntity<Map<String, Object>> storageLimit(StorageLimitException limit) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", limit.getMessage()));
	}

}
