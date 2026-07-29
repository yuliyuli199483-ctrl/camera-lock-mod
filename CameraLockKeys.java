package com.cameralock.client;

import com.cameralock.client.config.CameraLockConfig;
import com.cameralock.client.config.CameraLockConfigManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CameraLockClient implements ClientModInitializer {
	public static final String MOD_ID = "cameralock";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static KeyMapping toggleKey;

	@Override
	public void onInitializeClient() {
		CameraLockConfigManager.load();

		int toggleKeyCode = CameraLockConfigManager.get().toggleKey;
		toggleKey = KeyBindingHelper.registerKeyBinding(
			new KeyMapping(
				"key.cameralock.toggle",
				toggleKeyCode,
				KeyMapping.Category.MISC
			)
		);
		CameraLockConfigManager.apply(toggleKey);

		LOGGER.info("Camera Lock loaded.");

		ClientLifecycleEvents.CLIENT_STARTED.register(client ->
			CameraLockConfigManager.apply(toggleKey)
		);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			CameraLockKeyHandler.tick(client);
			CameraLockManager.tick(Minecraft.getInstance());
		});

		HudRenderCallback.EVENT.register((guiGraphics, deltaTracker) -> {
			drawFOVCircle(guiGraphics);
		});
	}

	private void drawFOVCircle(GuiGraphics graphics) {
		Minecraft client = Minecraft.getInstance();
		CameraLockConfig config = CameraLockConfigManager.get();

		if (config.showFOV && CameraLockManager.isEnabled() && client.player != null && client.screen == null) {
			int width = client.getWindow().getGuiScaledWidth();
			int height = client.getWindow().getGuiScaledHeight();
			
			double fovDegrees = config.lockFOV;
			double clientFovDegrees = client.options.fov().get();

			double radius = Math.tan(Math.toRadians(fovDegrees / 2.0)) / Math.tan(Math.toRadians(clientFovDegrees / 2.0)) * (height / 2.0);

			if (radius <= 1 || radius > (width / 2.0)) return;

			int centerX = width / 2;
			int centerY = height / 2;
			int color = 0x88FFFFFF;

			int segments = 120;
			for (int i = 0; i < segments; i++) {
				double angle = Math.toRadians(i * (360.0 / segments));
				int x = (int)(centerX + Math.cos(angle) * radius);
				int y = (int)(centerY + Math.sin(angle) * radius);
				graphics.fill(x, y, x + 1, y + 1, color);
			}
		}
	}

	public static KeyMapping getToggleKey() {
		return toggleKey;
	}
}