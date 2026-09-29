package me.cortex.voxy.client.iris;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Cold-path status written while Iris builds a shader pipeline and read by the config tooltip.
 */
public final class LiteShaderStatus {
    public enum Code {
        DISABLED,
        REQUESTED,
        ACTIVE,
        NO_VOXY_PATCH,
        MISSING_PROGRAMS,
        MISSING_CONTRACT,
        CONTRACT_MISMATCH,
        API_MISMATCH,
        TRANSITION_REQUIRED,
        ERROR
    }

    public record Snapshot(Code code, String packName, String testedVersions, String detail) {
        private static String clean(String value) {
            return value == null ? "" : value;
        }

        public Snapshot {
            packName = clean(packName);
            testedVersions = clean(testedVersions);
            detail = clean(detail);
        }
    }

    private static volatile Snapshot current = new Snapshot(Code.DISABLED, "", "", "");
    private static Code lastWarnedCode;
    private static String lastWarnedDetail = "";

    private LiteShaderStatus() {
    }

    public static Snapshot get() {
        return current;
    }

    public static void set(Code code) {
        current = new Snapshot(code, "", "", "");
        if (code == Code.DISABLED) {
            clearWarningDedupe();
        }
    }

    public static void set(Code code, String detail) {
        current = new Snapshot(code, "", "", detail);
    }

    public static void active(String packName, String testedVersions, String transition) {
        current = new Snapshot(Code.ACTIVE, packName, testedVersions, transition);
        clearWarningDedupe();
    }

    public static void fail(Code code) {
        fail(code, "");
    }

    public static void fail(Code code, String detail) {
        Snapshot failure = new Snapshot(code, "", "", detail);
        current = failure;
        synchronized (LiteShaderStatus.class) {
            if (lastWarnedCode == failure.code() && lastWarnedDetail.equals(failure.detail())) {
                return;
            }
            lastWarnedCode = failure.code();
            lastWarnedDetail = failure.detail();
        }

        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.gui != null) {
                minecraft.gui.getChat().addMessage(
                        Component.translatable("voxy.lodLiteShading.chatFailure", reason(failure)));
            }
        });
    }

    private static Component reason(Snapshot failure) {
        return switch (failure.code()) {
            case NO_VOXY_PATCH -> Component.translatable("voxy.config.general.lodLiteShading.status.noPatch");
            case MISSING_PROGRAMS -> Component.translatable("voxy.config.general.lodLiteShading.status.missingPrograms");
            case MISSING_CONTRACT -> Component.translatable("voxy.config.general.lodLiteShading.status.missingContract");
            case CONTRACT_MISMATCH -> Component.translatable("voxy.config.general.lodLiteShading.status.contractMismatch");
            case API_MISMATCH -> Component.translatable("voxy.config.general.lodLiteShading.status.apiMismatch", failure.detail());
            case TRANSITION_REQUIRED -> Component.translatable("voxy.config.general.lodLiteShading.status.transitionRequired");
            case ERROR -> Component.translatable("voxy.config.general.lodLiteShading.status.error", failure.detail());
            default -> Component.literal(failure.code().name());
        };
    }

    private static synchronized void clearWarningDedupe() {
        lastWarnedCode = null;
        lastWarnedDetail = "";
    }
}
