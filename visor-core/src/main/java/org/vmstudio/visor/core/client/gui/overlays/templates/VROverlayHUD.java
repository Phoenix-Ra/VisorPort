package org.vmstudio.visor.core.client.gui.overlays.templates;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.vmstudio.visor.api.VisorAPI;
import org.vmstudio.visor.api.compatibility.mcversion.McVersionUtils;
import org.vmstudio.visor.core.client.render.helpers.RenderShaderHelper;
import org.vmstudio.visor.api.client.ClientFeature;
import org.vmstudio.visor.api.client.gui.overlays.options.OptionTextures;
import org.vmstudio.visor.api.client.gui.overlays.options.types.OverlayOptionsGeneral;
import org.vmstudio.visor.api.client.gui.overlays.options.types.OverlayOptionsVisibility;
import org.vmstudio.visor.api.client.gui.overlays.options.types.properties.PropertyBool;
import org.vmstudio.visor.api.client.gui.widgets.info.WidgetInfoButtonImaged;
import org.vmstudio.visor.api.client.player.pose.PoseAnchor;
import org.vmstudio.visor.api.client.events.AllowClientFeatureVREvent;
import org.vmstudio.visor.api.client.gui.overlays.RegisterVROverlayTemplate;
import org.vmstudio.visor.api.client.gui.overlays.framework.template.VROverlayTemplateFrameBuffer;
import org.vmstudio.visor.api.client.gui.overlays.options.OverlayOptionGroup;
import org.vmstudio.visor.api.client.gui.overlays.options.types.OverlayOptionsPose;
import org.vmstudio.visor.api.client.gui.overlays.options.types.OverlayOptionsScreenRegion;
import org.vmstudio.visor.api.common.addon.VisorAddon;
import org.vmstudio.visor.api.common.eventbus.listener.VREventHandler;
import org.vmstudio.visor.api.common.eventbus.listener.VREventListener;
import org.vmstudio.visor.core.client.ClientContext;
import org.jetbrains.annotations.NotNull;
import org.vmstudio.visor.core.client.gui.overlays.builtin.settings.VROverlaySettings;

import java.util.List;

import static org.vmstudio.visor.core.client.VisorClientImpl.MC;

@RegisterVROverlayTemplate(
        id = VROverlayHUD.ID,
        name = VROverlayHUD.NAME,
        description = VROverlayHUD.DESCRIPTION,
        isCreateDefault = true
)
public class VROverlayHUD extends VROverlayTemplateFrameBuffer implements VREventListener {
    public static final String ID = "hud";
    public static final String NAME = "visor.overlay.template."+ID+".name";
    public static final String DESCRIPTION = "visor.overlay.template."+ID+".description";

    /**
     * The region crop draw.
     * <p>
     * PORT-1.21.11: an unblended, full-channel copy, which none of the shared pipelines is -
     * {@code VisorPipelines.POSITION_TEX_NO_DEPTH} composites instead of replacing and would
     * premultiply the crop by its own alpha, and {@code VisorPipelines.MIRROR_BLIT} masks alpha
     * writes off. The alpha is load-bearing here: it is what the overlay panel blends with, and
     * an untouched alpha channel would leave the panel opaque over the whole region.
     */
    private static final RenderPipeline REGION_CROP = RenderPipeline.builder()
            .withLocation(McVersionUtils.newResourceLoc("visor", "pipeline/hud_region_crop"))
            .withVertexShader("core/position_tex")
            .withFragmentShader("core/position_tex")
            .withSampler("Sampler0")
            // core/position_tex reads both blocks. Only Projection is auto-bound by name;
            // DynamicTransforms has to be declared or the shader silently reads whichever
            // buffer the previous draw happened to leave at that binding point.
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS)
            .withoutBlend()
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .build();

    private OverlayOptionsScreenRegion optionsScreenRegion;
    private PropertyBool hudLayerProperty;

    private RegionRenderTarget regionTarget;

    public VROverlayHUD(@NotNull VisorAddon owner,
                        @NotNull String id) {
        super(owner, id);
        setEnabled(true);
        VisorAPI.eventBus().registerListener(owner,this);
    }

    @VREventHandler
    public void enableHUD(AllowClientFeatureVREvent event){
        if(event.getFeature() == ClientFeature.GUI_DISABLE_HUD) {
            if(isVisible()){
                event.setCanceled(true);
            }
        }
    }

    @Override
    public void onRender(float partialTicks) {
        RenderTarget src = ClientContext.renderer.guiTarget.getTarget();
        updateRegionTargetFromSource(src);
    }

    @Override
    public void onPreTick() {
        RenderTarget src = ClientContext.renderer.guiTarget.getTarget();
        updateRegionTargetFromSource(src);
        super.onPreTick();
    }

    @Override
    public boolean updateVisibility() {
        return MC.screen == null
                && MC.player != null;
    }

    @Override
    public boolean supportsVisibilityUpdateOnRender() {
        return true;
    }

    @Override
    public boolean isHudLayer() {
        return hudLayerProperty.getValue();
    }

    private void updateRegionTargetFromSource(RenderTarget src) {
        if (src == null) {
            this.renderTarget = null;
            return;
        }

        // Read region from options; clamp additionally to the current source size for safety
        int guiW = src.width;
        int guiH = src.height;

        int rx = Math.max(0, Math.min(guiW, optionsScreenRegion.getRegionX()));
        int ryTopLeft = Math.max(0, Math.min(guiH, optionsScreenRegion.getRegionY()));
        int rw = Math.max(1, Math.min(guiW - rx, optionsScreenRegion.getRegionWidth()));
        int rh = Math.max(1, Math.min(guiH - ryTopLeft, optionsScreenRegion.getRegionHeight()));

        // Convert Y from top-left origin (GUI) to bottom-left origin (OpenGL framebuffer)
        int srcY0 = guiH - (ryTopLeft + rh);
        int srcY1 = guiH - ryTopLeft;
        int srcX0 = rx;
        int srcX1 = rx + rw;

        // Ensure valid bounds
        if (srcY0 < 0) srcY0 = 0;
        if (srcY1 > guiH) srcY1 = guiH;
        if (srcX0 < 0) srcX0 = 0;
        if (srcX1 > guiW) srcX1 = guiW;

        // Degenerate regions (a region pinned past the edge of a shrunken framebuffer) clamp
        // down to nothing; there is no crop to take in that case.
        if (srcX1 - srcX0 <= 0 || srcY1 - srcY0 <= 0) {
            this.renderTarget = null;
            return;
        }

        // Lazily create/resize the region target
        if (regionTarget == null) {
            regionTarget = new RegionRenderTarget(false);
            regionTarget.resize(rw, rh);
        } else if (regionTarget.width != rw || regionTarget.height != rh) {
            regionTarget.resize(rw, rh);
        }

        copyRegion(src, guiW, guiH, srcX0, srcY0, srcX1, srcY1);

        // Use the cropped texture as the overlay render target
        this.renderTarget = regionTarget;
    }

    /**
     * Copies the region rectangle out of {@code src} into {@link #regionTarget}.
     * <p>
     * PORT-1.21.11: a {@link RenderTarget} no longer exposes a framebuffer id, so the manual
     * read/draw FBO bind plus {@code glBlitFramebuffer} is gone.
     * {@code CommandEncoder.copyTextureToTexture} is not a stand-in for it: its width and height
     * arguments are forwarded into {@code glBlitNamedFramebuffer}'s srcX1/srcY1 slots rather than
     * being extents measured from the source offset, so the only source rectangle it can express
     * is one anchored at the texture origin. That is all vanilla's single caller ever asks for,
     * and wrong for every HUD region that is not flush with the corner. The crop is a textured
     * quad instead: the source rectangle becomes UVs and the whole region target is the
     * destination. Framebuffer texel rows and {@code v} both run bottom-up, so the GL-origin
     * rectangle the caller computed is already the UV rectangle.
     */
    private void copyRegion(RenderTarget src, int guiW, int guiH,
                            int srcX0, int srcY0, int srcX1, int srcY1) {
        // core/position_tex discards fully transparent texels, so unlike a blit this draw leaves
        // them untouched. Clearing first is what keeps the parts of the region the HUD does not
        // cover transparent instead of holding the previous frame.
        RenderSystem.getDevice().createCommandEncoder()
                .clearColorTexture(regionTarget.getColorTexture(), 0x00000000);

        // The quad is supplied in NDC, so both matrices have to be identity rather than whatever
        // projection happens to be current when the HUD updates.
        //
        // PORT-1.21.11: both blocks are produced here rather than in the bindings lambda below.
        // Writing a dynamic uniform goes through the command encoder, and the encoder refuses
        // every command other than the pass's own once the pass is open - doing it from the
        // lambda threw "Close the existing render pass before performing additional commands"
        // on the first HUD tick. identityProjection() allocates on first use and is hoisted for
        // the same reason.
        GpuBufferSlice transforms = RenderShaderHelper.writeIdentityTransform();
        GpuBufferSlice projection = RenderShaderHelper.identityProjection();

        RenderShaderHelper.renderScreenQuad(
                () -> "visor hud region crop",
                REGION_CROP,
                pass -> {
                    pass.setUniform("DynamicTransforms", transforms);
                    pass.setUniform("Projection", projection);
                    // NEAREST like the blit this replaces - source and destination rectangles are
                    // the same size, so there is nothing to interpolate.
                    pass.bindTexture("Sampler0", src.getColorTextureView(),
                            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                },
                regionTarget.getColorTextureView(),
                -1f, -1f, 1f, 1f,
                srcX0 / (float) guiW, srcY0 / (float) guiH,
                srcX1 / (float) guiW, srcY1 / (float) guiH);
    }

    @Override
    protected @NotNull List<OverlayOptionGroup<?>> createTemplateOptions() {
        Component trueLabel = Component.literal(
                Component.translatable("visor.overlay.property.hud_layer").getString()
                + ": " + Component.translatable("options.on").getString()
        );
        Component falseLabel = Component.literal(
                Component.translatable("visor.overlay.property.hud_layer").getString()
                        + ": " + Component.translatable("options.off").getString()
        );
        hudLayerProperty = new PropertyBool(
                "is_hud_layer",
                true,
                trueLabel,
                falseLabel,
                new WidgetInfoButtonImaged()
                        .setTexture(OptionTextures.GRAY_TEXTURE)
                        .setHighlightHovered(OptionTextures.HOVERED_HIGHLIGHT)
                        .setTextColor(VROverlaySettings.TEXT_COLOR)
        );

        optionsScreenRegion =  new OverlayOptionsScreenRegion(
                this,
                VisorAPI.client().getGuiManager().getGuiWidth(),
                VisorAPI.client().getGuiManager().getGuiHeight(),
                ()->ClientContext.renderer.guiTarget.getTarget(),
                (it)->{
                    it.setRegionX(0);
                    it.setRegionY(0);
                    it.setRegionWidth(it.getScreenWidth());
                    // Use the full screen height by default
                    it.setRegionHeight(it.getScreenHeight());
                }
        );
        return List.of(
                new OverlayOptionsVisibility(
                        this,
                        it -> it.setVisible(true)
                ),
                new OverlayOptionsGeneral(
                        this,
                        List.of(hudLayerProperty)
                ),
                new OverlayOptionsPose(
                        this,
                        it->{
                            it.setTickPose(true);
                            it.setAimedRotation(false);
                            it.setPositionAnchor(PoseAnchor.HMD);
                            it.setPositionOffset(
                                    0,-0.1f, -1.2f
                            );
                            it.setRotationAnchor(PoseAnchor.HMD);
                            it.setRotationOffset(
                                    0,0,0
                            );
                            it.setScale(1.0f);
                        }
                ),
                optionsScreenRegion
        );
    }


    // Minimal concrete RenderTarget for region copies
    private static final class RegionRenderTarget extends RenderTarget {
        public RegionRenderTarget(boolean useDepth) {
            // PORT-1.21.11: RenderTarget now labels its GPU textures, so it wants a name.
            super("visor hud region", useDepth);
        }
    }
}