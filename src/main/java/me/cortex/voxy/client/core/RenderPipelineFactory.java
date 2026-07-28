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
    public static AbstractRenderPipeline createPipeline(RenderProperties properties, AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        if (IrisUtil.IRIS_INSTALLED && IrisUtil.SHADER_SUPPORT) {
            var pipeline = createIrisPipeline(properties, nodeManager, nodeCleaner, traversal, frexSupplier);
            if (pipeline != null) {
                return pipeline;
            }
        }
        return new NormalRenderPipeline(properties, nodeManager, nodeCleaner, traversal, frexSupplier);
    }

    private static AbstractRenderPipeline createIrisPipeline(RenderProperties properties,
                                                             AsyncNodeManager nodeManager,
                                                             NodeCleaner nodeCleaner,
                                                             HierarchicalOcclusionTraverser traversal,
                                                             BooleanSupplier frexSupplier) {
        var irisPipeline = Iris.getPipelineManager().getPipelineNullable();
        if (!(irisPipeline instanceof IGetIrisVoxyPipelineData voxyPipeline)) {
            return null;
        }

        var pipelineData = voxyPipeline.voxy$getPipelineData();
        if (pipelineData == null) {
            return null;
        }

        Logger.info("Creating Voxy Iris render pipeline");
        try {
            return new IrisVoxyRenderPipeline(properties, pipelineData, nodeManager, nodeCleaner, traversal, frexSupplier);
        } catch (RuntimeException e) {
            Logger.error("Failed to create Voxy Iris render pipeline", e);
            IrisUtil.disableIrisShaders();
            return null;
        }
    }
}
