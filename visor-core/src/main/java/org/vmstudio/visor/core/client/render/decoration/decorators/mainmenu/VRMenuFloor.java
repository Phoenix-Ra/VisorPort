package org.vmstudio.visor.core.client.render.decoration.decorators.mainmenu;

import com.mojang.blaze3d.vertex.*;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.vmstudio.visor.api.client.settings.VRClientSettings;
import org.vmstudio.visor.api.compatibility.mcversion.McVersionUtils;
import org.vmstudio.visor.core.client.utils.ClientUtils;
import org.vmstudio.visor.core.client.render.VisorPipelines;

/**
 * Renders the play-area floor
 */
public final class VRMenuFloor {
    private static final Identifier floorTexture =
            McVersionUtils.newResourceLoc(VRClientSettings.getMainMenuFloor());

    private VRMenuFloor() {
    }

    public static void render(PoseStack poseStack) {
        BufferBuilder bufferbuilder;
        Vector2f area = ClientUtils.getPlayAreaSize();

        for (int i = 0; i < 2; i++) {
            float width = area.x + i * 2;
            float length = area.y + i * 2;

            poseStack.pushPose();

            int r = 128, g = 128, b = 128;

            Matrix4f matrix4f = poseStack.last().pose();
            bufferbuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            poseStack.translate(-width / 2.0F, 0.0F, -length / 2.0F);

            final int repeat = 4;

            bufferbuilder
                    .addVertex(matrix4f, 0, 0.005f * -i, 0)
                    .setUv(0, 0)
                    .setColor(r, g, b, 255)
            ;
            bufferbuilder
                    .addVertex(matrix4f, 0, 0.005f * -i, length)
                    .setUv(0, repeat * length)
                    .setColor(r, g, b, 255)
            ;
            bufferbuilder
                    .addVertex(matrix4f, width, 0.005f * -i, length)
                    .setUv(repeat * width, repeat * length)
                    .setColor(r, g, b, 255)
            ;
            bufferbuilder
                    .addVertex(matrix4f, width, 0.005f * -i, 0)
                    .setUv(repeat * width, 0)
                    .setColor(r, g, b, 255)
            ;

            // The floor tiles by drawing UVs past 1.0, so it needs the repeating sampler;
            // the default clamp would stretch one copy of the texture over the whole area.
            VisorPipelines.positionTexColorRepeat(floorTexture).draw(bufferbuilder.buildOrThrow());

            poseStack.popPose();
        }
    }
}
