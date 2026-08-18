package org.vmstudio.visor.core.client.render;


import lombok.Getter;
import me.phoenixra.atumvr.api.utils.GLUtils;
import org.vmstudio.visor.core.client.render.helpers.RenderShaderHelper;
import org.vmstudio.visor.core.client.render.shaders.*;


public class VRShaders {

    @Getter
    private static VRShaderPostProcessEye postProcess;

    @Getter
    private static VRShaderMixedReality mixedReality;

    @Getter
    private static VRShaderTeleportPoint teleportPoint;

    @Getter
    private static VRShaderEndPortal endPortal;

    @Getter
    private static VRShaderInBlockVignette inBlockVignette;


    private VRShaders() {

    }

    /**
     * (Re)allocates every Visor shader resource.
     * <p>
     * Called from {@code VRRendererBase.reinitTargets()}, which runs whenever the eye targets
     * change - so unlike the old link-once model this has to release what a previous run
     * allocated, hence the leading {@link #close()}.
     */
    public static void setup() throws Exception {
        close();

        RenderShaderHelper.setup();

        postProcess = init(new VRShaderPostProcessEye(), "PostProcess");
        mixedReality = init(new VRShaderMixedReality(), "MixedReality");
        teleportPoint = init(new VRShaderTeleportPoint(), "TeleportPoint");
        endPortal = init(new VRShaderEndPortal(), "EndPortal");
        inBlockVignette = init(new VRShaderInBlockVignette(), "InBlockVignette");
    }

    private static <T extends VRShader> T init(T shader, String label) throws Exception {
        shader.init();
        GLUtils.checkGLError("init " + label + " shader");
        return shader;
    }


    /** Releases every GPU buffer Visor owns here. Safe to call when nothing is allocated. */
    public static void close() {
        postProcess = closeOne(postProcess);
        mixedReality = closeOne(mixedReality);
        teleportPoint = closeOne(teleportPoint);
        endPortal = closeOne(endPortal);
        inBlockVignette = closeOne(inBlockVignette);
        RenderShaderHelper.close();
    }

    private static <T extends VRShader> T closeOne(T shader) {
        if (shader != null) {
            shader.close();
        }
        return null;
    }


    /**
     * Rotates every uniform ring buffer. Must be called once per VR frame; without it the CPU
     * blocks on a buffer the GPU is still reading, which shows up as a headset-only stutter
     * that never reproduces on the desktop mirror.
     */
    public static void endFrame() {
        endFrameOf(postProcess);
        endFrameOf(mixedReality);
        endFrameOf(teleportPoint);
        endFrameOf(endPortal);
        endFrameOf(inBlockVignette);
    }

    private static void endFrameOf(VRShader shader) {
        if (shader != null) {
            shader.endFrame();
        }
    }


}
