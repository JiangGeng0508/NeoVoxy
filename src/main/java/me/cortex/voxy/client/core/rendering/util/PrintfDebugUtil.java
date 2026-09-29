package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.gl.shader.IShaderProcessor;
import me.cortex.voxy.client.core.gl.shader.PrintfInjector;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.common.Logger;

import java.util.ArrayList;
import java.util.List;

public final class PrintfDebugUtil {
    public static final boolean ENABLE_PRINTF_DEBUGGING = System.getProperty("voxy.enableShaderDebugPrintf", "false").equals("true");

    private static final List<String> printfQueue2 = new ArrayList<>();
    private static final List<String> printfQueue = new ArrayList<>();
    public static final IShaderProcessor PRINTF_processor;
    private static final PrintfInjector PRINTF_object;


    static {
        if (ENABLE_PRINTF_DEBUGGING) {
            PRINTF_object = new PrintfInjector(50000, 20, line -> {
                if (line.startsWith("LOG")) {
                    Logger.info(line);
                }
                printfQueue.add(line);
            }, printfQueue::clear);
            PRINTF_processor = PRINTF_object;
        } else {
            PRINTF_object = null;
            PRINTF_processor = new IShaderProcessor() {
                @Override
                public String process(ShaderType type, String src) {
                    // to match on printf with balanced bracing
                    return src.replace("printf", "//printf");
                }
            };
        }
    }

    public static void tick() {
        if (ENABLE_PRINTF_DEBUGGING) {
            printfQueue2.clear();
            printfQueue2.addAll(printfQueue);
            printfQueue.clear();
            PRINTF_object.download();
        }
    }

    public static void addToOut(List<String> out) {
        if (ENABLE_PRINTF_DEBUGGING) {
            out.add("Printf Queue: ");
            out.addAll(printfQueue2);
        }
    }

    public static void bind() {
        if (ENABLE_PRINTF_DEBUGGING) {
            PRINTF_object.bind();
        }
    }
}
