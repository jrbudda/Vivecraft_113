package org.vivecraft.gui.settings;

import org.vivecraft.gui.framework.GuiVROptionsBase;
import org.vivecraft.settings.VRSettings;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

public class GuiTeleportSettings extends GuiVROptionsBase {
	private static VRSettings.VrOptions[] teleportSettings = new VRSettings.VrOptions[] {
			VRSettings.VrOptions.SIMULATE_FALLING,
			VRSettings.VrOptions.LIMIT_TELEPORT
	};

	private static VRSettings.VrOptions[] limitedTeleportSettings = new VRSettings.VrOptions[] {
			VRSettings.VrOptions.TELEPORT_UP_LIMIT,
			VRSettings.VrOptions.TELEPORT_DOWN_LIMIT,
			VRSettings.VrOptions.TELEPORT_HORIZ_LIMIT
	};

	public GuiTeleportSettings(GuiScreen guiScreen) {
		super(guiScreen);
	}

	@Override
	public void initGui()
	{
		title = "Teleport Settings";

		super.initGui(teleportSettings, true);
		if (settings.vrLimitedSurvivalTeleport)
			super.initGui(limitedTeleportSettings, false);

		super.addDefaultButtons();
	}

	@Override
	protected void loadDefaults() {
		VRSettings vrSettings = mc.vrSettings;
		vrSettings.vrLimitedSurvivalTeleport = true;
		vrSettings.simulateFalling = true;
		vrSettings.vrTeleportDownLimit = 4;
		vrSettings.vrTeleportUpLimit = 1;
		vrSettings.vrTeleportHorizLimit = 16;
	}

	@Override
	protected void actionPerformed(GuiButton button) {
		if (button.id == VRSettings.VrOptions.LIMIT_TELEPORT.ordinal())
			this.reinit = true;
	}
}
