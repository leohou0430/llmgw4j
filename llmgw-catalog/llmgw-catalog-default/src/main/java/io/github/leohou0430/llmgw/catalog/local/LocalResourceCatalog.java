package io.github.leohou0430.llmgw.catalog.local;

import io.github.leohou0430.llmgw.catalog.CatalogCursorException;
import io.github.leohou0430.llmgw.catalog.CatalogPage;
import io.github.leohou0430.llmgw.catalog.PageRequest;
import io.github.leohou0430.llmgw.catalog.ProjectAuthorizer;
import io.github.leohou0430.llmgw.catalog.ResourceCatalog;
import io.github.leohou0430.llmgw.catalog.ResourceEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local, in-memory catalog partitioned per project. Entries carry a monotonically
 * increasing creation sequence and are stored in a per-project ordered map, so pagination
 * sorts by (sequence) and stays stable across an in-flight traversal. A signed cursor
 * binds tenant/project/filter plus the first page's high-water mark; entries created
 * after the traversal began are excluded. Source route and last-observed time are stored
 * verbatim, so a route switch never re-points an entry and stale entries remain readable
 * with their last-synced timestamp.
 */
public final class LocalResourceCatalog implements ResourceCatalog {

	private final ProjectAuthorizer authorizer;

	private final CursorCodec codec;

	private final AtomicLong sequence = new AtomicLong();

	private final Map<String, ProjectState> byProject = new ConcurrentHashMap<>();

	public LocalResourceCatalog(ProjectAuthorizer authorizer) {
		this(authorizer, CursorCodec.random());
	}

	public LocalResourceCatalog(ProjectAuthorizer authorizer, CursorCodec codec) {
		this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
		this.codec = Objects.requireNonNull(codec, "codec");
	}

	private static final class ProjectState {

		private final Map<String, Long> gatewayToSequence = new HashMap<>();

		private final NavigableMap<Long, ResourceEntry> ordered = new TreeMap<>();

	}

	@Override
	public ResourceEntry create(ResourceEntry entry) {
		Objects.requireNonNull(entry, "entry");
		authorizer.requireAuthorized(entry.projectId());
		ProjectState state = byProject.computeIfAbsent(entry.projectId(), ignored -> new ProjectState());
		synchronized (state) {
			Long prior = state.gatewayToSequence.get(entry.gatewayId());
			if (prior != null) {
				state.ordered.remove(prior);
			}
			long assigned = sequence.incrementAndGet();
			state.gatewayToSequence.put(entry.gatewayId(), assigned);
			state.ordered.put(assigned, entry);
		}
		return entry;
	}

	@Override
	public Optional<ResourceEntry> get(String projectId, String gatewayId) {
		authorizer.requireAuthorized(projectId);
		ProjectState state = byProject.get(projectId);
		if (state == null) {
			return Optional.empty();
		}
		synchronized (state) {
			Long assigned = state.gatewayToSequence.get(gatewayId);
			return assigned == null ? Optional.empty() : Optional.ofNullable(state.ordered.get(assigned));
		}
	}

	@Override
	public boolean delete(String projectId, String gatewayId) {
		authorizer.requireAuthorized(projectId);
		ProjectState state = byProject.get(projectId);
		if (state == null) {
			return false;
		}
		synchronized (state) {
			Long assigned = state.gatewayToSequence.remove(gatewayId);
			return assigned != null && state.ordered.remove(assigned) != null;
		}
	}

	@Override
	public List<ResourceEntry> list(String projectId) {
		authorizer.requireAuthorized(projectId);
		ProjectState state = byProject.get(projectId);
		if (state == null) {
			return List.of();
		}
		synchronized (state) {
			return List.copyOf(state.ordered.values());
		}
	}

	@Override
	public CatalogPage page(String tenantId, String projectId, PageRequest request, String cursor) {
		authorizer.requireAuthorized(projectId);
		Objects.requireNonNull(request, "request");

		long highWaterMark;
		long afterSequence;
		if (cursor == null || cursor.isEmpty()) {
			highWaterMark = sequence.get();
			afterSequence = Long.MIN_VALUE;
		}
		else {
			CursorCodec.Token token = codec.decode(cursor);
			if (!Objects.equals(token.tenantId(), tenantId) || !Objects.equals(token.projectId(), projectId)
					|| !Objects.equals(token.typeFilter(), request.typeFilter())) {
				throw new CatalogCursorException("cursor is bound to a different tenant/project/filter");
			}
			highWaterMark = token.highWaterMark();
			afterSequence = token.lastSequence();
		}

		List<ResourceEntry> items = new ArrayList<>();
		String nextCursor = null;
		ProjectState state = byProject.get(projectId);
		if (state != null) {
			synchronized (state) {
				NavigableMap<Long, ResourceEntry> window = state.ordered.subMap(afterSequence, false, highWaterMark,
						true);
				long lastIncluded = Long.MIN_VALUE;
				for (Map.Entry<Long, ResourceEntry> candidate : window.entrySet()) {
					if (request.typeFilter() != null && !request.typeFilter().equals(candidate.getValue().type())) {
						continue;
					}
					if (items.size() == request.limit()) {
						nextCursor = codec.encode(new CursorCodec.Token(tenantId, projectId, request.typeFilter(),
								highWaterMark, lastIncluded));
						break;
					}
					items.add(candidate.getValue());
					lastIncluded = candidate.getKey();
				}
			}
		}
		return new CatalogPage(items, nextCursor);
	}

}