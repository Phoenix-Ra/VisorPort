package org.vmstudio.visor.mixin.client.vanillafix.itemmodel;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;

/**
 * PORT-1.21.11: this mixin is now a no-op and should be deleted together with its
 * "client.vanillafix.itemmodel.TextureAtlasSpriteMixin" entry in visor.mixins.json.
 * <p>
 * It used to force TextureAtlasSprite#uvShrinkRatio to 0 on the block atlas so that
 * FaceBakery would stop insetting baked quad UVs by 4/atlasSize toward the sprite
 * centre - the inset ate the outermost texel row of item models, which reads as a
 * transparent outline at the magnification VR looks at held items with.
 * <p>
 * 1.21.11 removed the shrink mechanism outright: uvShrinkRatio and atlasSize are gone
 * from TextureAtlasSprite (there is not a single reference left in the jar), the sprite
 * bakes its atlas padding straight into u0/u1/v0/v1, and FaceBakery#bakeVertex now feeds
 * sprite.getU(u)/getV(v) through unmodified. Vanilla already does what this hook forced,
 * so there is no seam left to retarget - anything injected here would only re-break it.
 * <p>
 * The empty body is deliberate: with "required": true, a dangling @Inject or a @Shadow of
 * the deleted atlasSize() throws at game start, and deleting the class while the config
 * still names it does the same.
 */
@Mixin(TextureAtlasSprite.class)
public abstract class TextureAtlasSpriteMixin {
}
