package me.cortex.voxy.testing;

import java.lang.reflect.InvocationTargetException;

/** Accesses renderer lifecycle operations without creating a Minecraft window. */
public final class Reflect {
    private Reflect() {}

    @SuppressWarnings("unchecked")
    public static <T> T get(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    public static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    public static void clearStatic(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, null);
    }

    public static void call(Object target, String name, Object... args) throws Exception {
        for (var method : target.getClass().getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            method.setAccessible(true);
            try {
                method.invoke(target, args);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof Exception exception) throw exception;
                if (e.getCause() instanceof Error error) throw error;
                throw e;
            }
            return;
        }
        throw new NoSuchMethodException(name);
    }
}
