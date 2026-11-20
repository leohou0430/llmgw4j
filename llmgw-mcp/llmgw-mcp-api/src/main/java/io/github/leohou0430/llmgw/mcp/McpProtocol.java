package io.github.leohou0430.llmgw.mcp;

import java.util.Set;

/**
 * MCP 协议事实（Chapter-9 §9.3.2/§9.8.1）。网关显式声明支持的版本集合： 下游仅支持
 * Modern（2026-07-28，逐请求元数据）；Legacy（initialize 握手） 仅作为上游桥接目标，反向路径未声明支持。版本与弃用政策按精确基线理解，
 * 不从弃用窗口推导无限兼容。
 */
public final class McpProtocol {

	/** Modern 基线：逐请求元数据 + server/discover（本章核验基线）。 */
	public static final String PROTOCOL_VERSION = "2026-07-28";

	/** 下游显式支持的版本集合（单一元素；扩展版本需在此登记并回归）。 */
	public static final Set<String> SUPPORTED_VERSIONS = Set.of(PROTOCOL_VERSION);

	/** HTTP 镜像头：与正文的协议版本一致。 */
	public static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";

	/** HTTP 镜像头：与 JSON-RPC 方法一致。 */
	public static final String HEADER_METHOD = "Mcp-Method";

	/** HTTP 镜像头：仅具名请求必需（见 {@link #NAME_MIRROR_METHODS}）。 */
	public static final String HEADER_NAME = "Mcp-Name";

	/** params._meta 中的协议版本键（Modern 必需）。 */
	public static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";

	/** params._meta 中的客户端能力键（Modern 必需，允许为空对象）。 */
	public static final String META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";

	/** params._meta 中的客户端信息键（推荐，不是认证凭据）。 */
	public static final String META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo";

	/**
	 * 必需 {@code Mcp-Name} 镜像的方法及其参数来源：tools/call → params.name、 resources/read →
	 * params.uri、prompts/get → params.name。其余方法不得强制 要求名称镜像，也不能把所有名称字段都解释为
	 * params.name（§9.3.3）。
	 */
	public static final Set<String> NAME_MIRROR_METHODS = Set.of("tools/call", "resources/read", "prompts/get");

	/** Legacy 会话头（桥接单次交换内使用，不跨请求保留）。 */
	public static final String HEADER_LEGACY_SESSION = "Mcp-Session-Id";

	/** 默认关闭的扩展方法前缀：订阅与 Tasks（§9.3.2：默认关闭，显式开启）。 */
	public static final Set<String> DISABLED_METHOD_PREFIXES = Set.of("subscriptions/", "tasks/");

	/** 网关自身的桥接客户端标识（Legacy 上游 initialize 用）。 */
	public static final String BRIDGE_CLIENT_NAME = "llmgw-mcp-bridge";

	private McpProtocol() {
	}

}
