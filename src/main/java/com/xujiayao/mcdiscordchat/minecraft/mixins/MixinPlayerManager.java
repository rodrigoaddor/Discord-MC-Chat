package com.xujiayao.mcdiscordchat.minecraft.mixins;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.message.MessageType;
//#if MC >= 11900
import net.minecraft.network.message.SignedMessage;
//#endif
import net.minecraft.server.PlayerManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
//#if MC < 11900
//$$ import net.minecraft.text.Text;
//#endif
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.xujiayao.mcdiscordchat.utils.Translations;
import com.xujiayao.mcdiscordchat.utils.Utils;

//#if MC < 11900
//$$ import java.util.UUID;
//#endif

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.xujiayao.mcdiscordchat.Main.CHANNEL;
import static com.xujiayao.mcdiscordchat.Main.CONFIG;
import static com.xujiayao.mcdiscordchat.Main.HTTP_CLIENT;
import static com.xujiayao.mcdiscordchat.Main.JDA;
import static com.xujiayao.mcdiscordchat.Main.LOGGER;
import static com.xujiayao.mcdiscordchat.Main.MULTI_SERVER;
import static com.xujiayao.mcdiscordchat.Main.WEBHOOK;

/**
 * @author Xujiayao
 */
@Mixin(PlayerManager.class)
public class MixinPlayerManager {

	@Inject(method = "broadcast(Lnet/minecraft/network/message/SignedMessage;Lnet/minecraft/server/command/ServerCommandSource;Lnet/minecraft/network/message/MessageType$Parameters;)V", at = @At("RETURN"))
	private void broadcast(SignedMessage message, ServerCommandSource source, MessageType.Parameters params, CallbackInfo ci) {
		sendMessage(message.getSignedContent(), source.getName());
	}

	private void sendMessage(String content, String username) {
		if (CONFIG.generic.broadcastChatMessages) {
			if (!CONFIG.generic.useWebhook) {
				if (CONFIG.multiServer.enable) {
					CHANNEL.sendMessage(Translations.translateMessage("message.messageWithoutWebhookForMultiServer")
							.replace("%server%", CONFIG.multiServer.name)
							.replace("%name%", username)
							.replace("%message%", content)).queue();
				} else {
					CHANNEL.sendMessage(Translations.translateMessage("message.messageWithoutWebhook")
							.replace("%name%", username)
							.replace("%message%", content)).queue();
				}
			} else {
				JsonObject body = new JsonObject();
				body.addProperty("content", content);
				body.addProperty("username", ((CONFIG.multiServer.enable) ? ("[" + CONFIG.multiServer.name + "] " + username) : username));
				body.addProperty("avatar_url", JDA.getSelfUser().getAvatarUrl());

				JsonObject allowedMentions = new JsonObject();
				allowedMentions.add("parse", new Gson().toJsonTree(CONFIG.generic.allowedMentions).getAsJsonArray());
				body.add("allowed_mentions", allowedMentions);

				Request request = new Request.Builder()
						.url(WEBHOOK.getUrl())
						.post(RequestBody.create(body.toString(), MediaType.get("application/json")))
						.build();

				ExecutorService executor = Executors.newFixedThreadPool(1);
				executor.submit(() -> {
					try {
						Response response = HTTP_CLIENT.newCall(request).execute();
						response.close();
					} catch (Exception e) {
						LOGGER.error(ExceptionUtils.getStackTrace(e));
					}
				});
				executor.shutdown();
			}

			if (CONFIG.multiServer.enable) {
				MULTI_SERVER.sendMessage(false, true, false, username, content);
			}
		}
	}

	@Inject(method = "onPlayerConnect", at = @At("RETURN"))
	private void onPlayerConnect(ClientConnection connection, ServerPlayerEntity player, ConnectedClientData clientData, CallbackInfo ci) {
		Utils.setBotActivity();

		if (CONFIG.generic.announcePlayerJoinLeave) {
			CHANNEL.sendMessage(Translations.translateMessage("message.joinServer")
					//#if MC >= 12003
					.replace("%playerName%", MarkdownSanitizer.escape(Objects.requireNonNull(player.getDisplayName()).getString()))).queue();
					//#else
					//$$ .replace("%playerName%", MarkdownSanitizer.escape(Objects.requireNonNull(player.getDisplayName()).getString()))).queue();
					//#endif
			if (CONFIG.multiServer.enable) {
				MULTI_SERVER.sendMessage(false, false, false, null, Translations.translateMessage("message.joinServer")
						//#if MC >= 12003
						.replace("%playerName%", MarkdownSanitizer.escape(player.getDisplayName().getString())));
						//#else
						//$$ .replace("%playerName%", MarkdownSanitizer.escape(player.getDisplayName().getString())));
						//#endif
			}
		}
	}

	@Inject(method = "remove", at = @At("RETURN"))
	private void remove(ServerPlayerEntity player, CallbackInfo ci) {
		Utils.setBotActivity();

		if (CONFIG.generic.announcePlayerJoinLeave) {
			CHANNEL.sendMessage(Translations.translateMessage("message.leftServer")
					//#if MC >= 12003
					.replace("%playerName%", MarkdownSanitizer.escape(Objects.requireNonNull(player.getDisplayName()).getString()))).queue();
					//#else
					//$$ .replace("%playerName%", MarkdownSanitizer.escape(Objects.requireNonNull(player.getDisplayName()).getString()))).queue();
					//#endif
			if (CONFIG.multiServer.enable) {
				MULTI_SERVER.sendMessage(false, false, false, null, Translations.translateMessage("message.leftServer")
						//#if MC >= 12003
						.replace("%playerName%", MarkdownSanitizer.escape(player.getDisplayName().getString())));
						//#else
						//$$ .replace("%playerName%", MarkdownSanitizer.escape(player.getDisplayName().getString())));
						//#endif
			}
		}
	}
}

