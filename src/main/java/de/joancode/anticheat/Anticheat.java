package de.joancode.anticheat;

import de.joancode.anticheat.config.ConfigManager;
import de.joancode.anticheat.networking.RequestVerificationPayload;
import de.joancode.anticheat.networking.VerificationResponsePayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Anticheat implements ModInitializer {
	public static final String MOD_ID = "anticheat";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static Set<String> BANNED_MODS = Collections.emptySet();

	private static boolean PAYLOADS_REGISTERED = false;

	private final Set<UUID> verifiedPlayers = ConcurrentHashMap.newKeySet();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "anticheat-verification");
		// Mark daemon so it doesn't block server shutdown
		t.setDaemon(true);
		return t;
	});


	@Override
	public void onInitialize() {
		BANNED_MODS = ConfigManager.loadBannedMods();
		LOGGER.info("Loaded {} banned mod(s) from config.", BANNED_MODS.size());

		// Register custom payload codecs once (runs on both client + server because environment="*")
		if (!PAYLOADS_REGISTERED) {
			PayloadTypeRegistry.playS2C().register(RequestVerificationPayload.ID, RequestVerificationPayload.CODEC);
			PayloadTypeRegistry.playC2S().register(VerificationResponsePayload.ID, VerificationResponsePayload.CODEC);
			PAYLOADS_REGISTERED = true;
		}

		// Register the event for when a player joins
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			final ServerPlayerEntity player = handler.getPlayer();
			final UUID playerUuid = player.getUuid();

			LOGGER.info("[AntiCheat] Player {} joined; initiating verification.", player.getName().getString());

			// Start timeout task (longer & clearer messaging)
			scheduler.schedule(() -> {
				if (!verifiedPlayers.contains(playerUuid)) {
					server.execute(() -> {
						if (!player.isDisconnected()) {
							LOGGER.warn("Player {} failed to verify (no response) within timeout; disconnecting.", player.getName().getString());
							String msg = ServerPlayNetworking.canSend(player, RequestVerificationPayload.ID)
								? "AntiCheat verification failed (no response)."
								: "You must install the AntiCheat mod to join this server.";
							player.networkHandler.disconnect(Text.of(msg));
						}
					});
				}
			}, 12, TimeUnit.SECONDS); // 12-second timeout to allow for slow clients

			// Attempt to send verification request; if channel missing we'll rely on timeout to kick with proper message
			if (ServerPlayNetworking.canSend(player, RequestVerificationPayload.ID)) {
				LOGGER.debug("[AntiCheat] Sending verification request to {}.", player.getName().getString());
				ServerPlayNetworking.send(player, new RequestVerificationPayload());
			} else {
				LOGGER.debug("[AntiCheat] Player {} cannot receive verification payload (no mod); will be kicked on timeout.", player.getName().getString());
			}
		});

		// Register the handler for the client's response
		ServerPlayNetworking.registerGlobalReceiver(VerificationResponsePayload.ID, (payload, context) -> {
			var player = context.player();
			var server = player.getServer();
			if (server == null) {
				LOGGER.warn("[AntiCheat] Could not obtain server instance for player {} during verification response.", player.getName().getString());
				return;
			}
			// Ensure logic runs on main server thread
			server.execute(() -> {
				if (player.isDisconnected()) return; // Already gone
				var mods = payload.installedMods();
				if (mods == null) mods = Collections.emptyList();
				LOGGER.info("[AntiCheat] Verification response from {}: {} mods {}", player.getName().getString(), mods.size(), mods.toList());
				var offending = mods.stream().filter(BANNED_MODS::contains).toList();
				if (!offending.isEmpty()) {
					LOGGER.warn("Player {} has banned mods: {}", player.getName().getString(), offending);
					player.networkHandler.disconnect(Text.of("Banned mods: " + String.join(", ", offending)));
					return;
				}
				verifiedPlayers.add(player.getUuid());
				LOGGER.info("Player {} successfully verified ({} total verified).", player.getName().getString(), verifiedPlayers.size());
			});
		});

		// Clean up verified players when they disconnect
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			var removed = verifiedPlayers.remove(handler.getPlayer().getUuid());
			if (removed) LOGGER.debug("[AntiCheat] Cleaned up verification state for {}.", handler.getPlayer().getName().getString());
		});
	}
}
