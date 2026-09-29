package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
import me.cortex.voxy.common.Logger;
import net.irisshaders.iris.Iris;

import java.util.function.BooleanSupplier;

public class RenderPipelineFactory {
    public static AbstractRenderPipeline createPipeline(RenderProperties properties,
                                                        AsyncNodeManager nodeManager,
                                                        NodeCleaner nodeCleaner,
                                                        HierarchicalOcclusionTraverser traversal,
                                                        BooleanSupplier frexSupplier) {
        // Shader 管线创建失败时必须回退到原版路径，保证进入世界不会因为 Iris 状态中断。
        if (IrisUtil.SHADER_SUPPORT && IrisUtil.irisShaderPackEnabled()) {
            var irisPipeline = createIrisPipeline(properties, nodeManager, nodeCleaner, traversal, frexSupplier);
            if (irisPipeline != null) {
                return irisPipeline;
            }
        }
        return new NormalRenderPipeline(properties, nodeManager, nodeCleaner, traversal, frexSupplier);
    }

    private static AbstractRenderPipeline createIrisPipeline(RenderProperties properties,
                                                             AsyncNodeManager nodeManager,
                                                             NodeCleaner nodeCleaner,
                                                             HierarchicalOcclusionTraverser traversal,
                                                             BooleanSupplier frexSupplier) {
        var irisPipe = Iris.getPipelineManager().getPipelineNullable();
        if (irisPipe == null) {
            return null;
        }
        if (irisPipe instanceof IGetIrisVoxyPipelineData getVoxyPipeData) {
            var pipeData = getVoxyPipeData.voxy$getPipelineData();
            if (pipeData == null) {
                return null;
            }
            Logger.info("Creating voxy iris render pipeline");
            try {
                return new IrisVoxyRenderPipeline(properties, pipeData, nodeManager, nodeCleaner, traversal, frexSupplier);
            } catch (Exception e) {
                Logger.error("Failed to create iris render pipeline", e);
                IrisUtil.disableIrisShaders();
                return null;
            }
        }
        return null;
    }
}
