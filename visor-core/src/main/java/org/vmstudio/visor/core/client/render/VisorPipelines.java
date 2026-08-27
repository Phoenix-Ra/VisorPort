package org.vmstudio.visor.core.client.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.platform.BlendFactor;
import java.util.Optional;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.vmstudio.visor.api.compatibility.mcversion.McVersionUtils;

import java.util.function.Function;

/**
 * The pipelines Visor draws its own geometry with.
 * <p>
 * Up to 1.21.4 every one of these draws was "pick a {@code CoreShaders} program, poke blend and
 * depth state onto {@code GlStateManager}, hand a buffer to {@code BufferUploader}". None of that
 * exists any more: the program, the vertex format, the blend function, the depth test and the
 * cull flag are all baked into an immutable {@link RenderPipeline}, applied when a render pass
 * binds it. The same draw is now "pick the pipeline that already has the state you wanted".
 * <p>
 * These reuse the GLSL vanilla still ships ({@code core/position}, {@code core/position_color},
 * {@code core/position_tex}, {@code core/position_tex_color}) - only the {@code CoreShaders} Java
 * registry was deleted, not the shaders themselves.
 * <p>
 * Pipelines are static data. They compile lazily inside the device on first use and survive
 * resource reloads, so declaring one that is never drawn costs nothing.
 *
 * <h2>Texture-parameterised types</h2>
 * A {@link RenderType} binds its textures by {@link Identifier} up front, so a textured draw needs
 * one type per texture. Those are memoized: callers such as the overlay manager draw twice per eye
 * per frame and must not allocate a new type each time.
 * <p>
 * Anything whose source texture is a {@code RenderTarget} rather than a registered
 * {@code Identifier} cannot use this route at all and has to go through
 * {@code RenderShaderHelper.renderFullscreenQuad} with a manual pass.
 */
public final class VisorPipelines {

    private VisorPipelines() {
        throw new UnsupportedOperationException("This is an utility class and cannot be instantiated");
    }

    /** {@code _blendFuncSeparate(SRC_ALPHA, ONE, SRC_ALPHA, ONE)} - the additive glow Visor used. */
    private static final BlendFunction ADDITIVE_GLOW = BlendFunction.LIGHTNING;

    /**
     * {@code _blendFuncSeparate(ONE_MINUS_DST_COLOR, ONE_MINUS_SRC_COLOR, ONE, ZERO)} - the
     * inverting blend the crosshair uses. Vanilla's {@code BlendFunction.INVERT} is a different
     * function and is not a substitute.
     */
    private static final BlendFunction CROSSHAIR_INVERT = new BlendFunction(
            BlendFactor.ONE_MINUS_DST_COLOR, BlendFactor.ONE_MINUS_SRC_COLOR,
            BlendFactor.ONE, BlendFactor.ZERO);


    /*
     * PORT-1.21.11: every pipeline built from a bare RenderPipeline.builder() has to declare
     * DynamicTransforms itself.
     *
     * Vanilla declares it exactly once, on a base Snippet that all of RenderPipelines inherits,
     * which is why no vanilla pipeline repeats it - and why Visor, which builds from scratch,
     * inherits nothing. GlProgram.setupUniforms auto-binds only {Projection, Lighting, Fog,
     * Globals}; any other block the linked program declares and the pipeline does not gets
     * "Found unknown and unsupported uniform {} in {}" and then NO binding, so the shader reads
     * ModelViewMat and ColorModulator out of whatever the previous draw left at that binding
     * point. That is a single WARN line at pipeline-compile time and a black screen at runtime.
     * port-notes/validate-pipelines.py checks every pipeline here against its shaders.
     */

    // ==================== POSITION ====================

    /**
     * Untextured triangles, no blend, no depth. Only the hidden-area stencil mask draws with this,
     * and that is currently disabled - see {@code VREffectsHelper.STENCIL_SUPPORTED}.
     */
    public static final RenderPipeline POSITION_TRIANGLES = RenderPipeline.builder()
            .withLocation(visor("pipeline/position_triangles"))
            .withVertexShader("core/position")
            .withFragmentShader("core/position")
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            // core/position is the one core shader here that also imports fog.glsl.
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withVertexBinding(0, DefaultVertexFormat.POSITION)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withCull(false)
            .build();


    // ==================== POSITION_COLOR ====================

    /** Depth-tested and depth-writing: world geometry that should occlude and be occluded. */
    public static final RenderPipeline POSITION_COLOR = RenderPipeline.builder()
            .withLocation(visor("pipeline/position_color"))
            .withVertexShader("core/position_color")
            .withFragmentShader("core/position_color")
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
            .withCull(true)
            .build();

    /** Overlay geometry: draws over everything, writes no depth, both faces. */
    public static final RenderPipeline POSITION_COLOR_NO_DEPTH = RenderPipeline.builder()
            .withLocation(visor("pipeline/position_color_no_depth"))
            .withVertexShader("core/position_color")
            .withFragmentShader("core/position_color")
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withCull(false)
            .build();

    /** Additive overlay geometry - the star field, the shooting star. */
    public static final RenderPipeline POSITION_COLOR_ADDITIVE_NO_DEPTH = RenderPipeline.builder()
            .withLocation(visor("pipeline/position_color_additive_no_depth"))
            .withVertexShader("core/position_color")
            .withFragmentShader("core/position_color")
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withColorTargetState(new ColorTargetState(ADDITIVE_GLOW))
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withCull(false)
            .build();


    // ==================== POSITION_COLOR_NORMAL ====================
    // The normal is unused by core/position_color - Visor has always fed POSITION_COLOR_NORMAL
    // geometry to that program. GlProgram.link binds attribute locations by name and ignores names
    // the shader does not declare, so carrying the extra attribute costs nothing but its bytes.

    /** Depth-tested, no depth write: the teleport arc, whose segments must not occlude each other. */
    public static final RenderPipeline POSITION_COLOR_NORMAL = RenderPipeline.builder()
            .withLocation(visor("pipeline/position_color_normal"))
            .withVertexShader("core/position_color")
            .withFragmentShader("core/position_color")
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR_NORMAL)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
            .withCull(true)
            .build();

    /** Overlay boxes - the hand cursor, the tracker debug boxes, the fake shadow. */
    public static final RenderPipeline POSITION_COLOR_NORMAL_NO_DEPTH = RenderPipeline.builder()
            .withLocation(visor("pipeline/position_color_normal_no_depth"))
            .withVertexShader("core/position_color")
            .withFragmentShader("core/position_color")
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR_NORMAL)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withCull(false)
            .build();


    // ==================== POSITION_TEX_COLOR ====================

    public static final RenderPipeline POSITION_TEX_COLOR = RenderPipeline.builder()
            .withLocation(visor("pipeline/position_tex_color"))
            .withVertexShader("core/position_tex_color")
            .withFragmentShader("core/position_tex_color")
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
            .withCull(true)
            .build();

    /**
     * Depth-writing but two-sided. The panorama is a cube seen from the inside, so back-face
     * culling can only ever remove faces that should be visible; the faces tile the view without
     * overlapping, which makes culling worth nothing here anyway.
     */
    public static final RenderPipeline POSITION_TEX_COLOR_NO_CULL = RenderPipeline.builder()
            .withLocation(visor("pipeline/position_tex_color_no_cull"))
            .withVertexShader("core/position_tex_color")
            .withFragmentShader("core/position_tex_color")
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
            .withCull(false)
            .build();

    public static final RenderPipeline POSITION_TEX_COLOR_NO_DEPTH = RenderPipeline.builder()
            .withLocation(visor("pipeline/position_tex_color_no_depth"))
            .withVertexShader("core/position_tex_color")
            .withFragmentShader("core/position_tex_color")
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withCull(false)
            .build();

    public static final RenderPipeline POSITION_TEX_COLOR_ADDITIVE_NO_DEPTH = RenderPipeline.builder()
            .withLocation(visor("pipeline/position_tex_color_additive_no_depth"))
            .withVertexShader("core/position_tex_color")
            .withFragmentShader("core/position_tex_color")
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withColorTargetState(new ColorTargetState(ADDITIVE_GLOW))
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withCull(false)
            .build();

    /** The crosshair, which inverts what it is drawn over so it stays visible on any backdrop. */
    public static final RenderPipeline POSITION_TEX_COLOR_INVERT = RenderPipeline.builder()
            .withLocation(visor("pipeline/position_tex_color_invert"))
            .withVertexShader("core/position_tex_color")
            .withFragmentShader("core/position_tex_color")
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withColorTargetState(new ColorTargetState(CROSSHAIR_INVERT))
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withCull(false)
            .build();

    /**
     * The crosshair's depth carve - the second half of what 1.21.4 expressed in one draw.
     * <p>
     * 1.21.4 drew the crosshair with {@code depthFunc(GL_ALWAYS)} AND {@code depthMask(true)}:
     * always visible over what was already drawn, and its depth stamped into the buffer so the
     * entities rendered after the AFTER_SOLID stage could only cover it by being genuinely
     * closer. {@code NO_DEPTH_TEST} maps to {@code glDisable(GL_DEPTH_TEST)}, under which GL
     * never writes depth, so one pipeline cannot do both halves any more. The colour half stays
     * on {@link #POSITION_TEX_COLOR_INVERT}; this colour-masked pass restores the write half.
     * Depth-tested rather than the old unconditional write: where the crosshair shows through a
     * wall, it no longer pushes the wall's depth back, it just declines to carve there.
     * <p>
     * PORT-26.2: every depth test in this class is GREATER_THAN_OR_EQUAL now - 26.2 renders with
     * a reversed depth buffer (cleared to 0.0, near mapping to 1). See {@code ProjectionHelper}.
     */
    public static final RenderPipeline CROSSHAIR_DEPTH_CARVE = RenderPipeline.builder()
            .withLocation(visor("pipeline/crosshair_depth_carve"))
            .withVertexShader("core/position_tex_color")
            .withFragmentShader("core/position_tex_color")
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            // PORT-26.1: depth-only carve - the old withoutBlend() + withColorWrite(false,false)
            // pair is now one ColorTargetState with an explicit write mask.
            // PORT-26.2: ColorTargetState carries the target's GpuFormat now. RGBA8_UNORM is what
            // ColorTargetState.DEFAULT itself is built with.
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM,
                    ColorTargetState.WRITE_NONE))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
            .withCull(false)
            .build();


    // ==================== POSITION_TEX ====================

    public static final RenderPipeline POSITION_TEX_NO_DEPTH = RenderPipeline.builder()
            .withLocation(visor("pipeline/position_tex_no_depth"))
            .withVertexShader("core/position_tex")
            .withFragmentShader("core/position_tex")
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withCull(false)
            .build();


    // ==================== VR OVERLAY QUADS ====================
    // The GUI overlay panels. Their texture is the overlay's own RenderTarget, so these never go
    // through a RenderType - RenderGuiHelper draws them with RenderShaderHelper.drawMesh and binds
    // the colour attachment itself.

    /**
     * {@code _blendFuncSeparate(SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE_MINUS_DST_ALPHA, ONE)}.
     * <p>
     * Differs from {@link BlendFunction#TRANSLUCENT} only in the alpha channel, and that channel is
     * load-bearing: the mixed-reality compositor reads the eye target's alpha.
     */
    private static final BlendFunction OVERLAY_IN_WORLD = new BlendFunction(
            BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA,
            BlendFactor.ONE_MINUS_DST_ALPHA, BlendFactor.ONE);

    /** Unlit overlay panel, drawn in a world scene. */
    public static final RenderPipeline OVERLAY_QUAD_WORLD = overlayQuad("world", OVERLAY_IN_WORLD, true);
    public static final RenderPipeline OVERLAY_QUAD_WORLD_NO_DEPTH = overlayQuad("world_no_depth", OVERLAY_IN_WORLD, false);

    /** Unlit overlay panel, drawn outside a world scene (main menu, loading). */
    public static final RenderPipeline OVERLAY_QUAD = overlayQuad("plain", BlendFunction.TRANSLUCENT, true);
    public static final RenderPipeline OVERLAY_QUAD_NO_DEPTH = overlayQuad("plain_no_depth", BlendFunction.TRANSLUCENT, false);

    /** Lit overlay panel - same four combinations, sampling the lightmap. */
    public static final RenderPipeline OVERLAY_QUAD_LIT_WORLD = litOverlayQuad("world", OVERLAY_IN_WORLD, true);
    public static final RenderPipeline OVERLAY_QUAD_LIT_WORLD_NO_DEPTH = litOverlayQuad("world_no_depth", OVERLAY_IN_WORLD, false);
    public static final RenderPipeline OVERLAY_QUAD_LIT = litOverlayQuad("plain", BlendFunction.TRANSLUCENT, true);
    public static final RenderPipeline OVERLAY_QUAD_LIT_NO_DEPTH = litOverlayQuad("plain_no_depth", BlendFunction.TRANSLUCENT, false);

    public static RenderPipeline overlayQuadFor(boolean inWorld, boolean lit, boolean depthAlways) {
        if (lit) {
            if (inWorld) return depthAlways ? OVERLAY_QUAD_LIT_WORLD_NO_DEPTH : OVERLAY_QUAD_LIT_WORLD;
            return depthAlways ? OVERLAY_QUAD_LIT_NO_DEPTH : OVERLAY_QUAD_LIT;
        }
        if (inWorld) return depthAlways ? OVERLAY_QUAD_WORLD_NO_DEPTH : OVERLAY_QUAD_WORLD;
        return depthAlways ? OVERLAY_QUAD_NO_DEPTH : OVERLAY_QUAD;
    }

    private static RenderPipeline overlayQuad(String suffix, BlendFunction blend, boolean depthTest) {
        return RenderPipeline.builder()
                .withLocation(visor("pipeline/overlay_quad_" + suffix))
                .withVertexShader("core/position_tex")
                .withFragmentShader("core/position_tex")
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withColorTargetState(new ColorTargetState(blend))
                .withDepthStencilState(new DepthStencilState(depthTest
                        ? CompareOp.GREATER_THAN_OR_EQUAL
                        : CompareOp.ALWAYS_PASS, depthTest))
                .withCull(false)
                .build();
    }

    /**
     * The lit panel, built from vanilla's entity snippet.
     * <p>
     * {@code NO_CARDINAL_LIGHTING} replaces what 1.21.4 did by hand: it pointed both shader light
     * directions at {@code (0,0,1)}, the same way the quad's own normal points, which drove
     * {@code minecraft_mix_light} to a flat {@code 1.0}. The define produces that same flat result
     * declaratively - {@code RenderSystem.setShaderLights} now takes a whole UBO slice and cannot
     * be poked per draw any more. Vanilla's {@code ENTITY_CUTOUT_NO_CULL} additionally defines
     * {@code PER_FACE_LIGHTING}, which would win over {@code NO_CARDINAL_LIGHTING}, so this
     * deliberately does not inherit it: a GUI panel should read the same from either side.
     */
    private static RenderPipeline litOverlayQuad(String suffix, BlendFunction blend, boolean depthTest) {
        return RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
                .withLocation(visor("pipeline/overlay_quad_lit_" + suffix))
                .withShaderDefine("ALPHA_CUTOUT", 0.1f)
                .withShaderDefine("NO_CARDINAL_LIGHTING")
                .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
                .withColorTargetState(new ColorTargetState(blend))
                .withDepthStencilState(new DepthStencilState(depthTest
                        ? CompareOp.GREATER_THAN_OR_EQUAL
                        : CompareOp.ALWAYS_PASS, depthTest))
                .withCull(false)
                .build();
    }


    // ==================== BLIT ====================

    /**
     * A framebuffer copy as a textured quad: destination rectangle as NDC positions, source
     * rectangle as UVs, every channel copied verbatim, nothing blended, nothing discarded.
     * <p>
     * This is the fallback half of {@code RenderShaderHelper.blit} - on the GL backend the copy
     * is a real {@code glBlitFramebuffer}, and this pipeline only serves textures that are not
     * GL textures. It is built on Visor's own {@code visor:core/vr_blit} rather than vanilla's
     * {@code core/position_tex} on purpose: that fragment shader discards every texel whose
     * alpha is exactly 0, and a copy must not care what the alpha channel holds (the XR
     * swapchain images, the level's fog-coloured clear and the GUI target's background all sit
     * at alpha 0). {@code vr_blit} also declares no uniform block at all - the quad is already
     * in NDC - so there is nothing to bind and nothing to write before the pass opens.
     */
    public static final RenderPipeline BLIT = RenderPipeline.builder()
            .withLocation(visor("pipeline/blit"))
            .withVertexShader(visor("core/vr_blit"))
            .withFragmentShader(visor("core/vr_blit"))
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withCull(false)
            .build();


    // ==================== RENDER TYPES ====================
    // Untextured types are single constants; textured ones are memoized per texture.

    public static final RenderType POSITION_TRIANGLES_TYPE =
            type("visor_position_triangles", POSITION_TRIANGLES);

    public static final RenderType POSITION_COLOR_TYPE =
            type("visor_position_color", POSITION_COLOR);

    public static final RenderType POSITION_COLOR_NO_DEPTH_TYPE =
            type("visor_position_color_no_depth", POSITION_COLOR_NO_DEPTH);

    public static final RenderType POSITION_COLOR_ADDITIVE_NO_DEPTH_TYPE =
            type("visor_position_color_additive_no_depth", POSITION_COLOR_ADDITIVE_NO_DEPTH);

    public static final RenderType POSITION_COLOR_NORMAL_TYPE =
            type("visor_position_color_normal", POSITION_COLOR_NORMAL);

    public static final RenderType POSITION_COLOR_NORMAL_NO_DEPTH_TYPE =
            type("visor_position_color_normal_no_depth", POSITION_COLOR_NORMAL_NO_DEPTH);

    private static final Function<Identifier, RenderType> POSITION_TEX_COLOR_TYPE = Util.memoize(
            texture -> texturedType("visor_position_tex_color", POSITION_TEX_COLOR, texture));

    private static final Function<Identifier, RenderType> POSITION_TEX_COLOR_NO_CULL_TYPE = Util.memoize(
            texture -> texturedType("visor_position_tex_color_no_cull", POSITION_TEX_COLOR_NO_CULL, texture));

    private static final Function<Identifier, RenderType> POSITION_TEX_COLOR_NO_DEPTH_TYPE = Util.memoize(
            texture -> texturedType("visor_position_tex_color_no_depth",
                    POSITION_TEX_COLOR_NO_DEPTH, texture));

    private static final Function<Identifier, RenderType> POSITION_TEX_COLOR_ADDITIVE_NO_DEPTH_TYPE = Util.memoize(
            texture -> texturedType("visor_position_tex_color_additive_no_depth",
                    POSITION_TEX_COLOR_ADDITIVE_NO_DEPTH, texture));

    private static final Function<Identifier, RenderType> POSITION_TEX_COLOR_INVERT_TYPE = Util.memoize(
            texture -> texturedType("visor_position_tex_color_invert",
                    POSITION_TEX_COLOR_INVERT, texture));

    private static final Function<Identifier, RenderType> CROSSHAIR_DEPTH_CARVE_TYPE = Util.memoize(
            texture -> texturedType("visor_crosshair_depth_carve",
                    CROSSHAIR_DEPTH_CARVE, texture));

    private static final Function<Identifier, RenderType> POSITION_TEX_NO_DEPTH_TYPE = Util.memoize(
            texture -> texturedType("visor_position_tex_no_depth", POSITION_TEX_NO_DEPTH, texture));

    /** Tiling variant: the play-area floor draws UVs past 1.0 and needs a repeating sampler. */
    private static final Function<Identifier, RenderType> POSITION_TEX_COLOR_REPEAT_TYPE = Util.memoize(
            texture -> RenderType.create("visor_position_tex_color_repeat",
                    RenderSetup.builder(POSITION_TEX_COLOR)
                            .withTexture("Sampler0", texture,
                                    () -> RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST))
                            .createRenderSetup()));


    // ==================== ACCESSORS ====================

    public static RenderType positionTexColor(Identifier texture) {
        return POSITION_TEX_COLOR_TYPE.apply(texture);
    }

    public static RenderType positionTexColorNoCull(Identifier texture) {
        return POSITION_TEX_COLOR_NO_CULL_TYPE.apply(texture);
    }

    public static RenderType positionTexColorNoDepth(Identifier texture) {
        return POSITION_TEX_COLOR_NO_DEPTH_TYPE.apply(texture);
    }

    public static RenderType positionTexColorAdditiveNoDepth(Identifier texture) {
        return POSITION_TEX_COLOR_ADDITIVE_NO_DEPTH_TYPE.apply(texture);
    }

    public static RenderType positionTexColorInvert(Identifier texture) {
        return POSITION_TEX_COLOR_INVERT_TYPE.apply(texture);
    }

    public static RenderType crosshairDepthCarve(Identifier texture) {
        return CROSSHAIR_DEPTH_CARVE_TYPE.apply(texture);
    }

    public static RenderType positionTexNoDepth(Identifier texture) {
        return POSITION_TEX_NO_DEPTH_TYPE.apply(texture);
    }

    public static RenderType positionTexColorRepeat(Identifier texture) {
        return POSITION_TEX_COLOR_REPEAT_TYPE.apply(texture);
    }

    /** Picks the additive or the translucent glow variant, the way the old code switched blend func. */
    public static RenderType positionTexColorGlow(Identifier texture, boolean additive) {
        return additive
                ? positionTexColorAdditiveNoDepth(texture)
                : positionTexColorNoDepth(texture);
    }


    // ==================== INTERNALS ====================

    private static Identifier visor(String path) {
        return McVersionUtils.newResourceLoc("visor", path);
    }

    private static RenderType type(String name, RenderPipeline pipeline) {
        return RenderType.create(name, RenderSetup.builder(pipeline).createRenderSetup());
    }

    private static RenderType texturedType(String name, RenderPipeline pipeline, Identifier texture) {
        return RenderType.create(name,
                RenderSetup.builder(pipeline)
                        .withTexture("Sampler0", texture)
                        .createRenderSetup());
    }
}
