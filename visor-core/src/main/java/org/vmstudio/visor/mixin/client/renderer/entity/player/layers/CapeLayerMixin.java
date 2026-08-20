package org.vmstudio.visor.mixin.client.renderer.entity.player.layers;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.vmstudio.visor.api.common.utils.VRMathUtils;
import org.vmstudio.visor.core.client.utils.ModelUtils;
import org.vmstudio.visor.extensions.client.entity.EntityRenderStateExtension;


@Mixin(CapeLayer.class)
public abstract class CapeLayerMixin extends RenderLayer<AvatarRenderState, PlayerModel> {

    @Unique
    private final Vector3f visor$tempV = new Vector3f();

    @Unique
    private final Matrix3f visor$bodyRot = new Matrix3f();

    public CapeLayerMixin(RenderLayerParent<AvatarRenderState, PlayerModel> renderer) {
        super(renderer);
    }

    // A commented-out DEBUG CAPE hook used to live here: it wrapped PlayerSkin#capeTexture() so a
    // capeless player still got a white cape to eyeball the VR transform against. Dropped rather
    // than ported - 1.21.11 replaced capeTexture() with cape(), returning a ClientAsset.Texture
    // instead of an Identifier, and submit() now returns early when that is null, so reviving it
    // means synthesising a Texture rather than swapping a path.

    // ordinal 1 is the HUMANOID check that applies the vanilla with-armor cape offset; returning
    // false there skips it, and the VR offset/rotation is applied instead.
    // PORT-1.21.11: RenderLayer#render became RenderLayer#submit - the layer records geometry into
    // a SubmitNodeCollector instead of drawing into a MultiBufferSource. The hasLayer branch this
    // hooks (and its ordinal) is unchanged, and the pose is still read at submit time, so the
    // transform written here reaches the cape exactly as before.
    @ModifyExpressionValue(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/layers/CapeLayer;hasLayer(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;)Z", ordinal = 1))
    private boolean visor$modifyTransform(
        boolean hasArmor, @Local(argsOnly = true) AvatarRenderState renderState,
        @Local(argsOnly = true) PoseStack poseStack)
    {
        // entity-derived VR data is resolved during extractRenderState and parked on the state
        var vrPlayer = ((EntityRenderStateExtension) renderState).visor$getVRPlayer();
        if (vrPlayer == null) {
            return hasArmor;
        }

        this.visor$bodyRot.rotationZYX(getParentModel().body.zRot, -getParentModel().body.yRot,
            -getParentModel().body.xRot);

        // attach the cape to the body
        this.visor$bodyRot.transform(VRMathUtils.UP_VECTOR, this.visor$tempV);
        float xRotation = (float) Math.atan2(this.visor$tempV.y, this.visor$tempV.z) - Mth.HALF_PI;

        // make sure it doesn't go below -PI
        xRotation = xRotation < -Mth.PI ? xRotation + Mth.TWO_PI : xRotation;

        this.visor$bodyRot.transform(VRMathUtils.RIGHT_VECTOR, this.visor$tempV);
        float yRotation = (float) -Math.atan2(this.visor$tempV.x, this.visor$tempV.y) + Mth.HALF_PI;

        // transform offset to be body relative
        this.visor$tempV.set(0F, 0F, 2F - 0.5F * (getParentModel().body.xRot / Mth.HALF_PI));
        if (hasArmor) {
            // vanilla cape offset with armor
            this.visor$tempV.add(0F, -0.85F, 1.1F);
        }
        this.visor$tempV.rotateX(xRotation);
        this.visor$tempV.rotateZ(yRotation);

        // +24 because it should be the offset to the default position, which is at 24
        this.visor$tempV.add(getParentModel().body.x, getParentModel().body.y + 24F, getParentModel().body.z);

        // no yaw, since we  need the vector to be player rotated anyway
        ModelUtils.modelToWorld(vrPlayer.getMcPlayer(), this.visor$tempV, vrPlayer, 0F, false, false,
            this.visor$tempV);
        poseStack.translate(this.visor$tempV.x, -this.visor$tempV.y, -this.visor$tempV.z);

        // rotate with body
        // max of 0 to keep it down when the body bends backwards
        float min = (renderState.isFallFlying ? 1F : renderState.swimAmount) * -Mth.HALF_PI;
        float flap = renderState.capeFlap + Mth.RAD_TO_DEG * Math.max(min, xRotation);

        // limit the up rotation when walking forward, depending on body rotation.
        // 1.21.4 dropped the vanilla "+25 while crouching" flap term (see
        // AvatarRenderer#extractRenderState), so there is nothing left to cancel here.
        float lean = xRotation / Mth.HALF_PI;
        if (lean >= 0) {
            lean = renderState.capeLean * (1F - Mth.clamp(lean, 0F, 1F));
        } else {
            lean = 0F;
        }

        // Manual rotation - PlayerCapeModelMixin suppresses PlayerCapeModel's own for VR players.
        // The cape part's own pose still contributes the 180 that vanilla folds into
        // Y(180 - capeLean2/2), so only the -capeLean2/2 half belongs here.
        poseStack.mulPose(new Quaternionf()
            .rotateX((6.0F + lean / 2.0F + flap) * Mth.DEG_TO_RAD)
            .rotateZ(renderState.capeLean2 / 2.0F * Mth.DEG_TO_RAD)
            .rotateY(-renderState.capeLean2 / 2.0F * Mth.DEG_TO_RAD + yRotation));

        return false;
    }
}
