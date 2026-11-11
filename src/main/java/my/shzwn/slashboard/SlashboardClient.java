package my.shzwn.slashboard;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SlashboardClient implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("slashboard");
	public static Store store;

	@Override
	public void onInitializeClient() {
		store = new Store(FabricLoader.getInstance().getConfigDir().resolve("slashboard.json"));
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("slashboard", "main"));
		KeyMapping open = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.slashboard.open", InputConstants.Type.KEYBOARD, InputConstants.KEY_H, category));

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (open.consumeClick()) {
				if (mc.player != null && mc.gui.screen() == null) mc.gui.setScreen(new SlashboardScreen());
			}
		});
	}
}
