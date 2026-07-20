package minicode.core.loop;

import minicode.tools.registry.ToolRegistry;

/** 能够创建等价新实例，并将其绑定到其他工具 Registry 的 Provider Adapter。 */
public interface ForkableModelAdapter extends ModelAdapter {
    ModelAdapter fork(ToolRegistry toolRegistry);
}
