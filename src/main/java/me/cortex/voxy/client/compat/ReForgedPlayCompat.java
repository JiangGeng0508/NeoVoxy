package me.cortex.voxy.client.compat;

import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ReForgedPlayCompat {
    private static final String REPLAY_MOD_REPLAY_CLASS = "com.replaymod.replay.ReplayModReplay";
    private static final String VOXY_STORAGE_PATH_ENTRY = "voxy_storage_path";
    private static final long CACHED_REPLAY_FILE_TIMEOUT_NANOS = 30_000_000_000L;
    private static volatile Object activeReplayFile;
    private static volatile long activeReplayFileSetTime;

    public static void beginReplay(Object replayFile) {
        activeReplayFile = replayFile;
        activeReplayFileSetTime = System.nanoTime();
    }

    public static void endReplay() {
        activeReplayFile = null;
        activeReplayFileSetTime = 0;
    }

    public static Path getReplayStoragePath() {
        try {
            Object replayFile = getActiveReplayFile();
            if (replayFile == null) {
                return null;
            }

            Path embeddedPath = getEmbeddedStoragePath(replayFile);
            if (embeddedPath != null) {
                Logger.info("ReForgedPlay replay contains Voxy storage path, using this as lod data source");
                return embeddedPath;
            }

            Object metadata = invoke(replayFile, "getMetaData");
            if (metadata == null) {
                return null;
            }

            boolean singleplayer = (Boolean) invoke(metadata, "isSingleplayer");
            String serverName = (String) invoke(metadata, "getServerName");
            if (serverName == null || serverName.isBlank()) {
                return null;
            }

            Path path = singleplayer ? getSingleplayerStoragePath(serverName) : getMultiplayerStoragePath(serverName);
            if (path != null && Files.exists(path)) {
                Logger.info("ReForgedPlay replay storage path resolved to existing Voxy data source: " + path);
                return path;
            }

            if (path != null) {
                Logger.warn("ReForgedPlay replay storage path was resolved but does not exist: " + path);
                return path;
            }
        } catch (ClassNotFoundException ignored) {
            return null;
        } catch (Throwable throwable) {
            Logger.warn("Failed to resolve ReForgedPlay replay storage path: " + throwable);
        }
        return null;
    }

    private static Object getActiveReplayFile() throws ReflectiveOperationException {
        Class<?> replayModReplay = Class.forName(REPLAY_MOD_REPLAY_CLASS, false, ReForgedPlayCompat.class.getClassLoader());
        Field instanceField = replayModReplay.getField("instance");
        Object instance = instanceField.get(null);
        if (instance == null) {
            return null;
        }

        Object replayHandler = invoke(instance, "getReplayHandler");
        if (replayHandler == null) {
            Object replayFile = activeReplayFile;
            if (replayFile != null && System.nanoTime() - activeReplayFileSetTime < CACHED_REPLAY_FILE_TIMEOUT_NANOS) {
                return replayFile;
            }
            return null;
        }
        return invoke(replayHandler, "getReplayFile");
    }

    private static Path getEmbeddedStoragePath(Object replayFile) {
        Path path = readReplayFilePathEntry(replayFile, VOXY_STORAGE_PATH_ENTRY);
        if (path == null) {
            path = readReplayFilePathEntry(replayFile, VOXY_STORAGE_PATH_ENTRY + ".txt");
        }
        if (path != null && Files.exists(path)) {
            return path.toAbsolutePath().normalize();
        }
        return null;
    }

    private static Path readReplayFilePathEntry(Object replayFile, String entry) {
        try {
            Object optional = invoke(replayFile, "get", new Class<?>[]{String.class}, entry);
            if (optional == null || !(Boolean) invoke(optional, "isPresent")) {
                return null;
            }

            try (InputStream input = (InputStream) invoke(optional, "get")) {
                String value = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
                if (!value.isEmpty()) {
                    return Path.of(value);
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    private static Path getSingleplayerStoragePath(String levelName) {
        Path saves = Minecraft.getInstance().gameDirectory.toPath().resolve("saves");

        Path direct = saves.resolve(levelName).resolve("voxy");
        if (Files.exists(direct)) {
            return direct.toAbsolutePath().normalize();
        }

        Path matchedSave = findSaveByLevelName(saves, levelName);
        if (matchedSave != null) {
            return matchedSave.resolve("voxy").toAbsolutePath().normalize();
        }

        return direct.toAbsolutePath().normalize();
    }

    private static Path findSaveByLevelName(Path saves, String levelName) {
        if (!Files.isDirectory(saves)) {
            return null;
        }

        try (var stream = Files.list(saves)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(path -> levelName.equals(readLevelName(path.resolve("level.dat"))))
                    .findFirst()
                    .orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readLevelName(Path levelDat) {
        if (!Files.isRegularFile(levelDat)) {
            return null;
        }

        try {
            var root = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
            if (root == null || !root.contains("Data")) {
                return null;
            }
            var data = root.getCompound("Data");
            return data.contains("LevelName") ? data.getString("LevelName") : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Path getMultiplayerStoragePath(String serverName) {
        Path basePath = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy").resolve("saves");
        if ("A Realms Server".equals(serverName)) {
            return basePath.resolve("realms").toAbsolutePath().normalize();
        }
        return basePath.resolve(serverName.replace(":", "_")).toAbsolutePath().normalize();
    }

    private static Object invoke(Object target, String name) throws ReflectiveOperationException {
        return invoke(target, name, new Class<?>[0]);
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... args) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name, parameterTypes);
        return method.invoke(target, args);
    }
}
