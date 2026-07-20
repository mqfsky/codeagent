package minicode.agent.runtime;

import minicode.core.loop.ModelAdapter;
import minicode.tools.registry.ToolRegistry;

/**
 * 为一次子 Agent 运行创建模型适配器。
 *
 * <p>实现必须继承当前启用的供应商和模型配置，并将返回的适配器绑定到给定的子 Agent 工具注册表。
 * 每次调用都必须返回新的适配器，避免供应商请求状态和工具 Schema 在不同 Agent 之间泄漏。</p>
 */
@FunctionalInterface
public interface ModelAdapterFactory {
    ModelAdapter create(ToolRegistry childToolRegistry);
}
