package io.github.leohou0430.llmgw.mcp.defaultgate;

import io.github.leohou0430.llmgw.core.extension.ExtensionContext;
import io.github.leohou0430.llmgw.core.extension.ExtensionModule;
import io.github.leohou0430.llmgw.mcp.McpAccessPolicy;
import io.github.leohou0430.llmgw.mcp.McpGatewayService;
import io.github.leohou0430.llmgw.mcp.McpSettings;

/**
 * Named {@code mcp-default} extension module. 装配方先以
 * {@code context.external(McpSettings.class, settings)} 提供配置；缺省策略是
 * {@link ConfiguredMcpAccessPolicy}（默认拒绝）。组合根可在 seal 前显式 {@code replace}
 * 自定义策略。McpSettings 缺席时不绑定——MCP 网关是否启用 由装配层显式决定，不做隐式探测。
 */
public final class McpGatewayModule implements ExtensionModule {

	@Override
	public void configure(ExtensionContext context) {
		if (!context.contains(McpSettings.class)) {
			return;
		}
		context.bindDefault(McpAccessPolicy.class, () -> new ConfiguredMcpAccessPolicy(context.get(McpSettings.class)));
		context.bindDefault(McpGatewayService.class,
				() -> new DefaultMcpGatewayService(context.get(McpSettings.class), context.get(McpAccessPolicy.class)));
	}

}
