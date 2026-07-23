package minicode.agent.runtime;

import minicode.core.loop.ModelAdapter;
import minicode.tools.registry.ToolRegistry;

/**
 * 为一次子 Agent 运行创建模型适配器。
 *
 * <p>实现应继承当前启用的供应商和模型配置，并将返回的适配器绑定到给定的子 Agent 工具注册表。
 * 内置供应商会为子注册表创建或分叉适配器；兼容入口也允许包装调用方传入的普通适配器。</p>
 */
@FunctionalInterface
public interface ModelAdapterFactory {
    ModelAdapter create(ToolRegistry childToolRegistry);
}
