package org.vmstudio.visor.core.client.render.decoration.decorators.mainmenu;

import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import org.vmstudio.visor.core.client.render.VisorPipelines;
import org.vmstudio.visor.core.client.render.helpers.RenderShaderHelper;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.vmstudio.visor.api.client.settings.VRClientSettings;
import org.vmstudio.visor.api.compatibility.mcversion.McVersionUtils;


public class VRMenuPanorama {
    private static final Identifier cubeFront = McVersionUtils.newResourceLoc(VRClientSettings.getPanoramaFront());
    private static final Identifier cubeBack = McVersionUtils.newResourceLoc(VRClientSettings.getPanoramaBack());
    private static final Identifier cubeRight = McVersionUtils.newResourceLoc(VRClientSettings.getPanoramaRight());
    private static final Identifier cubeLeft = McVersionUtils.newResourceLoc(VRClientSettings.getPanoramaLeft());
    private static final Identifier cubeUp = McVersionUtils.newResourceLoc(VRClientSettings.getPanoramaUp());
    private static final Identifier cubeBelow = McVersionUtils.newResourceLoc(VRClientSettings.getPanoramaBelow());

    public static void render(PoseStack poseStack) {
        BufferBuilder bufferbuilder;

        RenderShaderHelper.clearColorAndDepth(Minecraft.getInstance().getMainRenderTarget(), 0, 1.0);

        poseStack.pushPose();
        poseStack.translate(-50F, -50F, -50.0F);

        Matrix4f matrix = poseStack.last().pose();

        // Down face
        bufferbuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bufferbuilder.addVertex(matrix, 0, 0, 0)
                .setUv(0, 0).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 0, 0, 100)
                .setUv(0, 1).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 100, 0, 100)
                .setUv(1, 1).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 100, 0, 0)
                .setUv(1, 0).setColor(255, 255, 255, 255);
        VisorPipelines.positionTexColorNoCull(cubeBelow).draw(bufferbuilder.buildOrThrow());

        // Up face
        bufferbuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bufferbuilder.addVertex(matrix, 0, 100, 100)
                .setUv(0, 0).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 0, 100, 0)
                .setUv(0, 1).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 100, 100, 0)
                .setUv(1, 1).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 100, 100, 100)
                .setUv(1, 0).setColor(255, 255, 255, 255);
        VisorPipelines.positionTexColorNoCull(cubeUp).draw(bufferbuilder.buildOrThrow());

        // Left face
        bufferbuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bufferbuilder.addVertex(matrix, 0, 0, 0)
                .setUv(1, 1).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 0, 100, 0)
                .setUv(1, 0).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 0, 100, 100)
                .setUv(0, 0).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 0, 0, 100)
                .setUv(0, 1).setColor(255, 255, 255, 255);
        VisorPipelines.positionTexColorNoCull(cubeLeft).draw(bufferbuilder.buildOrThrow());

        // Right face
        bufferbuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bufferbuilder.addVertex(matrix, 100, 0, 0)
                .setUv(0, 1).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 100, 0, 100)
                .setUv(1, 1).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 100, 100, 100)
                .setUv(1, 0).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 100, 100, 0)
                .setUv(0, 0).setColor(255, 255, 255, 255);
        VisorPipelines.positionTexColorNoCull(cubeRight).draw(bufferbuilder.buildOrThrow());

        // Front face
        bufferbuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bufferbuilder.addVertex(matrix, 0, 0, 0)
                .setUv(0, 1).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 100, 0, 0)
                .setUv(1, 1).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 100, 100, 0)
                .setUv(1, 0).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 0, 100, 0)
                .setUv(0, 0).setColor(255, 255, 255, 255);
        VisorPipelines.positionTexColorNoCull(cubeFront).draw(bufferbuilder.buildOrThrow());

        // Back face
        bufferbuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bufferbuilder.addVertex(matrix, 0, 0, 100)
                .setUv(1, 1).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 0, 100, 100)
                .setUv(1, 0).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 100, 100, 100)
                .setUv(0, 0).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 100, 0, 100)
                .setUv(0, 1).setColor(255, 255, 255, 255);
        VisorPipelines.positionTexColorNoCull(cubeBack).draw(bufferbuilder.buildOrThrow());

        poseStack.popPose();
    }
}
