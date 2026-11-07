package io.github.leohou0430.llmgw.core.governance;

/** Persistence SPI. The in-memory build ships a JSON snapshot implementation. */
public interface SnapshotStore {
    String exportJson();

    void importJson(String json);

    void save();

    void load();
}
