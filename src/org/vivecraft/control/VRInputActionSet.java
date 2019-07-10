package org.vivecraft.control;

import net.minecraft.client.settings.KeyBinding;

public enum VRInputActionSet {
	INGAME("/actions/ingame", "vivecraft.actionset.ingame", "leftright", false),
	GUI("/actions/gui", "vivecraft.actionset.gui", "leftright", false),
	GLOBAL("/actions/global", "vivecraft.actionset.global", "leftright", false),
	CLIMBEY("/actions/climbey", "vivecraft.actionset.climbey", "single", true),
	KEYBOARD("/actions/keyboard", "vivecraft.actionset.keyboard", "single", true),
	MIXED_REALITY("/actions/mixedreality", "vivecraft.actionset.mixedReality", "single", true),
	TECHNICAL("/actions/technical", "vivecraft.actionset.technical", "leftright", true);

	public final String name;
	public final String localizedName;
	public final String usage;
	public final boolean advanced;

	VRInputActionSet(String name, String localizedName, String usage, boolean advanced) {
		this.name = name;
		this.localizedName = localizedName;
		this.usage = usage;
		this.advanced = advanced;
	}

	public static VRInputActionSet fromKeyBinding(KeyBinding keyBinding) {
		switch (keyBinding.getKeyCategory()) {
			case "vivecraft.key.category.gui":
				return GUI;
			case "vivecraft.key.category.climbey":
				return CLIMBEY;
			case "vivecraft.key.category.keyboard":
				return KEYBOARD;
			default:
				return INGAME;
		}
	}
}
