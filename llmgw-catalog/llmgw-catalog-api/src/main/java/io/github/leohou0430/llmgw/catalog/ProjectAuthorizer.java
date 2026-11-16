package io.github.leohou0430.llmgw.catalog;

/**
 * Authorization precondition for catalog access. Every catalog operation must check
 * this BEFORE touching the fixed upstream source, so an unauthorized project cannot
 * read or create entries (and never reaches the source).
 */
@FunctionalInterface
public interface ProjectAuthorizer {

    boolean authorized(String projectId);

    default void requireAuthorized(String projectId) {
        if (!authorized(projectId)) {
            throw new CatalogAccessException("project not authorized: " + projectId);
        }
    }
}