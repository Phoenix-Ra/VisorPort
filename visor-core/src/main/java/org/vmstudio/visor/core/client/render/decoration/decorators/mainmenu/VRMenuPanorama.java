package org.vmstudio.visor.core.client.render.decoration.decorators.mainmenu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.vmstudio.visor.api.client.settings.VRClientSettings;
import org.vmstudio.visor.api.compatibility.mcversion.McVersionUtils;
import com.mojang.blaze3d.opengl.GlStateManager;
import org.lwjgl.opengl.GL11;


public class VRMenuPanorama {
    private static final Identifier cubeFront = McVersionUtils.newResourceLoc(VRClientSettings.getPanoramaFront());
    private static final Identifier cubeBack = McVersionUtils.newResourceLoc(VRClientSettings.getPanoramaBack());
    private static final Identifier cubeRight = McVersionUtils.newResourceLoc(VRClientSettings.getPanoramaRight());
    private static final Identifier cubeLeft = McVersionUtils.newResourceLoc(VRClientSettings.getPanoramaLeft());
    private static final Identifier cubeUp = McVersionUtils.newResourceLoc(VRClientSettings.getPanoramaUp());
    private static final Identifier cubeBelow = McVersionUtils.newResourceLoc(VRClientSettings.getPanoramaBelow());

    public static void render(PoseStack poseStack) {
        BufferBuilder bufferbuilder;

        RenderSystem.setShader(CoreShaders.POSITION_TEX_COLOR);
        GlStateManager._clear(GL11C.GL_COLOR_BUFFER_BIT | GL11C.GL_DEPTH_BUFFER_BIT);
        GlStateManager._depthMask(true);
        GlStateManager._enableBlend();
        GlStateManager._blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        RenderSystem.setShaderColor(1, 1, 1, 1);

        poseStack.pushPose();
        poseStack.translate(-50F, -50F, -50.0F);

        Matrix4f matrix = poseStack.last().pose();

        // Down face
        RenderSystem.setShaderTexture(0, cubeBelow);
        bufferbuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bufferbuilder.addVertex(matrix, 0, 0, 0)
                .setUv(0, 0).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 0, 0, 100)
                .setUv(0, 1).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 100, 0, 100)
                .setUv(1, 1).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 100, 0, 0)
                .setUv(1, 0).setColor(255, 255, 255, 255);
        BufferUploader.drawWithShader(bufferbuilder.buildOrThrow());

        // Up face
        RenderSystem.setShaderTexture(0, cubeUp);
        bufferbuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bufferbuilder.addVertex(matrix, 0, 100, 100)
                .setUv(0, 0).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 0, 100, 0)
                .setUv(0, 1).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 100, 100, 0)
                .setUv(1, 1).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 100, 100, 100)
                .setUv(1, 0).setColor(255, 255, 255, 255);
        BufferUploader.drawWithShader(bufferbuilder.buildOrThrow());

        // Left face
        RenderSystem.setShaderTexture(0, cubeLeft);
        bufferbuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bufferbuilder.addVertex(matrix, 0, 0, 0)
                .setUv(1, 1).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 0, 100, 0)
                .setUv(1, 0).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 0, 100, 100)
                .setUv(0, 0).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 0, 0, 100)
                .setUv(0, 1).setColor(255, 255, 255, 255);
        BufferUploader.drawWithShader(bufferbuilder.buildOrThrow());

        // Right face
        RenderSystem.setShaderTexture(0, cubeRight);
        bufferbuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bufferbuilder.addVertex(matrix, 100, 0, 0)
                .setUv(0, 1).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 100, 0, 100)
                .setUv(1, 1).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 100, 100, 100)
                .setUv(1, 0).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 100, 100, 0)
                .setUv(0, 0).setColor(255, 255, 255, 255);
        BufferUploader.drawWithShader(bufferbuilder.buildOrThrow());

        // Front face
        RenderSystem.setShaderTexture(0, cubeFront);
        bufferbuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bufferbuilder.addVertex(matrix, 0, 0, 0)
                .setUv(0, 1).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 100, 0, 0)
                .setUv(1, 1).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 100, 100, 0)
                .setUv(1, 0).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 0, 100, 0)
                .setUv(0, 0).setColor(255, 255, 255, 255);
        BufferUploader.drawWithShader(bufferbuilder.buildOrThrow());

        // Back face
        RenderSystem.setShaderTexture(0, cubeBack);
        bufferbuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bufferbuilder.addVertex(matrix, 0, 0, 100)
                .setUv(1, 1).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 0, 100, 100)
                .setUv(1, 0).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 100, 100, 100)
                .setUv(0, 0).setColor(255, 255, 255, 255);
        bufferbuilder.addVertex(matrix, 100, 0, 100)
                .setUv(0, 1).setColor(255, 255, 255, 255);
        BufferUploader.drawWithShader(bufferbuilder.buildOrThrow());

        poseStack.popPose();
    }
}
