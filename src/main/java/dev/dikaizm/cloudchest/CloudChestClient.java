package dev.dikaizm.cloudchest;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

public class CloudChestClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		CloudChest.notifier = (title, msg) -> {
			Minecraft mc = Minecraft.getInstance();
			mc.execute(() -> SystemToast.add(mc.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
				Component.literal(title), Component.literal(msg)));
		};
	}
}
