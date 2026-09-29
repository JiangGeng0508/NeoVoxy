package me.cortex.voxy.client.core.gl.shader;


import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;

import org.apache.commons.io.IOUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public class ShaderLoader {
    public static String parse(String id) {
        var src =  "#version 460 core\n";
        src += String.join("\n", ShaderLoadingParser.parseRoot(ResourceLocation.parse(id)));
        return src;
    }


    //Use our own loader

    private static final class ShaderLoadingParser {
        private static final Pattern IMPORT_PATTERN = Pattern.compile("#import <(?<namespace>.*):(?<path>.*)>");
        public static List<String> parseRoot(ResourceLocation id) {
            List<String> out = new ArrayList<>();
            for (var line : toLines(loadShaderAsset(id))) {
                if (line.startsWith("#version")) {
                    continue;
                } else if (line.startsWith("#import")) {
                    var match = IMPORT_PATTERN.matcher(line);
                    if (!match.matches()) throw new IllegalArgumentException("Unknown import: " + line);
                    var iid = ResourceLocation.fromNamespaceAndPath(match.group("namespace"), match.group("path"));
                    out.addAll(parseRoot(iid));
                } else {
                    out.add(line);
                }
            }
            return out;
        }

        private static List<String> toLines(String src) {
            return new BufferedReader(new StringReader(src)).lines().toList();
        }
        private static String loadShaderAsset(ResourceLocation id) {
            String path = String.format("/assets/%s/shaders/%s", id.getNamespace(), id.getPath());
            try (InputStream in = ShaderLoadingParser.class.getResourceAsStream(path)) {
                if (in != null) {
                    return IOUtils.toString(in, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to read shader source for " + path, e);
            }

            //The classpath lookup can miss assets that only exist in a resource pack (or that the
            //loader exposes through the resource manager instead of our own classloader), so retry
            //through the client resource manager before giving up.
            var resourceId = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "shaders/" + id.getPath());
            try {
                var minecraft = Minecraft.getInstance();
                var resourceManager = minecraft == null ? null : minecraft.getResourceManager();
                if (resourceManager != null) {
                    var resource = resourceManager.getResource(resourceId);
                    if (resource.isPresent()) {
                        try (InputStream in = resource.get().open()) {
                            return IOUtils.toString(in, StandardCharsets.UTF_8);
                        }
                    }
                }
            } catch (Throwable ignored) {
                //Fall through to the original error; the classpath lookup is still the primary path.
            }

            throw new RuntimeException("Shader not found: " + path + " or " + resourceId);
        }
    }
}
