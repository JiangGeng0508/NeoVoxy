package me.cortex.voxy.commonImpl.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import me.cortex.voxy.common.util.ChunkUnloadBudget;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Queue;

@Mixin(ChunkMap.class)
public abstract class MixinChunkMap {
    @WrapOperation(method = "processUnloads", at = @At(value = "INVOKE",
            target = "Ljava/util/Queue;poll()Ljava/lang/Object;"))
    private Object voxy$yieldUnloadRetries(Queue<Runnable> queue, Operation<Object> original,
                                           @Share("voxy$unloadBudget") LocalRef<ChunkUnloadBudget> budget) {
        // A holder can gain generation references after unload was scheduled. If its
        // save future is already done, scheduleUnload immediately enqueues another retry.
        // During shutdown the vanilla time supplier is always true: draining those new
        // retries here can starve the chunk tasks needed to release the references.
        // Keep every retry queued, but run at most the callbacks present at the first poll.
        if (budget.get() == null) budget.set(new ChunkUnloadBudget());
        return budget.get().take(queue) ? original.call(queue) : null;
    }
}
