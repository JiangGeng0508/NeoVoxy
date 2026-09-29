package me.cortex.voxy.commonImpl.mixin.simplebackups;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Voxy's per-world directory is a live, regenerable cache, not world-authoritative data.  Archiving
 * RocksDB while it compacts can observe files disappearing between enumeration and opening; on
 * Windows its native lock can also make a live copy fail.  Wrap SimpleBackups' visitor so the cache is
 * excluded without a compile/runtime dependency on SimpleBackups and without touching its config.
 */
@Mixin(targets = "de.melanx.simplebackups.BackupThread", remap = false)
public abstract class MixinBackupThread {
    @Redirect(
            method = "makeWorldBackup",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/nio/file/Files;walkFileTree(Ljava/nio/file/Path;Ljava/nio/file/FileVisitor;)Ljava/nio/file/Path;"
            )
    )
    private Path voxy$excludeLiveCache(Path worldRoot, FileVisitor<? super Path> visitor) throws IOException {
        Path voxyCache = worldRoot.resolve("voxy").normalize();
        return Files.walkFileTree(worldRoot, new FileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.normalize().equals(voxyCache)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return visitor.preVisitDirectory(dir, attrs);
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                return visitor.visitFile(file, attrs);
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                return visitor.visitFileFailed(file, exception);
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exception) throws IOException {
                return visitor.postVisitDirectory(dir, exception);
            }
        });
    }
}
