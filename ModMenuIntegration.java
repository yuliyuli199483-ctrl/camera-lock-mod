package com.cameralock.client;

import com.cameralock.client.config.CameraLockConfigManager;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
public final class CameraLockKeyHandler {
	private static boolean keyWasDown;

	private CameraLockKeyHandler() {
	}

	public static void tick(Minecraft client) {
		if (client.player == null || client.screen != null) {
			keyWasDown = false;
			return;
		}

		int keyCode = CameraLockConfigManager.get().toggleKey;
		Window window = client.getWindow();
		boolean keyDown = InputConstants.isKeyDown(window, keyCode);

		if (keyDown && !keyWasDown) {
			boolean enabled = CameraLockManager.toggle();
			if (CameraLockConfigManager.get().showMessages) {
				Component message = enabled
					? Component.translatable("message.cameralock.enabled")
					: Component.translatable("message.cameralock.disabled");
				client.player.displayClientMessage(message, true);
			}
		}

		keyWasDown = keyDown;
	}

	public static void reset() {
		keyWasDown = false;
	}
}
