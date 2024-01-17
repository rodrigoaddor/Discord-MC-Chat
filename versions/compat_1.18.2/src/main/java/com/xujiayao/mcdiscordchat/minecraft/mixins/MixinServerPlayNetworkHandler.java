//#if MC >= 11600
package com.xujiayao.mcdiscordchat.minecraft.mixins;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.fellbaum.jemoji.EmojiManager;
import net.minecraft.client.option.ChatVisibility;
import net.minecraft.network.MessageType;
import net.minecraft.network.Packet;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.server.MinecraftServer;
//#if MC >= 11700
import net.minecraft.server.filter.TextStream.Message;
//#endif
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.xujiayao.mcdiscordchat.utils.MarkdownParser;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.xujiayao.mcdiscordchat.Main.CHANNEL;
import static com.xujiayao.mcdiscordchat.Main.CONFIG;
import static com.xujiayao.mcdiscordchat.Main.HTTP_CLIENT;
import static com.xujiayao.mcdiscordchat.Main.JDA;
import static com.xujiayao.mcdiscordchat.Main.LOGGER;
import static com.xujiayao.mcdiscordchat.Main.MINECRAFT_LAST_RESET_TIME;
import static com.xujiayao.mcdiscordchat.Main.MINECRAFT_SEND_COUNT;
import static com.xujiayao.mcdiscordchat.Main.MULTI_SERVER;
import static com.xujiayao.mcdiscordchat.Main.SERVER;
import static com.xujiayao.mcdiscordchat.Main.WEBHOOK;

/**
 * @author Xujiayao
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class MixinServerPlayNetworkHandler {

	@Shadow
	private ServerPlayerEntity player;

	@Final
	@Shadow
	private MinecraftServer server;

	@Shadow
	private int messageCooldown;

	@Shadow
	public abstract void sendPacket(Packet<?> packet);

	@Shadow
	public abstract void executeCommand(String input);

	@Shadow
	public abstract void disconnect(Text reason);

	//#if MC >= 11700
	@Inject(method = "handleMessage", at = @At("HEAD"), cancellable = true)
	private void handleMessage(Message message, CallbackInfo ci) {
	//#else
	//$$ @Inject(method = "method_31286", at = @At("HEAD"), cancellable = true)
	//$$ private void handleMessage(String string, CallbackInfo ci) {
	//#endif
		if (player.getClientChatVisibility() == ChatVisibility.HIDDEN) {
			sendPacket(new GameMessageS2CPacket((new TranslatableText("chat.disabled.options")).formatted(Formatting.RED), MessageType.SYSTEM, Util.NIL_UUID));
			ci.cancel();
		} else {
			player.updateLastActionTime();

			//#if MC >= 11700
			if (message.getRaw().startsWith("/")) {
				executeCommand(message.getRaw());
			//#else
			//$$ if (string.startsWith("/")) {
			//$$  executeCommand(string);
			//#endif
				ci.cancel();
			} else {
				//#if MC >= 11700
				String contentToDiscord = message.getRaw();
				String contentToMinecraft = message.getRaw();
				//#else
				//$$ String contentToDiscord = string;
				//$$ String contentToMinecraft = string;
				//#endif

				if (StringUtils.countMatches(contentToDiscord, ":") >= 2) {
					String[] emojiNames = StringUtils.substringsBetween(contentToDiscord, ":", ":");
					for (String emojiName : emojiNames) {
						List<RichCustomEmoji> emojis = JDA.getEmojisByName(emojiName, true);
						if (!emojis.isEmpty()) {
							contentToDiscord = StringUtils.replaceIgnoreCase(contentToDiscord, (":" + emojiName + ":"), emojis.get(0).getAsMention());
							contentToMinecraft = StringUtils.replaceIgnoreCase(contentToMinecraft, (":" + emojiName + ":"), (Formatting.YELLOW + ":" + emojiName + ":" + Formatting.RESET));
						} else if (EmojiManager.getByAlias(emojiName).isPresent()) {
							contentToMinecraft = StringUtils.replaceIgnoreCase(contentToMinecraft, (":" + emojiName + ":"), (Formatting.YELLOW + ":" + emojiName + ":" + Formatting.RESET));
						}
					}
				}

				if (!CONFIG.generic.allowedMentions.isEmpty() && contentToDiscord.contains("@")) {
					if (CONFIG.generic.allowedMentions.contains("users")) {
						for (Member member : CHANNEL.getMembers()) {
							String usernameMention = "@" + member.getUser().getName();
							String displayNameMention = "@" + member.getUser().getEffectiveName();
							String formattedMention = Formatting.YELLOW + "@" + member.getEffectiveName() + Formatting.RESET;

							contentToDiscord = StringUtils.replaceIgnoreCase(contentToDiscord, usernameMention, member.getAsMention());
							contentToMinecraft = StringUtils.replaceIgnoreCase(contentToMinecraft, usernameMention, MarkdownSanitizer.escape(formattedMention));

							contentToDiscord = StringUtils.replaceIgnoreCase(contentToDiscord, displayNameMention, member.getAsMention());
							contentToMinecraft = StringUtils.replaceIgnoreCase(contentToMinecraft, displayNameMention, MarkdownSanitizer.escape(formattedMention));

							if (member.getNickname() != null) {
								String nicknameMention = "@" + member.getNickname();
								contentToDiscord = StringUtils.replaceIgnoreCase(contentToDiscord, nicknameMention, member.getAsMention());
								contentToMinecraft = StringUtils.replaceIgnoreCase(contentToMinecraft, nicknameMention, MarkdownSanitizer.escape(formattedMention));
							}
						}
					}

					if (CONFIG.generic.allowedMentions.contains("roles")) {
						for (Role role : CHANNEL.getGuild().getRoles()) {
							String roleMention = "@" + role.getName();
							String formattedMention = Formatting.YELLOW + "@" + role.getName() + Formatting.RESET;
							contentToDiscord = StringUtils.replaceIgnoreCase(contentToDiscord, roleMention, role.getAsMention());
							contentToMinecraft = StringUtils.replaceIgnoreCase(contentToMinecraft, roleMention, MarkdownSanitizer.escape(formattedMention));
						}
					}

					if (CONFIG.generic.allowedMentions.contains("everyone")) {
						contentToMinecraft = StringUtils.replaceIgnoreCase(contentToMinecraft, "@everyone", Formatting.YELLOW + "@everyone" + Formatting.RESET);
						contentToMinecraft = StringUtils.replaceIgnoreCase(contentToMinecraft, "@here", Formatting.YELLOW + "@here" + Formatting.RESET);
					}
				}

				contentToMinecraft = MarkdownParser.parseMarkdown(contentToMinecraft.replace("\\", "\\\\"));

				for (String protocol : new String[]{"http://", "https://"}) {
					if (contentToMinecraft.contains(protocol)) {
						String[] links = StringUtils.substringsBetween(contentToMinecraft, protocol, " ");
						if (!StringUtils.substringAfterLast(contentToMinecraft, protocol).contains(" ")) {
							links = ArrayUtils.add(links, StringUtils.substringAfterLast(contentToMinecraft, protocol));
						}
						for (String link : links) {
							if (link.contains("\n")) {
								link = StringUtils.substringBefore(link, "\n");
							}

							String hyperlinkInsert;
							if (StringUtils.containsIgnoreCase(link, "gif")
									&& StringUtils.containsIgnoreCase(link, "tenor.com")) {
								hyperlinkInsert = "\"},{\"text\":\"<gif>\",\"underlined\":true,\"color\":\"yellow\",\"clickEvent\":{\"action\":\"open_url\",\"value\":\"" + protocol + link + "\"},\"hoverEvent\":{\"action\":\"show_text\",\"contents\":[{\"text\":\"Open URL\"}]}},{\"text\":\"";
							} else {
								hyperlinkInsert = "\"},{\"text\":\"" + protocol + link + "\",\"underlined\":true,\"color\":\"yellow\",\"clickEvent\":{\"action\":\"open_url\",\"value\":\"" + protocol + link + "\"},\"hoverEvent\":{\"action\":\"show_text\",\"contents\":[{\"text\":\"Open URL\"}]}},{\"text\":\"";
							}
							contentToMinecraft = StringUtils.replaceIgnoreCase(contentToMinecraft, (protocol + link), hyperlinkInsert);
						}
					}
				}

				if (CONFIG.generic.formatChatMessages) {
					//#if MC >= 11800
					server.getPlayerManager().broadcast(new TranslatableText("chat.type.text", player.getDisplayName(), Text.Serializer.fromJson("[{\"text\":\"" + contentToMinecraft + "\"}]")), MessageType.CHAT, player.getUuid());
					//#else
					//$$ server.getPlayerManager().broadcastChatMessage(new TranslatableText("chat.type.text", player.getDisplayName(), Text.Serializer.fromJson("[{\"text\":\"" + contentToMinecraft + "\"}]")), MessageType.CHAT, player.getUuid());
					//#endif
					ci.cancel();
				}

				sendMessage(contentToDiscord, false);
				if (CONFIG.multiServer.enable) {
					//#if MC >= 11700
					MULTI_SERVER.sendMessage(false, true, false, player.getDisplayName().getString(), CONFIG.generic.formatChatMessages ? contentToMinecraft : message.getRaw());
					//#else
					//$$ MULTI_SERVER.sendMessage(false, true, false, player.getDisplayName().getString(), CONFIG.generic.formatChatMessages ? contentToMinecraft : string);
					//#endif
				}
			}

			messageCooldown += 20;
			if (messageCooldown > 200 && !server.getPlayerManager().isOperator(player.getGameProfile())) {
				disconnect(new TranslatableText("disconnect.spam"));
			}
		}
	}

	@Inject(method = "executeCommand", at = @At(value = "HEAD"))
	private void executeCommand(String input, CallbackInfo ci) {
		if (CONFIG.generic.broadcastPlayerCommandExecution) {
			for (String command : CONFIG.generic.excludedCommands) {
				if (input.startsWith(command + " ")) {
					return;
				}
			}

			if ((System.currentTimeMillis() - MINECRAFT_LAST_RESET_TIME) > 20000) {
				MINECRAFT_SEND_COUNT = 0;
				MINECRAFT_LAST_RESET_TIME = System.currentTimeMillis();
			}

			MINECRAFT_SEND_COUNT++;
			if (MINECRAFT_SEND_COUNT <= 20) {
				Text text = new LiteralText("<").append(player.getDisplayName().getString()).append("> ").append(input);

				server.getPlayerManager().getPlayerList().forEach(
						player -> player.sendMessage(text, false));

				SERVER.sendSystemMessage(text, player.getUuid());

				sendMessage(input, true);
				if (CONFIG.multiServer.enable) {
					MULTI_SERVER.sendMessage(false, true, false, player.getDisplayName().getString(), MarkdownSanitizer.escape(input));
				}
			}
		}
	}

	private void sendMessage(String message, boolean escapeMarkdown) {
		String content = (escapeMarkdown ? MarkdownSanitizer.escape(message) : message);

		if (!CONFIG.generic.useWebhook) {
			CHANNEL.sendMessage(((CONFIG.multiServer.enable) ? ("[" + CONFIG.multiServer.name + "] <") : "<") + player.getDisplayName().getString() + "> " + content).queue();
		} else {
			JsonObject body = new JsonObject();
			body.addProperty("content", content);
			body.addProperty("username", ((CONFIG.multiServer.enable) ? ("[" + CONFIG.multiServer.name + "] " + player.getDisplayName().getString()) : player.getDisplayName().getString()));
			body.addProperty("avatar_url", CONFIG.generic.avatarApi.replace("%player%", (CONFIG.generic.useUuidInsteadOfName ? player.getUuid().toString() : player.getDisplayName().getString())));

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
	}
}
//#endif