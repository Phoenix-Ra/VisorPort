package org.vmstudio.visor.mixin.client.renderer;

import org.vmstudio.visor.core.client.VisorState;
import org.vmstudio.visor.mixin.client.accessors.SectionOcclusionGraphAccessor;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class NoSodiumLevelRendererMixin {

    @Shadow
    @Final
    private SectionOcclusionGraph sectionOcclusionGraph;

    /**
     * 1.21.1: needsFullRenderChunkUpdate/needsFrustumUpdate moved from
     * LevelRenderer into SectionOcclusionGraph
     * <p>
     * PORT-1.21.11: setupRender was renamed to cullTerrain and lost its
     * hasCapturedFrustum parameter (the capture is now read off the field
     * inline). The body is otherwise unchanged - it still skips applyFrustum
     * unless consumeFrustumUpdate() or a camera rotation change says
     * otherwise, which is exactly what this hook has to defeat per eye.
     */
    @Inject(method = "cullTerrain", at = @At("HEAD"))
    private void visor$alwaysUpdateCull(CallbackInfo ci) {
        if (VisorState.get().isActive()) {
            // fixes chunks cull frustum between displays
            this.sectionOcclusionGraph.invalidate();
            ((SectionOcclusionGraphAccessor) this.sectionOcclusionGraph).getNeedsFrustumUpdate().set(true);
        }
    }
}
