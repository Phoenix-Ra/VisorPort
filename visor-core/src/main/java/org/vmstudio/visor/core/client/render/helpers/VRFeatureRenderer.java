package org.vmstudio.visor.core.client.render.helpers;

import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;

import static org.vmstudio.visor.core.client.VisorClientImpl.MC;

/**
 * PORT-26.2: a {@link FeatureRenderDispatcher} of Visor's own, for drawing submitted geometry in
 * the middle of a level pass.
 * <p>
 * Visor draws its VR hands (and the item-activation effect) by submitting nodes and immediately
 * draining them, so the draw lands under the matrices that are bound right then - the alternative
 * is the 1.21.11 bug where the hand survived to vanilla's next flush and rendered through the flat
 * hud3d projection.
 * <p>
 * In 26.2 that drain cannot go through vanilla's dispatcher any more. A dispatcher owns exactly one
 * reusable {@code PreparedFrame}, and {@code begin} is a re-entrancy lock - it throws
 * "PreparedFrame already in use" if one is already open. Vanilla gets away with the same
 * {@code renderAllFeatures(handAndScreenSubmitNodeStorage)} call because it drains from
 * {@code renderItemInHand}, after {@code LevelRenderer.render} has closed its frame; Visor drains
 * from inside the level pass, while that frame is still open.
 * <p>
 * A second dispatcher is the smallest fix that keeps the draw where it has to be. It gets its own
 * {@link RenderBuffers} rather than sharing {@code GameRenderer}'s: the frame context carries a
 * {@code StagedVertexBuffer}, and driving vanilla's while its frame is mid-flight would reset a
 * batch that still has pending draws in it. The {@code RenderBuffers} size argument only sizes the
 * chunk-section pool, which this dispatcher never touches, so it is allocated at the minimum.
 */
public final class VRFeatureRenderer {

    private static RenderBuffers buffers;
    private static FeatureRenderDispatcher dispatcher;
    private static SubmitNodeStorage storage;

    private VRFeatureRenderer() {
    }

    /** The collector Visor submits into. Its nodes are consumed by {@link #drain()}. */
    public static SubmitNodeCollector collector() {
        ensureCreated();
        return storage;
    }

    /**
     * Renders everything submitted since the last drain, now.
     * <p>
     * Safe to call inside a level pass - this dispatcher's frame is independent of the one
     * {@code LevelRenderer} holds open.
     */
    public static void drain() {
        ensureCreated();
        dispatcher.renderAllFeatures(storage);
        // Recycle the staged buffers this drain allocated - vanilla's dispatcher gets this from
        // renderBuffers.endFrame() at GameRenderer.render()'s tail, which never runs for this
        // private RenderBuffers; without it every drain allocates fresh GPU buffers for good.
        buffers.endFrame();
    }

    /** Releases the dispatcher and its buffers; the next use rebuilds them. */
    public static void close() {
        if (dispatcher != null) {
            dispatcher.close();
            dispatcher = null;
        }
        if (buffers != null) {
            buffers.close();
            buffers = null;
        }
        storage = null;
    }

    private static void ensureCreated() {
        if (dispatcher != null) {
            return;
        }
        buffers = new RenderBuffers(1);
        dispatcher = new FeatureRenderDispatcher(
                buffers,
                MC.getModelManager(),
                MC.getAtlasManager(),
                MC.font,
                MC.gameRenderer.gameRenderState()
        );
        storage = new SubmitNodeStorage();
    }
}
