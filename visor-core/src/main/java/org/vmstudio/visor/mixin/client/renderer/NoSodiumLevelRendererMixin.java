package org.vmstudio.visor.mixin.client.renderer;

import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vmstudio.visor.core.client.VisorState;
import org.vmstudio.visor.mixin.client.accessors.SectionOcclusionGraphAccessor;

import static org.vmstudio.visor.core.client.VisorClientImpl.MC;

/**
 * PORT-26.2: LevelRenderer.cullTerrain is gone. The "skip the frustum update unless something
 * asked for one" decision moved with the extraction half: {@code LevelExtractor.extract} is what
 * calls {@code SectionOcclusionGraph.consumeFrustumUpdate()} now, so that is where the skip has
 * to be defeated per eye.
 * <p>
 * The graph itself stayed on {@code LevelRenderer} as a private field, which is why the class name
 * still says LevelRenderer and why {@code sectionOcclusionGraph} is widened in visor.accesswidener
 * - a {@code @Shadow} cannot reach across from a mixin that targets the extractor.
 */
@Mixin(LevelExtractor.class)
public class NoSodiumLevelRendererMixin {

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
    @Inject(method = "extract", at = @At("HEAD"))
    private void visor$alwaysUpdateCull(CallbackInfo ci) {
        if (VisorState.get().isNotActive() || MC.levelRenderer == null) {
            return;
        }
        SectionOcclusionGraph graph = MC.levelRenderer.sectionOcclusionGraph;
        if (graph == null) {
            return;
        }
        // fixes chunks cull frustum between displays
        //
        // PORT-26.2: only the frustum half. The invalidate() this also used to force is a *full*
        // graph rebuild, and 26.2's scheduleFullUpdate captures the shared CameraRenderState by
        // reference and reads pos/blockPos/smartCull from a background thread - the next eye's
        // extract overwrites that object microseconds later, so forcing a rebuild per pass made
        // every frame race and cull sections for a viewpoint that was never rendered. Per-eye
        // correctness only needs applyFrustum to re-run (the eyes share a graph origin);
        // vanilla's own invalidateIfNeeded still handles real camera movement.
        ((SectionOcclusionGraphAccessor) graph).getNeedsFrustumUpdate().set(true);
    }
}
