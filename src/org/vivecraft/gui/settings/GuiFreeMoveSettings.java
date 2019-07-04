package org.vivecraft.gui.settings;

import org.vivecraft.gui.framework.GuiVROptionsBase;
import org.vivecraft.settings.VRSettings;

import net.minecraft.client.gui.GuiScreen;

public class GuiFreeMoveSettings extends GuiVROptionsBase {
	private static VRSettings.VrOptions[] standingSettings = new VRSettings.VrOptions[] {
			VRSettings.VrOptions.FREEMOVE_MODE,
			VRSettings.VrOptions.FOV_REDUCTION,
			VRSettings.VrOptions.INERTIA_FACTOR,
			VRSettings.VrOptions.MOVEMENT_MULTIPLIER,
			VRSettings.VrOptions.AUTO_SPRINT,
			VRSettings.VrOptions.AUTO_SPRINT_THRESHOLD,
			VRSettings.VrOptions.ANALOG_MOVEMENT
	};

	private static VRSettings.VrOptions[] seatedSettings = new VRSettings.VrOptions[] {
			VRSettings.VrOptions.SEATED_HMD,
			VRSettings.VrOptions.FOV_REDUCTION
	};

	public GuiFreeMoveSettings(GuiScreen guiScreen) {
		super(guiScreen);
	}

	@Override
	public void initGui()
	{
		title = "Free Move Settings";

		if (mc.vrSettings.seated)
			super.initGui(seatedSettings, true);
		else
			super.initGui(standingSettings, true);

		super.addDefaultButtons();
	}

	@Override
	protected void loadDefaults() {
		VRSettings vrSettings = mc.vrSettings;
		vrSettings.inertiaFactor = VRSettings.INERTIA_NORMAL;
		vrSettings.movementSpeedMultiplier = 1f;
		vrSettings.vrFreeMoveMode = VRSettings.FREEMOVE_CONTROLLER;
		vrSettings.useFOVReduction = false;
		vrSettings.seatedUseHMD = false;
		vrSettings.analogMovement = true;
		vrSettings.autoSprint = true;
		vrSettings.autoSprintThreshold = 0.9f;
	}
}
