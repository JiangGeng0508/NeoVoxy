package me.cortex.voxy.testing;

import com.google.gson.Gson;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.util.LinkedHashMap;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;

/** Strict hidden context for opt-in tests. An unavailable GPU is a failure, not a pass/skip. */
public final class GpuTestContext implements AutoCloseable {
    private long window;
    private boolean initialized;
    private boolean closed;
    private GLFWErrorCallback errors;
    private GLFWErrorCallback previousErrors;
    private boolean callbackInstalled;

    private GpuTestContext() {}

    public static GpuTestContext open(String suite) {
        // Load the native API before constructing callbacks or entering cleanup paths.
        try { glfwGetVersionString(); }
        catch (Throwable failure) {
            throw new AssertionError("GPU checks NOT RUN for " + suite + ": GLFW native library unavailable", failure);
        }
        var context = new GpuTestContext();
        try {
            context.errors = GLFWErrorCallback.createPrint(System.err);
            context.previousErrors = glfwSetErrorCallback(context.errors);
            context.callbackInstalled = true;
            if (!glfwInit()) throw new IllegalStateException("GLFW initialization failed");
            context.initialized = true;
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            context.window = glfwCreateWindow(32, 32, suite, 0, 0);
            if (context.window == 0) throw new IllegalStateException("OpenGL 4.6 context unavailable");
            glfwMakeContextCurrent(context.window);
            var capabilities = GL.createCapabilities();
            if (!capabilities.OpenGL46) throw new IllegalStateException("OpenGL 4.6 is required");
            var environment = new LinkedHashMap<String, Object>();
            environment.put("suite", suite);
            environment.put("vendor", glGetString(GL_VENDOR));
            environment.put("renderer", glGetString(GL_RENDERER));
            environment.put("version", glGetString(GL_VERSION));
            environment.put("glsl", glGetString(GL_SHADING_LANGUAGE_VERSION));
            environment.put("shaderInt64", capabilities.GL_ARB_gpu_shader_int64);
            environment.put("java", System.getProperty("java.version"));
            environment.put("os", System.getProperty("os.name"));
            System.out.println("GPU_ENV " + new Gson().toJson(environment));
            return context;
        } catch (Throwable failure) {
            try { context.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw new AssertionError("GPU checks NOT RUN for " + suite + ": context initialization failed", failure);
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (window != 0) {
            GL.setCapabilities(null);
            glfwMakeContextCurrent(0);
            glfwDestroyWindow(window);
            window = 0;
        }
        if (initialized) { glfwTerminate(); initialized = false; }
        if (callbackInstalled) glfwSetErrorCallback(previousErrors);
        if (errors != null) errors.free();
    }
}
