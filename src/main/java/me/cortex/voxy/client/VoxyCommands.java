package me.cortex.voxy.client;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.distgen.DistantGenerationManager;
import me.cortex.voxy.common.DebugUtils;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.importers.DHImporter;
import me.cortex.voxy.commonImpl.importers.WorldImporter;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;


public class VoxyCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        var imports = Commands.literal("import")
                .then(Commands.literal("world")
                        .then(Commands.argument("world_name", StringArgumentType.string())
                                .suggests(VoxyCommands::importWorldSuggester)
                                .executes(VoxyCommands::importWorld)))
                .then(Commands.literal("bobby")
                        .then(Commands.argument("world_name", StringArgumentType.string())
                                .suggests(VoxyCommands::importBobbySuggester)
                                .executes(VoxyCommands::importBobby)))
                .then(Commands.literal("raw")
                        .then(Commands.argument("path", StringArgumentType.string())
                                .executes(VoxyCommands::importRaw)))
                .then(Commands.literal("zip")
                        .then(Commands.argument("zipPath", StringArgumentType.string())
                                .executes(VoxyCommands::importZip)
                                .then(Commands.argument("innerPath", StringArgumentType.string())
                                        .executes(VoxyCommands::importZip))))
                .then(Commands.literal("current")
                        .executes(VoxyCommands::importCurrentWorldIn))
                .then(Commands.literal("cancel")
                        .executes(VoxyCommands::cancelImport));

        if (hasDistantHorizonsImportLibraries()) {
            imports = imports
                    .then(Commands.literal("distant_horizons")
                            .then(Commands.argument("sqlDbPath", StringArgumentType.string())
                                    .executes(VoxyCommands::importDistantHorizons)));
        }

        var debug = Commands.literal("debug")
                .then(Commands.literal("verifyTLNChildMask")
                        .executes(ctx->verifyTLNs(ctx, false))
                        .then(Commands.argument("attemptRepair", BoolArgumentType.bool())
                                .executes(ctx->verifyTLNs(ctx, BoolArgumentType.getBool(ctx, "attemptRepair"))))
                );

        return Commands.literal("voxy")//.requires((ctx)-> VoxyCommon.getInstance() != null)
                .then(Commands.literal("reload")
                        .executes(VoxyCommands::reloadInstance))
                .then(Commands.literal("distantgen")
                        .then(Commands.literal("status")
                                .executes(VoxyCommands::distantGenStatus))
                        .then(Commands.literal("pause")
                                .executes(ctx->distantGenSetPaused(ctx, true)))
                        .then(Commands.literal("resume")
                                .executes(ctx->distantGenSetPaused(ctx, false)))
                        .then(Commands.literal("reset")
                                .executes(VoxyCommands::distantGenReset)))
                .then(Commands.literal("colorfix")
                        .executes(VoxyCommands::toggleColorFix)
                        .then(Commands.literal("on").executes(ctx->setColorFix(ctx, true)))
                        .then(Commands.literal("off").executes(ctx->setColorFix(ctx, false))))
                .then(Commands.literal("curvefix")
                        .executes(VoxyCommands::toggleCurveFix)
                        .then(Commands.literal("on").executes(ctx->setCurveFix(ctx, true)))
                        .then(Commands.literal("off").executes(ctx->setCurveFix(ctx, false))))
                .then(imports)
                .then(debug);
    }

    private static int toggleColorFix(CommandContext<CommandSourceStack> ctx) {
        return setColorFix(ctx, !VoxyConfig.CONFIG.colorFix);
    }

    private static int setColorFix(CommandContext<CommandSourceStack> ctx, boolean on) {
        VoxyConfig.CONFIG.colorFix = on;
        ctx.getSource().sendSuccess(()->Component.literal("Voxy LOD colour fix " + (on ? "ENABLED" : "DISABLED (raw brightness)")), false);
        return 0;
    }

    private static int toggleCurveFix(CommandContext<CommandSourceStack> ctx) {
        return setCurveFix(ctx, !VoxyConfig.CONFIG.curveFix);
    }

    private static int setCurveFix(CommandContext<CommandSourceStack> ctx, boolean on) {
        VoxyConfig.CONFIG.curveFix = on;
        ctx.getSource().sendSuccess(()->Component.literal("Voxy world-curve fix " + (on ? "ENABLED (seamless + smooth)" : "DISABLED (original curve)")), false);
        return 0;
    }

    private static int distantGenStatus(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        if (!VoxyConfig.CONFIG.distantGenEnabled) {
            src.sendSuccess(()->Component.literal("Distant generation is disabled in the Voxy config"), false);
            return 0;
        }
        if (Minecraft.getInstance().getSingleplayerServer() == null) {
            src.sendSuccess(()->Component.literal("Distant generation only runs on the integrated server (singleplayer/LAN host)"), false);
            return 0;
        }
        src.sendSuccess(()->Component.literal("Distant generation: " + (DistantGenerationManager.isPaused()?"paused":"running")
                + ", radius " + VoxyConfig.CONFIG.distantGenRadius + " chunks"), false);
        var lines = DistantGenerationManager.statusLines();
        if (lines.isEmpty()) {
            src.sendSuccess(()->Component.literal("  (no active generators yet)"), false);
        }
        for (var line : lines) {
            src.sendSuccess(()->Component.literal("  " + line), false);
        }
        return 0;
    }

    private static int distantGenSetPaused(CommandContext<CommandSourceStack> ctx, boolean pause) {
        DistantGenerationManager.setPaused(pause);
        ctx.getSource().sendSuccess(()->Component.literal("Distant generation " + (pause?"paused":"resumed")), false);
        return 0;
    }

    private static int distantGenReset(CommandContext<CommandSourceStack> ctx) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        var level = Minecraft.getInstance().level;
        if (server == null || level == null) {
            ctx.getSource().sendFailure(Component.literal("Distant generation only runs on the integrated server"));
            return 1;
        }
        var dim = level.dimension();
        //Generator state is owned by the server thread
        server.execute(()->{
            var serverLevel = server.getLevel(dim);
            if (serverLevel != null) {
                DistantGenerationManager.resetLevel(serverLevel);
            }
        });
        ctx.getSource().sendSuccess(()->Component.literal("Distant generation progress reset for " + dim.location() + " (terrain will re-ingest)"), false);
        return 0;
    }

    private static boolean hasDistantHorizonsImportLibraries() {
        try {
            var loader = VoxyCommands.class.getClassLoader();
            Class.forName("org.sqlite.JDBC", false, loader);
            Class.forName("org.tukaani.xz.XZInputStream", false, loader);
            Class.forName("org.lwjgl.util.zstd.Zstd", false, loader);
            return true;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            Logger.warn("Distant Horizons import command disabled because sqlite, xz, or zstd is unavailable", e);
            return false;
        }
    }

    private static int reloadInstance(CommandContext<CommandSourceStack> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var wr = Minecraft.getInstance().levelRenderer;
        if (wr!=null) {
            ((IGetVoxyRenderSystem)wr).voxy$shutdownRenderer();
        }

        VoxyCommon.shutdownInstance();
        System.gc();
        VoxyCommon.createInstance();

        var r = Minecraft.getInstance().levelRenderer;
        if (r != null) r.allChanged();
        return 0;
    }

    private static int verifyTLNs(CommandContext<CommandSourceStack> ctx, boolean attemptRepair) {
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        if (Minecraft.getInstance().level == null) {
            throw new IllegalStateException("How you even do this");
        }
        DebugUtils.verifyAllTopLevelNodes(WorldIdentifier.ofEngine(Minecraft.getInstance().level), attemptRepair);
        return 0;
    }


    private static int importDistantHorizons(CommandContext<CommandSourceStack> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var dbFile = new File(ctx.getArgument("sqlDbPath", String.class));
        if (!dbFile.exists()) {
            ctx.getSource().sendFailure(Component.literal("Database not found: " + dbFile));
            return 1;
        }
        if (dbFile.isDirectory()) {
            dbFile = dbFile.toPath().resolve("DistantHorizons.sqlite").toFile();
            if (!dbFile.exists()) {
                ctx.getSource().sendFailure(Component.literal("Database not found: " + dbFile));
                return 1;
            }
        }

        File dbFile_ = dbFile;
        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine==null) {
            ctx.getSource().sendFailure(Component.literal("Unable to resolve a voxy engine for the current world"));
            return 1;
        }
        var ok = instance.getImportManager().makeAndRunIfNone(engine, ()->
                new DHImporter(dbFile_, engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter));
        if (!ok) {
            ctx.getSource().sendFailure(Component.literal("An import is already running for this world (use /voxy import cancel to cancel it)"));
        }
        return ok?0:1;
    }

    private static boolean fileBasedImporter(CommandSourceStack src, File directory) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            return false;
        }

        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine==null) {
            src.sendFailure(Component.literal("Unable to resolve a voxy engine for the current world"));
            return false;
        }
        var ok = instance.getImportManager().makeAndRunIfNone(engine, ()->{
            var importer = new WorldImporter(engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter);
            importer.importRegionDirectoryAsync(directory);
            return importer;
        });
        if (!ok) {
            src.sendFailure(Component.literal("An import is already running for this world (use /voxy import cancel to cancel it)"));
        }
        return ok;
    }

    private static int importRaw(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        return fileBasedImporter(ctx.getSource(), new File(ctx.getArgument("path", String.class)))?0:1;
    }

    private static int importBobby(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var file = new File(".bobby").toPath().resolve(ctx.getArgument("world_name", String.class)).toFile();
        return fileBasedImporter(ctx.getSource(), file)?0:1;
    }

    private static CompletableFuture<Suggestions> importWorldSuggester(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder sb) {
        return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve("saves"), sb);
    }
    private static CompletableFuture<Suggestions> importBobbySuggester(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder sb) {
        return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve(".bobby"), sb);
    }

    private static CompletableFuture<Suggestions> fileDirectorySuggester(Path dir, SuggestionsBuilder sb) {
        var str = sb.getRemaining().replace("\\\\", "\\").replace("\\", "/");
        if (str.startsWith("\"")) {
            str = str.substring(1);
        }
        if (str.endsWith("\"")) {
            str = str.substring(0,str.length()-1);
        }
        var remaining = str;
        if (str.contains("/")) {
            int idx = str.lastIndexOf('/');
            remaining = str.substring(idx+1);
            try {
                dir = dir.resolve(str.substring(0, idx));
            } catch (Exception e) {
                return Suggestions.empty();
            }
            str = str.substring(0, idx+1);
        } else {
            str = "";
        }

        try {
            var worlds = Files.list(dir).toList();
            for (var world : worlds) {
                if (!world.toFile().isDirectory()) {
                    continue;
                }
                var wn = world.getFileName().toString();
                if (wn.equals(remaining)) {
                    continue;
                }
                if (SharedSuggestionProvider.matchesSubStr(remaining, wn) || SharedSuggestionProvider.matchesSubStr(remaining, '"'+wn)) {
                    wn = str+wn + "/";
                    sb.suggest(StringArgumentType.escapeIfRequired(wn));
                }
            }
        } catch (IOException e) {}

        return sb.buildFuture();
    }


    private static int importCurrentWorldIn(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var localServer = Minecraft.getInstance().getSingleplayerServer();
        if (localServer == null) {
            ctx.getSource().sendFailure(Component.translatable("You must be in single player to use this command"));
            return 1;
        }
        var regionPath = DimensionType.getStorageFolder(Minecraft.getInstance().level.dimension(), localServer.getWorldPath(LevelResource.ROOT)).resolve("region");
        if ((!regionPath.toFile().exists())||!regionPath.toFile().isDirectory()) {
            ctx.getSource().sendFailure(Component.translatable("Cannot find region folder for current dimension"));
            return 1;
        }
        return fileBasedImporter(ctx.getSource(), regionPath.toFile())?0:1;
    }

    private static int importWorld(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var name = ctx.getArgument("world_name", String.class);
        var file = new File("saves").toPath().resolve(name);
        name = name.toLowerCase(Locale.ROOT);
        if (name.endsWith("/")) {
            name = name.substring(0, name.length()-1);
        }
        if (file.resolve("level.dat").toFile().exists()) {
            var dimFile = DimensionType.getStorageFolder(Minecraft.getInstance().level.dimension(), file)
                    .resolve("region")
                    .toFile();
            if (!dimFile.isDirectory()) {
                ctx.getSource().sendFailure(Component.literal("Region folder not found for world '" + name + "': " + dimFile));
                return 1;
            }
            return fileBasedImporter(ctx.getSource(), dimFile)?0:1;
            //We are in a world directory, so import the current dimension we are in
            /*
            for (var dim : new String[]{"overworld", "the_nether", "the_end"}) {//This is so annoying that you cant loop through all the dimensions
                var id = ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace(dim));
                var dimPath = DimensionType.getStorageFolder(id, file);
                dimPath = dimPath.resolve("region");
                var dimFile = dimPath.toFile();
                if (dimFile.isDirectory()) {//exists and is a directory
                    if (!fileBasedImporter(dimFile)) {
                        Logger.error("Failed to import dimension: " + id);
                    }
                }
            }*/
        } else {
            if (!(name.endsWith("region"))) {
                file = file.resolve("region");
            }
            if (!file.toFile().isDirectory()) {
                ctx.getSource().sendFailure(Component.literal("Region folder not found: " + file));
                return 1;
            }
            return fileBasedImporter(ctx.getSource(), file.toFile()) ? 0 : 1;
        }
    }

    private static int importZip(CommandContext<CommandSourceStack> ctx) {
        var zip =  new File(ctx.getArgument("zipPath", String.class));
        var innerDir = "region/";
        try {
            innerDir = ctx.getArgument("innerPath", String.class);
        } catch (Exception e) {}

        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        String finalInnerDir = innerDir;

        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine != null) {
            var ok = instance.getImportManager().makeAndRunIfNone(engine, () -> {
                var importer = new WorldImporter(engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter);
                importer.importZippedRegionDirectoryAsync(zip, finalInnerDir);
                return importer;
            });
            if (!ok) {
                ctx.getSource().sendFailure(Component.literal("An import is already running for this world (use /voxy import cancel to cancel it)"));
            }
            return ok ? 0 : 1;
        }
        return 1;
    }

    private static int cancelImport(CommandContext<CommandSourceStack> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var world = WorldIdentifier.ofEngineNullable(Minecraft.getInstance().level);
        if (world != null) {
            return instance.getImportManager().cancelImport(world)?0:1;
        }
        return 1;
    }
}
