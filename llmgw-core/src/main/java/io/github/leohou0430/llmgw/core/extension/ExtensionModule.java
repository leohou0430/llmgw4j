package io.github.leohou0430.llmgw.core.extension;

import org.apache.dubbo.common.extension.ExtensionScope;
import org.apache.dubbo.common.extension.SPI;

/** A named, stateless factory pack. Constructors and dependencies remain explicit Java. */
@SPI(scope = ExtensionScope.FRAMEWORK)
public interface ExtensionModule {
    void configure(ExtensionContext context);
}
