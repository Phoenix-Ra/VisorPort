package org.vmstudio.visor.core.client.render.decoration.effects;

import com.mojang.blaze3d.vertex.PoseStack;
import org.vmstudio.visor.api.client.render.VRRenderPass;
import org.vmstudio.visor.api.client.render.decoration.VRDecorator;
import org.vmstudio.visor.api.client.render.decoration.annotations.RegisterVRGameEffect;
import org.vmstudio.visor.api.client.render.decoration.effects.VRGameEffect;
import org.vmstudio.visor.api.common.addon.VisorAddon;
import org.jetbrains.annotations.NotNull;
import org.vmstudio.visor.extensions.client.render.GameRendererExtension;

import static org.vmstudio.visor.core.client.VisorClientImpl.MC;


@RegisterVRGameEffect
public class GameEffectVanilla extends VRGameEffect {
    public static final String ID = "vanilla";
    public GameEffectVanilla(@NotNull VisorAddon owner) {
        super(owner);
    }

    /**
     * PORT-1.21.11: the animation moved off {@code GameRenderer} onto its private
     * {@code ScreenEffectRenderer}, takes a {@link PoseStack} rather than a {@code GuiGraphics},
     * and submits render nodes instead of drawing. Both members are reached through
     * {@code visor.accesswidener}; the nodes are flushed straight away so the effect still lands
     * inside this decorator's pass rather than leaking into whatever draws next.
     */
    @Override
    public void render(@NotNull VRRenderPass renderPass,
                       @NotNull PoseStack poseStack,
                       float partialTicks) {
        MC.gameRenderer.screenEffectRenderer.renderItemActivationAnimation(
                poseStack,
                partialTicks,
                MC.gameRenderer.getSubmitNodeStorage()
        );
        MC.gameRenderer.getFeatureRenderDispatcher().renderAllFeatures();
    }

    @Override
    public boolean isVisible(@NotNull VRDecorator currentDecorator) {
        return true;
    }

    @Override
    public @NotNull String getId() {
        return ID;
    }

}
