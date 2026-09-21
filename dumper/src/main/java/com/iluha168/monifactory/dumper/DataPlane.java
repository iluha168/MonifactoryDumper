package com.iluha168.monifactory.dumper;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.Util;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientRegistryLayer;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.telemetry.TelemetryEventSender;
import net.minecraft.client.telemetry.WorldSessionTelemetryManager;
import net.minecraft.commands.Commands;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateTagsPacket;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.stats.StatsCounter;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.Difficulty;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.iluha168.monifactory.dumper.Dumper.LOG;

/**
 * What a server and a login would have given the client, produced by the same code a server runs: the datapack
 * reload, then the recipe and tag packets, written and read back through their real network format.
 * <p>
 * Mods that index recipes do it from this path themselves. GregTech, for one, fills its lookup from a mixin at the
 * tail of {@code RecipeManager.apply}, which the server reload calls. So there is nothing here about any mod.
 */
final class DataPlane {
    private DataPlane() {
    }

    /** The server side, once its datapack reload is done. */
    record Server(ReloadableServerResources resources, RegistryAccess.Frozen dynamic, RegistryAccess.Frozen full) {
    }

    /**
     * Starts the server resource reload: every datapack the instance has, the pack's KubeJS scripts and every mod's
     * data included. The returned future completes off the game thread; poll it from the frame rather than block.
     */
    static CompletableFuture<Server> startServerReload(Path gameDir) throws Exception {
        Path datapacks = gameDir.resolve("datapacks");
        Files.createDirectories(datapacks);
        PackRepository repository = ServerPacksSource.createPackRepository(datapacks);
        WorldDataConfiguration configuration = MinecraftServer.configurePackRepository(
                repository, DataPackConfig.DEFAULT, false, FeatureFlags.DEFAULT_FLAGS);
        repository.setSelected(new ArrayList<>(repository.getAvailableIds()));
        LOG.info("[dumper] server packs: {}", repository.getSelectedIds());

        CloseableResourceManager resources = new WorldLoader.PackConfig(repository, configuration, false, false)
                .createResourceManager().getSecond();

        // The worldgen and dimension registries come from datapacks, in one pass, as the server's WorldLoader does.
        List<RegistryDataLoader.RegistryData<?>> dynamicRegistries = new ArrayList<>();
        dynamicRegistries.addAll(RegistryDataLoader.WORLDGEN_REGISTRIES);
        dynamicRegistries.addAll(RegistryDataLoader.DIMENSION_REGISTRIES);
        RegistryAccess.Frozen dynamic = RegistryDataLoader.load(
                resources, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY), dynamicRegistries);
        RegistryAccess.Frozen full = RegistryLayer.createRegistryAccess()
                .replaceFrom(RegistryLayer.WORLDGEN, dynamic).compositeAccess();

        var flags = repository.getRequestedFeatureFlags();
        return ReloadableServerResources.loadResources(
                        resources, full, flags, Commands.CommandSelection.DEDICATED, 2,
                        Util.backgroundExecutor(), Runnable::run)
                .thenApply(loaded -> {
                    loaded.updateRegistryTags(full);
                    return new Server(loaded, dynamic, full);
                });
    }

    /**
     * Builds the client's side of a connection to {@code server} and joins a level, then syncs tags and recipes into
     * it. Must run on the game thread: the packet handlers run inline there, and the world swap must not race a frame.
     * <p>
     * Tags go before recipes, the reverse of a server's login order, so that EMI's reload starts on the last change
     * this makes. EMI reloads once it has seen both a TagsUpdatedEvent and a RecipesUpdatedEvent (its log lines for
     * the two are swapped: "Tags synchronized, reloading EMI" is printed on the recipes). The server reload already
     * fired the tags event, so in login order the reload would start at the recipe sync and run while the client's
     * tags are still being rebound.
     */
    static void joinAndSync(Minecraft minecraft, Server server) throws Exception {
        Connection connection = new Connection(PacketFlow.CLIENTBOUND);
        // A real session telemetry manager with sending off. Once the connection has a channel it reports itself
        // connected, and then the game ticks the listener, which ticks this.
        ClientPacketListener listener = new ClientPacketListener(minecraft, null, connection, null,
                new GameProfile(UUID.nameUUIDFromBytes("Dumper".getBytes()), "Dumper"),
                new WorldSessionTelemetryManager(TelemetryEventSender.DISABLED, false, null, null));
        connection.setListener(listener);
        installChannel(connection);
        installServerRegistries(listener, server.dynamic());

        ClientLevel level = new ClientLevel(listener,
                new ClientLevel.ClientLevelData(Difficulty.NORMAL, false, false),
                Level.OVERWORLD,
                server.full().registryOrThrow(Registries.DIMENSION_TYPE).getHolderOrThrow(BuiltinDimensionTypes.OVERWORLD),
                2, 2, minecraft::getProfiler, minecraft.levelRenderer, false, 0L);
        MultiPlayerGameMode gameMode = new MultiPlayerGameMode(minecraft, listener);
        LocalPlayer player = gameMode.createPlayer(level, new StatsCounter(), new ClientRecipeBook());
        player.setYRot(-180.0F);
        player.resetPos();
        // What Minecraft.setLevel does, minus the screen it would open. LDLib reads player.tickCount on every
        // GregTech recipe draw, and EMI reads the level's registries.
        minecraft.gameMode = gameMode;
        minecraft.player = player;
        minecraft.cameraEntity = player;
        minecraft.levelRenderer.setLevel(level);
        minecraft.particleEngine.setLevel(level);
        minecraft.level = level;
        LOG.info("[dumper] joined a client level: connection connected={}, memory={}",
                connection.isConnected(), connection.isMemoryConnection());

        syncTags(listener, server);
        syncRecipes(listener, server);
    }

    /**
     * The connection gets a netty channel, because mods ask for one. Forge's {@code NetworkHooks.getConnectionData}
     * dereferences it, and TooManyRecipeViewers calls that from its EMI plugin. What sort of channel is pinned down
     * by how Connection reads it: a closed one makes the game disconnect every tick, and one that is not registered
     * on an event loop throws on send. An {@link EmbeddedChannel} is registered, open and active with no thread and
     * no peer, and its outbound handler drops whatever mods send once they see a live connection.
     * <p>
     * The field is found by its type, so this costs no SRG name.
     */
    private static void installChannel(Connection connection) throws ReflectiveOperationException {
        Field channelField = null;
        for (Field field : Connection.class.getDeclaredFields()) {
            if (Channel.class.isAssignableFrom(field.getType())) {
                if (channelField != null) {
                    throw new IllegalStateException("Connection has more than one Channel field");
                }
                channelField = field;
            }
        }
        if (channelField == null) {
            throw new NoSuchFieldException("Connection has no Channel field");
        }
        channelField.setAccessible(true);
        channelField.set(connection, new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                ReferenceCountUtil.release(message);
                promise.setSuccess();
            }
        }));
    }

    /**
     * What the login packet does: the listener's REMOTE layer becomes the server's networked registries. Only the
     * dynamic ones; the built-in registries already sit in the static layer, and merging them twice is an error.
     */
    @SuppressWarnings("unchecked")
    private static void installServerRegistries(ClientPacketListener listener, RegistryAccess.Frozen dynamic)
            throws ReflectiveOperationException {
        List<Registry<?>> remote = new ArrayList<>();
        dynamic.registries().forEach(entry -> {
            if (!BuiltInRegistries.REGISTRY.containsKey(entry.key().location())) {
                remote.add(entry.value());
            }
        });
        Field field = ClientPacketListener.class.getDeclaredField(Dumper.SRG_CLIENT_PACKET_LISTENER_REGISTRY_ACCESS);
        field.setAccessible(true);
        var access = (LayeredRegistryAccess<ClientRegistryLayer>) field.get(listener);
        field.set(listener, access.replaceFrom(ClientRegistryLayer.REMOTE,
                new RegistryAccess.ImmutableRegistryAccess(remote).freeze()));
        LOG.info("[dumper] client REMOTE registry layer holds {} registries", remote.size());
    }

    /** The server's recipe packet, serialised and parsed back, handed to the client's own handler. */
    private static void syncRecipes(ClientPacketListener listener, Server server) {
        List<Recipe<?>> recipes = new ArrayList<>(server.resources().getRecipeManager().getRecipes());
        LOG.info("[dumper] server reload: {} recipes, hadErrorsLoading={}",
                recipes.size(), server.resources().getRecipeManager().hadErrorsLoading());
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new ClientboundUpdateRecipesPacket(recipes).write(buffer);
            listener.handleUpdateRecipes(new ClientboundUpdateRecipesPacket(buffer));
        } finally {
            buffer.release();
        }
        LOG.info("[dumper] client recipe manager holds {}/{} recipes",
                listener.getRecipeManager().getRecipes().size(), recipes.size());
    }

    /**
     * The server's tag packet, through the client's own handler, which fires TagsUpdatedEvent. The payload is built
     * from a server-shaped registry stack whose RELOADABLE layer holds only the dynamic registries, since the static
     * layer already holds the built-in ones.
     */
    private static void syncTags(ClientPacketListener listener, Server server) {
        var layers = RegistryLayer.createRegistryAccess().replaceFrom(RegistryLayer.RELOADABLE, server.dynamic());
        var payload = TagNetworkSerialization.serializeTagsToNetwork(layers);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new ClientboundUpdateTagsPacket(payload).write(buffer);
            listener.handleUpdateTags(new ClientboundUpdateTagsPacket(buffer));
        } finally {
            buffer.release();
        }
        LOG.info("[dumper] tags synced for {} registries", payload.size());
    }
}
