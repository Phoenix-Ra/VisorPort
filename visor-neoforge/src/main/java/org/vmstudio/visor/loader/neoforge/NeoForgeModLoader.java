package org.vmstudio.visor.loader.neoforge;


import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.ClientHooks;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.model.MaterialSet;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.ModFileScanData;
import org.jetbrains.annotations.NotNull;
import org.vmstudio.visor.api.ModLoader;
import org.vmstudio.visor.api.VisorAPI;
import org.vmstudio.visor.api.client.render.RenderPipelineCallback;
import org.vmstudio.visor.api.client.render.RenderPipelineStage;
import org.vmstudio.visor.api.common.VRException;
import org.vmstudio.visor.api.common.network.VisorChannel;
import org.vmstudio.visor.api.common.network.VisorPayload;
import org.vmstudio.visor.api.common.network.VisorPayloadToClient;
import org.vmstudio.visor.api.common.network.VisorPayloadToServer;
import org.vmstudio.visor.loader.neoforge.network.ClientTunnelSupport;
import org.vmstudio.visor.loader.neoforge.network.VisorRawPayload;

import java.io.File;
import java.lang.annotation.Annotation;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;


public class NeoForgeModLoader implements ModLoader {


    private static final String NETWORK_VERSION = "1";

    private final File configFolder = FMLPaths.CONFIGDIR.get().toFile();

    private final Map<RenderPipelineStage, List<RenderPipelineCallback>> pipelineCallbacks
            = new EnumMap<>(RenderPipelineStage.class);

    private final Map<Identifier, VisorChannel> networkChannels = new HashMap<>();

    private boolean levelStageListenerRegistered = false;


    @Override
    public File getConfigFolder() {
        return configFolder;
    }

    @Override
    public boolean isModLoaded(@NotNull String id) {
        return FMLLoader.getCurrent().getLoadingModList().getModFileById(id) != null;
    }

    @Override
    public @NotNull String getModVersion(@NotNull String id) {
        if (isModLoaded(VisorAPI.MOD_ID)) {
            return FMLLoader.getCurrent().getLoadingModList()
                    .getModFileById(id).versionString();
        }
        return "no version";
    }

    @Override
    public boolean isDedicatedServer() {
        return // PORT-1.21.11: FMLEnvironment.dist became getDist()
                FMLEnvironment.getDist() == Dist.DEDICATED_SERVER;
    }


    @Override
    public void addToRenderPipeline(@NotNull RenderPipelineStage stage,
                                    @NotNull RenderPipelineCallback callback) {
        pipelineCallbacks
                .computeIfAbsent(stage, k -> new CopyOnWriteArrayList<>())
                .add(callback);

        if (!levelStageListenerRegistered) {
            // PORT-1.21.11: RenderLevelStageEvent.Stage is gone. NeoForge 21.11 turned the one
            // event + Stage enum into an abstract event with a concrete subclass per stage, so
            // the dispatch that used to be a switch on getStage() is now three registrations.
            // AFTER_CUTOUT_BLOCKS became AfterOpaqueBlocks - the SOLID and CUTOUT chunk layers
            // are drawn as one "opaque" group now, which is the same point in the frame.
            NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.AfterOpaqueBlocks.class,
                    e -> onRenderLevelStage(RenderPipelineStage.AFTER_SOLID));
            NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.AfterTranslucentBlocks.class,
                    e -> onRenderLevelStage(RenderPipelineStage.AFTER_TRANSLUCENT));
            NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.AfterLevel.class,
                    e -> onRenderLevelStage(RenderPipelineStage.AFTER_WORLD));
            levelStageListenerRegistered = true;
        }
    }


    /**
     * 1.21.4: NeoForge dropped {@code RenderTarget#enableStencil()} - stencil is now a final
     * flag taken by the {@code RenderTarget(useDepth, useStencil)} constructor, which
     * visor-core cannot call because it compiles against unpatched vanilla. Report no loader
     * support so visor-core falls back to its own RenderTargetMixin, same as on Fabric.
     */
    @Override
    public boolean enableRenderTargetStencil(@NotNull RenderTarget renderTarget) {
        return false;
    }


    @Override
    public double getItemEntityReach(double baseRange, ItemStack itemStack, EquipmentSlot slot) {
        List<AttributeModifier> attributes = new ArrayList<>();
        itemStack.forEachModifier(slot, (holder, modifier) -> {
            if (holder == Attributes.ENTITY_INTERACTION_RANGE) {
                attributes.add(modifier);
            }
        });
        for (AttributeModifier entry : attributes) {
            if (entry.operation() == AttributeModifier.Operation.ADD_VALUE) {
                baseRange += entry.amount();
            }
        }
        double totalRange = baseRange;
        for (AttributeModifier entry : attributes) {
            if (entry.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_BASE) {
                totalRange += baseRange * entry.amount();
            }
        }
        for (AttributeModifier entry : attributes) {
            if (entry.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) {
                totalRange *= 1.0 + entry.amount();
            }
        }
        return totalRange;
    }

    @Override
    public @NotNull List<Class<?>> getClassesAnnotated(@NotNull Class<? extends Annotation> annotation,
                                                       @NotNull String modId,
                                                       @NotNull String packagePath) {
        List<Class<?>> result = new ArrayList<>();
        IModFileInfo info = ModList.get().getModFileById(modId);
        if (info == null) {
            return result;
        }

        ModFileScanData scanData = info.getFile().getScanResult();
        String annotationName = annotation.getName();

        for (var annotationData : scanData.getAnnotations()) {
            String className = annotationData.clazz().getClassName();

            if (!className.startsWith(packagePath)) {
                continue;
            }
            if (!annotationData.annotationType()
                    .getClassName().equals(annotationName)) {
                continue;
            }

            try {
                Class<?> cls = Class.forName(className, false,
                        Thread.currentThread().getContextClassLoader());
                result.add(cls);
            } catch (ClassNotFoundException e) {
                throw new VRException(e);
            }
        }

        return result;

    }


    @Override
    public void registerNetworkChannel(@NotNull VisorChannel channel) {
        networkChannels.put(channel.getChannelId(), channel);
    }

    @Override
    public @NotNull Packet<?> createPacketToClient(@NotNull Identifier channelId,
                                                   @NotNull VisorPayloadToClient payload) {
        return new ClientboundCustomPayloadPacket(
                VisorRawPayload.of(channelId, writePayload(payload)));
    }

    @Override
    public @NotNull Packet<?> createPacketToServer(@NotNull Identifier channelId,
                                                   @NotNull VisorPayloadToServer payload) {
        return new ServerboundCustomPayloadPacket(
                VisorRawPayload.of(channelId, writePayload(payload)));
    }


    @Override
    public boolean canSendToServer(@NotNull Identifier channelId) {
        return ClientTunnelSupport.serverAcceptsTunnel();
    }


    /*
     * PORT-1.21.11: NeoForge widened these to (Player, PoseStack, MaterialSet, MultiBufferSource).
     * Both still only post a RenderBlockScreenEffectEvent and return isCanceled() - neither draws
     * anything itself (verified in ClientHooks#renderBlockOverlay) - so the two new arguments exist
     * purely for listeners that want to draw the overlay themselves.
     *
     * Visor calls these as a QUESTION, not to render: GameRendererMixin passes a throwaway
     * PoseStack and uses only the boolean ("did something suppress the fire overlay?"). So the
     * buffer source handed over is a discard sink whose batches are never ended - a listener that
     * draws into it emits nothing, which is exactly what happens on Forge, whose hook never gained
     * a buffer source at all. The MaterialSet is the real one, so a listener that only looks up
     * sprites still sees correct data.
     */
    @Override
    public boolean renderWaterOverlay(Player player, PoseStack mat) {
        return ClientHooks.renderWaterOverlay(
                player, mat, visor$materials(), visor$discardBuffers());
    }

    @Override
    public boolean renderFireOverlay(Player player, PoseStack mat) {
        return ClientHooks.renderFireOverlay(
                player, mat, visor$materials(), visor$discardBuffers());
    }

    /** AtlasManager is the client's MaterialSet implementation. */
    private static MaterialSet visor$materials() {
        return Minecraft.getInstance().getAtlasManager();
    }

    private static MultiBufferSource.BufferSource visor$discardBuffers;

    private static MultiBufferSource.BufferSource visor$discardBuffers() {
        if (visor$discardBuffers == null) {
            visor$discardBuffers = MultiBufferSource.immediate(new ByteBufferBuilder(256));
        }
        return visor$discardBuffers;
    }

    @Override
    public @NotNull LoaderType getType() {
        return LoaderType.NEOFORGE;
    }


    // ----- INNER -----


    static void registerPayloads(@NotNull RegisterPayloadHandlersEvent event) {
        event.registrar(NETWORK_VERSION)
                .optional()
                .playBidirectional(
                        VisorRawPayload.TYPE,
                        VisorRawPayload.STREAM_CODEC,
                        NeoForgeModLoader::onTunnelPayload
                );
    }

    /**
     * PORT-1.21.11: NeoForge 21.11 split payload handling by side. The single-handler
     * {@code playBidirectional} overload now registers the SERVER handler only (it delegates
     * with {@code clientHandler = null}), and client handlers are registered separately through
     * this client-only mod-bus event - {@code ClientNetworkRegistry.setup()} hard-fails at
     * startup when a clientbound payload has no client handler. Same tunnel handler, default
     * thread (MAIN), matching what the registrar gave both directions before the split.
     */
    static void registerClientPayloads(
            net.neoforged.neoforge.client.network.event.@NotNull RegisterClientPayloadHandlersEvent event) {
        event.register(VisorRawPayload.TYPE, NeoForgeModLoader::onTunnelPayload);
    }

    private static void onTunnelPayload(VisorRawPayload payload, IPayloadContext context) {
        if (ModLoader.get() instanceof NeoForgeModLoader loader) {
            loader.handleTunnelPayload(payload, context);
        }
    }


    private void handleTunnelPayload(VisorRawPayload payload, IPayloadContext context) {
        VisorChannel channel = networkChannels.get(payload.channelId());
        if (channel == null) {
            return;
        }

        FriendlyByteBuf buffer = payload.toBuffer();
        try {
            if (context.flow() == PacketFlow.SERVERBOUND) {
                if (!channel.hasPacketsToServer()
                        || !(context.player() instanceof ServerPlayer sender)) {
                    return;
                }
                channel.handleToServer(buffer, sender,
                        response -> context.reply(
                                VisorRawPayload.of(channel.getChannelId(), writePayload(response))
                        ));
            } else if (channel.hasPacketsToClient()) {
                channel.handleToClient(buffer);
            }
        } finally {
            buffer.release();
        }
    }

    private static @NotNull FriendlyByteBuf writePayload(@NotNull VisorPayload payload) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        payload.write(buffer);
        return buffer;
    }


    private void onRenderLevelStage(RenderPipelineStage stage) {
        List<RenderPipelineCallback> callbacks = pipelineCallbacks.get(stage);
        if (callbacks == null || callbacks.isEmpty()) return;

        // Identity basis on purpose: the decoration renderers build their own camera transform
        // (see DecorationRendererImpl#runStageWithVRContract, which resets the model-view stack),
        // so seeding the view matrix here double-transforms them. Fabric and Forge both hand over
        // a fresh PoseStack, and this keeps all three loaders on one contract.
        PoseStack poseStack = new PoseStack();
        // PORT-1.21.11: RenderLevelStageEvent#getPartialTick() went with the Stage rework and
        // LevelRenderState carries no tick, so the delta comes from the same place the Forge
        // mixin reads it.
        float partialTicks = Minecraft.getInstance()
                .getDeltaTracker().getGameTimeDeltaPartialTick(true);

        for (RenderPipelineCallback callback : callbacks) {
            callback.render(poseStack, partialTicks);
        }
    }
}
