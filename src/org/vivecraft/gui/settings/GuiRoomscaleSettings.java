package org.vivecraft.gui.settings;

import org.vivecraft.gui.framework.GuiVROptionsBase;
import org.vivecraft.settings.VRSettings;

import net.minecraft.client.gui.GuiScreen;

public class GuiRoomscaleSettings extends GuiVROptionsBase
{
	static VRSettings.VrOptions[] roomscaleSettings = new VRSettings.VrOptions[]
			{
					VRSettings.VrOptions.WEAPON_COLLISION,
					VRSettings.VrOptions.REALISTIC_JUMP,
					VRSettings.VrOptions.ANIMAL_TOUCHING,
					VRSettings.VrOptions.REALISTIC_SNEAK,
					VRSettings.VrOptions.REALISTIC_CLIMB,
					VRSettings.VrOptions.REALISTIC_ROW,
					VRSettings.VrOptions.REALISTIC_SWIM,
					VRSettings.VrOptions.BOW_MODE,
					VRSettings.VrOptions.BACKPACK_SWITCH
					//VRSettings.VrOptions.PHYSICAL_GUI
			};

	public GuiRoomscaleSettings(GuiScreen guiScreen) {
		super( guiScreen );
	}

	@Override
	public void initGui()
	{ 	
		title = "Roomscale Interactions Settings";
		super.initGui(roomscaleSettings, true);
		super.addDefaultButtons();
	}

	@Override
	protected void loadDefaults() {
		this.settings.weaponCollision = true;
		this.settings.animaltouching = true;
		this.settings.realisticClimbEnabled = true;
		this.settings.realisticJumpEnabled = true;
		this.settings.realisticSneakEnabled = true;
		this.settings.realisticSwimEnabled = true;
		this.settings.realisticRowEnabled = true;
		this.settings.backpackSwitching = true;
	}
}
