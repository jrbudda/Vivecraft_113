package org.vivecraft.api;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.vivecraft.render.PlayerModelController;
import org.vivecraft.utils.Quaternion;
import org.vivecraft.utils.lwjgl.Matrix4f;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.CPacketCustomPayload;
import net.minecraft.network.play.server.SPacketCustomPayload;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.Vec3d;

public class NetworkHelper {

	public static Map<UUID, VivePlayer> vivePlayers = new HashMap<UUID, VivePlayer>();
	
	public enum PacketDiscriminators {
		VERSION,
		REQUESTDATA,
		HEADDATA,
		CONTROLLER0DATA,
		CONTROLLER1DATA,
		WORLDSCALE,
		DRAW,
		MOVEMODE,
		UBERPACKET,
		TELEPORT,
		CLIMBING
	}
	public final static ResourceLocation channel = new ResourceLocation("vivecraft:data");
	
	public static CPacketCustomPayload getVivecraftClientPacket(PacketDiscriminators command, byte[] payload)
	{
		PacketBuffer pb = new PacketBuffer(Unpooled.buffer());
		pb.writeByte(command.ordinal());
		pb.writeBytes(payload);
        return  (new CPacketCustomPayload(channel, pb));
	}
	
	public static SPacketCustomPayload getVivecraftServerPacket(PacketDiscriminators command, byte[] payload)
	{
		PacketBuffer pb = new PacketBuffer(Unpooled.buffer());
		pb.writeByte(command.ordinal());
		pb.writeBytes(payload);
        return (new SPacketCustomPayload(channel, pb));
	}
	
	public static SPacketCustomPayload getVivecraftServerPacket(PacketDiscriminators command, String payload)
	{
		PacketBuffer pb = new PacketBuffer(Unpooled.buffer());
		pb.writeByte(command.ordinal());
		pb.writeString(payload);
        return (new SPacketCustomPayload(channel, pb));
	}
	
	
	public static boolean serverWantsData = false;
	public static boolean serverAllowsClimbey = false;
	public static boolean serverSupportsDirectTeleport = false;
	
	private static float worldScallast = 0;

	
	public static boolean isVive(EntityPlayerMP p){
		if(p == null) return false;
			if(vivePlayers.containsKey(p.getUniqueID())){
				return vivePlayers.get(p.getUniqueID()).isVR();
			}
		return false;
	}
	
	public static void sendPosData(EntityPlayerMP from) {

		VivePlayer v = vivePlayers.get(from.getUniqueID());
		if (v==null || v.isVR() == false || v.player == null || v.player.hasDisconnected()) return;

		for (VivePlayer sendTo : vivePlayers.values()) {

			if (sendTo == null || sendTo.player == null || sendTo.player.hasDisconnected())
				continue; // dunno y but just in case.

			if (v == sendTo || v.player.getEntityWorld() != sendTo.player.getEntityWorld() || v.hmdData == null || v.controller0data == null || v.controller1data == null){
				continue;
			}

			double d = sendTo.player.getPositionVector().squareDistanceTo(v.player.getPositionVector());

			if (d < 256 * 256) {
				SPacketCustomPayload pack  = getVivecraftServerPacket(PacketDiscriminators.UBERPACKET, v.getUberPacket());
				sendTo.player.connection.sendPacket(pack);
			}
		}
	}
	
}
