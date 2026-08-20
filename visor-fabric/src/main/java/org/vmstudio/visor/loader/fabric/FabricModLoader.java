package org.vmstudio.visor.loader.fabric;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import io.netty.buffer.Unpooled;
import net.minecraft.resources.Identifier;
import org.vmstudio.visor.api.ModLoader;
import org.vmstudio.visor.api.VisorAPI;
import org.vmstudio.visor.api.client.render.RenderPipelineCallback;
import org.vmstudio.visor.api.client.render.RenderPipelineStage;
import org.vmstudio.visor.api.common.VRException;
import org.vmstudio.visor.api.common.network.VisorChannel;
import org.vmstudio.visor.api.common.network.VisorPayloadToClient;
import org.vmstudio.visor.api.common.network.VisorPayloadToServer;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.vmstudio.visor.loader.fabric.network.VisorRawPayload;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.*;


import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

public class FabricModLoader implements ModLoader {
    private final File configFolder = FabricLoader.getInstance()
            .getConfigDir().toFile();

    // static so FabricLevelRendererVRMixin can reach it, mirroring ForgeModLoader
    private static final Map<RenderPipelineStage, List<RenderPipelineCallback>> pipelineCallbacks
            = new EnumMap<>(RenderPipelineStage.class);

    private final Map<Identifier, VisorChannel> networkChannels = new HashMap<>();
    private boolean worldEventsRegistered = false;
    private boolean serverReceiverRegistered = false;
    private boolean clientReceiverRegistered = false;


    @Override
    public File getConfigFolder() {
        return configFolder;
    }


    @Override
    public boolean isModLoaded(@NotNull String id) {
        return FabricLoader.getInstance().isModLoaded(id);
    }
    @Override
    public @NotNull String getModVersion(@NotNull String id) {
        if (isModLoaded(VisorAPI.MOD_ID)) {
            return FabricLoader.getInstance()
                    .getModContainer(id)
                    .get().getMetadata().getVersion().getFriendlyString();
        }
        return "version not found";
    }

    @Override
    public boolean isDedicatedServer() {
        return FabricLoader.getInstance().getEnvironmentType().equals(EnvType.SERVER);
    }


    @Override
    public void addToRenderPipeline(@NotNull RenderPipelineStage stage,
                                    @NotNull RenderPipelineCallback callback) {
        pipelineCallbacks
                .computeIfAbsent(stage, k -> new CopyOnWriteArrayList<>())
                .add(callback);

        if (!worldEventsRegistered) {
            /*
             * PORT-1.21.11: WorldRenderEvents moved to ...rendering.v1.world and the event set was
             * reshuffled for the extract/submit split. Two of the three Visor used survived in
             * spirit:
             *   BEFORE_ENTITIES  - unchanged, still "after the SOLID, CUTOUT and CUTOUT_MIPPED
             *                      terrain layers are drawn, before entities", which is what Visor
             *                      already used as its closest equivalent of AFTER_SOLID.
             *   AFTER_TRANSLUCENT -> END_MAIN. Both fire once translucent terrain is on the
             *                      framebuffer and before particles, clouds and weather.
             * The context lost tickCounter() (it only exists on the extraction context now) and
             * matrixStack() is matrices().
             */
            WorldRenderEvents.BEFORE_ENTITIES.register(context ->
                    fireCallbacks(RenderPipelineStage.AFTER_SOLID,
                            context.matrices(), visor$partialTicks()));

            WorldRenderEvents.END_MAIN.register(context ->
                    fireCallbacks(RenderPipelineStage.AFTER_TRANSLUCENT,
                            context.matrices(), visor$partialTicks()));

            // AFTER_WORLD has no event any more - WorldRenderEvents.END is gone and END_MAIN
            // stops short of particles, clouds and weather, so it would collide with
            // AFTER_TRANSLUCENT rather than replace END. FabricLevelRendererVRMixin fires it from
            // the tail of renderLevel instead, which is where END used to sit.
            worldEventsRegistered = true;
        }
    }

    /**
     * The drawing contexts no longer carry a DeltaTracker - only the extraction context does - and
     * this is the same instance renderLevel is handed, including on Visor's own per-eye passes
     * (VisorScene drives them with MC.gameRenderer.render(MC.getDeltaTracker(), ...)).
     */
    private static float visor$partialTicks() {
        return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
    }

    /** Entry point for FabricLevelRendererVRMixin, which owns the AFTER_WORLD stage. */
    public static void fireRenderPipelineStage(@NotNull RenderPipelineStage stage,
                                               @NotNull PoseStack poseStack,
                                               float partialTicks) {
        fireCallbacks(stage, poseStack, partialTicks);
    }

    private static void fireCallbacks(RenderPipelineStage stage, PoseStack poseStack, float partialTicks) {
        List<RenderPipelineCallback> callbacks = pipelineCallbacks.get(stage);
        if (callbacks == null || callbacks.isEmpty()) return;
        for (RenderPipelineCallback cb : callbacks) {
            cb.render(poseStack, partialTicks);
        }
    }


    @Override
    public boolean enableRenderTargetStencil(@NotNull RenderTarget renderTarget) {
        return false;
    }

    @Override
    public double getItemEntityReach(double baseRange, ItemStack itemStack, EquipmentSlot slot) {
        return baseRange;
    }


    public @NotNull List<Class<?>> getClassesAnnotated(
            @NotNull Class<? extends Annotation> annotation,
            @NotNull String modId,
            @NotNull String packagePath
    ) {
        try {
            List<Class<?>> result = new ArrayList<>();

            ModContainer container = FabricLoader.getInstance()
                    .getModContainer(modId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown mod: " + modId));

            String pkgPath = packagePath.replace('.', '/');

            for (Path root : container.getRootPaths()) {
                Path pkgRoot = root.resolve(pkgPath);
                if (!Files.exists(pkgRoot)) continue;

                try (Stream<Path> stream = Files.walk(pkgRoot)) {
                    stream
                            .filter(p -> p.getFileName().toString().endsWith(".class"))
                            .forEach(classFile -> {
                                try (InputStream in = Files.newInputStream(classFile)) {
                                    ClassReader reader = new ClassReader(in);
                                    reader.accept(new ClassVisitor(Opcodes.ASM9) {
                                        @Override
                                        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                                            String found = Type.getType(desc).getClassName();
                                            if (found.equals(annotation.getName())) {
                                                Path rel = pkgRoot.relativize(classFile);
                                                String className = packagePath + "."
                                                        + rel.toString()
                                                        .replace('/', '.')
                                                        .replace('\\', '.')
                                                        .replaceAll("\\.class$", "");
                                                try {
                                                    result.add(
                                                            Class.forName(
                                                                    className,
                                                                    false,
                                                                    Thread.currentThread().getContextClassLoader()
                                                            )
                                                    );
                                                } catch (ClassNotFoundException e) {
                                                    throw new VRException(e);
                                                }
                                            }
                                            return super.visitAnnotation(desc, visible);
                                        }
                                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            });
                }
            }
            return result;
        }catch (Exception e){
            throw new VRException(e);
        }
    }


    /**
     * 1.21.1: Fabric networking is payload-typed. All Visor channels share
     * the single VisorRawPayload tunnel (registered at mod init) and are
     * dispatched here by the channel id carried in the payload — receivers
     * are registered once, so channels may register at any time.
     */
    @Override
    public void registerNetworkChannel(@NotNull VisorChannel channel) {
        networkChannels.put(channel.getChannelId(), channel);

        if (channel.hasPacketsToServer() && !serverReceiverRegistered) {
            ServerPlayNetworking.registerGlobalReceiver(VisorRawPayload.TYPE, (payload, context) -> {
                VisorChannel registeredChannel = networkChannels.get(payload.channelId());
                if (registeredChannel == null || !registeredChannel.hasPacketsToServer()) {
                    return;
                }
                context.server().execute(() -> {
                    FriendlyByteBuf buffer = payload.toBuffer();
                    try {
                        registeredChannel.handleToServer(buffer, context.player(),
                                p -> context.responseSender().sendPacket(
                                        ModLoader.get().createPacketToClient(registeredChannel.getChannelId(), p)
                                ));
                    } finally {
                        buffer.release();
                    }
                });
            });
            serverReceiverRegistered = true;
        }
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT
                && channel.hasPacketsToClient()
                && !clientReceiverRegistered) {
            ClientPlayNetworking.registerGlobalReceiver(VisorRawPayload.TYPE, (payload, context) -> {
                VisorChannel registeredChannel = networkChannels.get(payload.channelId());
                if (registeredChannel == null || !registeredChannel.hasPacketsToClient()) {
                    return;
                }
                context.client().execute(() -> {
                    FriendlyByteBuf buffer = payload.toBuffer();
                    try {
                        registeredChannel.handleToClient(buffer);
                    } finally {
                        buffer.release();
                    }
                });
            });
            clientReceiverRegistered = true;
        }
    }

    @Override
    public @NotNull Packet<?> createPacketToClient(@NotNull Identifier channelId,
                                                   @NotNull VisorPayloadToClient payload) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        payload.write(buffer);
        return ServerPlayNetworking.createS2CPacket(VisorRawPayload.of(channelId, buffer));
    }

    @Override
    public @NotNull Packet<?> createPacketToServer(@NotNull Identifier channelId,
                                                   @NotNull VisorPayloadToServer payload) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        payload.write(buffer);
        return ClientPlayNetworking.createC2SPacket(VisorRawPayload.of(channelId, buffer));
    }


    @Override
    public boolean renderWaterOverlay(Player player, PoseStack mat) {
        return false;
    }

    @Override
    public boolean renderFireOverlay(Player player, PoseStack mat) {
        return false;
    }

    @Override
    public @NotNull LoaderType getType() {
        return LoaderType.FABRIC;
    }
}