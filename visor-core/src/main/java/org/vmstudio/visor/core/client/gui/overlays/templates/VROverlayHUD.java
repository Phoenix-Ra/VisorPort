package org.vmstudio.visor.core.client.gui.overlays.templates;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.vmstudio.visor.api.VisorAPI;
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

        copyRegion(src, srcX0, srcY0, srcX1, srcY1);

        // Use the cropped texture as the overlay render target
        this.renderTarget = regionTarget;
    }

    /**
     * Copies the region rectangle out of {@code src} into {@link #regionTarget}.
     * <p>
     * PORT-1.21.11: a {@link RenderTarget} no longer exposes a framebuffer id, so the manual
     * read/draw FBO bind plus {@code glBlitFramebuffer} became {@link RenderShaderHelper#blit},
     * which keeps the blit's semantics: every texel of the rectangle is copied, alpha included.
     * That alpha is load-bearing - it is what the overlay panel blends with - and the region's
     * uncovered parts come out transparent because the GUI target's background is transparent,
     * not because anything clears them first. {@code src} and the region are the same size, so
     * NEAREST: there is nothing to interpolate.
     */
    private void copyRegion(RenderTarget src, int srcX0, int srcY0, int srcX1, int srcY1) {
        RenderShaderHelper.blit(
                () -> "visor hud region crop",
                src, srcX0, srcY0, srcX1, srcY1,
                regionTarget, 0, 0, regionTarget.width, regionTarget.height,
                FilterMode.NEAREST);
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