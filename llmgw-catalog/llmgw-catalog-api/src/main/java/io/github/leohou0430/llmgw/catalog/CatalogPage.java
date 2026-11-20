package io.github.leohou0430.llmgw.catalog;

import java.util.List;

/**
 * One page of catalog entries plus the opaque signed cursor for the next page (null when
 * done).
 */
public record CatalogPage(List<ResourceEntry> items, String nextCursor) {

	public CatalogPage {
		items = List.copyOf(items);
	}

	public boolean hasMore() {
		return nextCursor != null;
	}
}