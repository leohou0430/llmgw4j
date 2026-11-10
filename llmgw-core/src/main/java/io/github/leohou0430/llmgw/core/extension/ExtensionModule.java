package io.github.leohou0430.llmgw.core.extension;

import io.github.leohou0430.llmgw.core.extension.ExtensionPoint;

/** A named, stateless factory pack. Constructors and dependencies remain explicit Java. */
@ExtensionPoint
public interface ExtensionModule {
    void configure(ExtensionContext context);
}
