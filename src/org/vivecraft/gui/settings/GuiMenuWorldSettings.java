package org.vivecraft.gui.settings;

import org.vivecraft.gui.framework.GuiVROptionsBase;
import org.vivecraft.gui.framework.VROptionEntry;
import org.vivecraft.settings.VRSettings;

import net.minecraft.client.gui.GuiScreen;

public class GuiMenuWorldSettings extends GuiVROptionsBase {
	private VROptionEntry[] miscSettings = new VROptionEntry[]
			{
					new VROptionEntry(VRSettings.VrOptions.MENU_WORLD_SELECTION),
					new VROptionEntry("Refresh Menu World", (button, mousePos) -> {
						if (mc.menuWorldRenderer.getWorld() != null) {
							try {
								mc.menuWorldRenderer.destroy();
								mc.menuWorldRenderer.prepare();
							} catch (Exception e) {
								e.printStackTrace();
							}
						}
						return true;
					}),
					new VROptionEntry(VRSettings.VrOptions.DUMMY),
					new VROptionEntry("Load New Menu World", (button, mousePos) -> {
						if (mc.menuWorldRenderer.getWorld() != null) {
							try {
								mc.menuWorldRenderer.destroy();
								mc.menuWorldRenderer.init();
							} catch (Exception e) {
								e.printStackTrace();
							}
						}
						return true;
					}),
			};

	public GuiMenuWorldSettings(GuiScreen guiScreen) {
		super(guiScreen);
	}

	@Override
	public void initGui()
	{
		title = "Miscellaneous Settings";

		super.initGui(miscSettings, true);

		super.addDefaultButtons();
	}

	@Override
	protected void loadDefaults() {
		VRSettings vr = mc.vrSettings;
		vr.menuWorldSelection = VRSettings.MENU_WORLD_BOTH;
	}
}
