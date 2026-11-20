package io.github.leohou0430.llmgw.recording.local;

import io.github.leohou0430.llmgw.core.extension.ExtensionContext;
import io.github.leohou0430.llmgw.core.extension.ExtensionModule;
import io.github.leohou0430.llmgw.recording.RecordingStore;

import java.nio.file.Path;
import java.time.Clock;

/**
 * Named {@code recording-local} extension module (task 5). Binds the local bounded
 * recording store under the configured spool directory; definitions are defaults: the
 * composition root may {@code replace} them before seal.
 */
public final class RecordingModule implements ExtensionModule {

	@Override
	public void configure(ExtensionContext context) {
		context.bindDefault(RecordingStore.class, () -> newStore(context, Path.of(System
			.getProperty("llmgw.recording.directory", System.getProperty("java.io.tmpdir") + "/llmgw-recordings"))));
	}

	static LocalRecordingStore newStore(ExtensionContext context, Path directory) {
		return new LocalRecordingStore(directory, 1024, Clock.systemUTC(), 7L * 24 * 60 * 60 * 1000);
	}

}
