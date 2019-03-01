package org.vivecraft.gameplay;

import java.util.ArrayList;
import java.util.Random;

import org.vivecraft.api.NetworkHelper;
import org.vivecraft.api.NetworkHelper.PacketDiscriminators;
import org.vivecraft.api.VRData;
import org.vivecraft.control.VRButtonMapping;
import org.vivecraft.gameplay.screenhandlers.GuiHandler;
import org.vivecraft.gameplay.screenhandlers.KeyboardHandler;
import org.vivecraft.gameplay.screenhandlers.RadialHandler;
import org.vivecraft.gameplay.trackers.BowTracker;
import org.vivecraft.gameplay.trackers.Tracker;
import org.vivecraft.provider.MCOpenVR;
import org.vivecraft.settings.AutoCalibration;
import org.vivecraft.settings.VRSettings;
import org.vivecraft.utils.InputSimulator;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiWinGame;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.passive.AbstractHorse;
import net.minecraft.init.Particles;
import net.minecraft.item.ItemEgg;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemSnowball;
import net.minecraft.item.ItemSpawnEgg;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.CPacketCustomPayload;
import net.minecraft.particles.BlockParticleData;
import net.minecraft.util.Util;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceFluidMode;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;


// VIVE
public class OpenVRPlayer 
{
	Minecraft mc = Minecraft.getMinecraft();

	//loop start
	public VRData vrdata_room_pre; //just latest polling data, origin = 0,0,0, rotation = 0, scaleXZ = walkMultiplier
	//handle server messages
	public VRData vrdata_world_pre; //latest polling data but last tick's origin, rotation, scale
	//tick here
	public VRData vrdata_room_post; //recalc here in the odd case the walk multiplier changed
	public VRData vrdata_world_post; //this is used for rendering and the server. _render is interpolated between this and _pre.
	//interpolate here between post and pre
	public VRData vrdata_world_render; // using interpolated origin, scale, rotation
	//loop end
	
	private long errorPrintTime = Util.milliTime();

	ArrayList<Tracker> trackers=new ArrayList<>();
	public void registerTracker(Tracker tracker){
		trackers.add(tracker);
	}

	public OpenVRPlayer() {
		this.vrdata_room_pre = new VRData(new Vec3d(0, 0, 0), mc.vrSettings.walkMultiplier, 1, 0);
		this.vrdata_room_post = new VRData(new Vec3d(0, 0, 0), mc.vrSettings.walkMultiplier, 1, 0);
		this.vrdata_world_post = new VRData(new Vec3d(0, 0, 0), mc.vrSettings.walkMultiplier, 1, 0);
		this.vrdata_world_pre = new VRData(new Vec3d(0, 0, 0), mc.vrSettings.walkMultiplier, 1, 0);
	}

	public float worldScale =  Minecraft.getMinecraft().vrSettings.vrWorldScale;
	public boolean noTeleportClient = true;

	public Vec3d roomOrigin = new Vec3d(0,0,0);
	private boolean isFreeMoveCurrent = true; // true when connected to another server that doesn't have this mod

	//for overriding the world scale settings with wonder foods.
	public double wfMode = 0;
	public int wfCount = 0;
	//

	private int roomScaleMovementDelay = 0;
	public float vrot = 0;
	boolean initdone =false;
	
	public static OpenVRPlayer get()
	{
		return Minecraft.getMinecraft().vrPlayer;
	}

	public Vec3d room_to_world_pos(Vec3d pos, VRData data){
		Vec3d out = new Vec3d(pos.x*data.worldScale, pos.y*data.worldScale, pos.z*worldScale);
		out =out.rotateYaw(data.rotation);
		return out.add(data.origin.x, data.origin.y, data.origin.z);
	}

	public Vec3d world_to_room_pos(Vec3d pos, VRData data){
		Vec3d out = pos.add(-data.origin.x, -data.origin.y, -data.origin.z);
		out = new Vec3d(out.x/data.worldScale, out.y/data.worldScale, out.z/data.worldScale);
		return out.rotateYaw(-data.rotation);
	}

	public void postPoll(){
		this.vrdata_room_pre = new VRData(new Vec3d(0, 0, 0), mc.vrSettings.walkMultiplier, 1, 0);
		GuiHandler.processGui();
		KeyboardHandler.processGui();
		RadialHandler.processGui();
	}

	public void preTick(){
		
		this.vrdata_world_pre = new VRData(this.roomOrigin, mc.vrSettings.walkMultiplier, worldScale, (float) Math.toRadians(mc.vrSettings.vrWorldRotation));

		if(mc.vrSettings.seated && !mc.entityRenderer.isInMenuRoom())
			mc.vrSettings.vrWorldRotation = MCOpenVR.seatedRot;

		//Vivecraft - setup the player entity with the correct view for the logic tick.
		doLookOverride(vrdata_world_pre);
		////

	}

	public void postTick(){
		Minecraft mc = Minecraft.getMinecraft();
		this.vrdata_room_post = new VRData(new Vec3d(0, 0, 0), mc.vrSettings.walkMultiplier, 1, 0);
		this.vrdata_world_post = new VRData(this.roomOrigin, mc.vrSettings.walkMultiplier, worldScale, (float) Math.toRadians(mc.vrSettings.vrWorldRotation));

		//Vivecraft - setup the player entity with the correct view for the logic tick.
		doLookOverride(vrdata_world_post);
		////

		NetworkHelper.sendVRPlayerPositions(this);

	}

	public void preRender(float par1){
		Minecraft mc = Minecraft.getMinecraft();

		//do some interpolatin'

		float interpolatedWorldScale = vrdata_world_post.worldScale*par1 + vrdata_world_pre.worldScale*(1-par1);

		float end = vrdata_world_post.rotation;
		float start = vrdata_world_pre.rotation;

		float difference = Math.abs(end - start);

		if (difference > Math.PI)
			if (end > start)
				start += 2*Math.PI;
			else
				end += 2*Math.PI;

		float interpolatedWorldRotation_Radians = (float) (end*par1 + start*(1-par1));
		//worldRotationRadians += 0.01;
		Vec3d interPolatedRoomOrigin = new Vec3d(
				vrdata_world_pre.origin.x + (vrdata_world_post.origin.x - vrdata_world_pre.origin.x) * (double)par1,
				vrdata_world_pre.origin.y + (vrdata_world_post.origin.y - vrdata_world_pre.origin.y) * (double)par1,
				vrdata_world_pre.origin.z + (vrdata_world_post.origin.z - vrdata_world_pre.origin.z) * (double)par1
				);

		//System.out.println(vrdata_world_post.origin.x + " " + vrdata_world_pre.origin.x + " = " + interPolatedRoomOrigin.x);

		this.vrdata_world_render = new VRData(interPolatedRoomOrigin, mc.vrSettings.walkMultiplier, interpolatedWorldScale, interpolatedWorldRotation_Radians);

		//handle special items
		for (Tracker tracker : trackers) {
			if (tracker.getEntryPoint() == Tracker.EntryPoint.SPECIAL_ITEMS) {
				if (tracker.isActive(mc.player)){
					tracker.doProcess(mc.player);
				}else{
					tracker.reset(mc.player);
				}
			}
		}


	}

	public void postRender(float par1){
		//insurance.
		//vrdata_room_pre = null;
		//vrdata_world_pre = null;
		//vrdata_room_post = null; 
		//vrdata_world_post = null; //This has to be available for the integrated server. TODO: use network api.
		vrdata_world_render = null; 		
	}


	public void setRoomOrigin(double x, double y, double z, boolean reset) { 

		if(!reset && vrdata_world_render != null){
			if (Util.milliTime() - errorPrintTime >= 1000) { // Only print once per second, since this might happen every frame
				System.out.println("Vivecraft Warning: Room origin set too late! Printing call stack:");
				Thread.dumpStack();
				errorPrintTime = Util.milliTime();
			}
			return;
		}

		if (reset){
			if(vrdata_world_pre!=null)
				vrdata_world_pre.origin = new Vec3d(x, y, z);
		}
		roomOrigin = new Vec3d(x, y, z);
	} 

	//set room 
	public void snapRoomOriginToPlayerEntity(EntityPlayerSP player, boolean reset, boolean instant)
	{
		if (Thread.currentThread().getName().equals("Server thread"))
			return;

		if(player.posX == 0 && player.posY == 0 &&player.posZ == 0) return;

		Minecraft mc = Minecraft.getMinecraft();

		VRData temp = vrdata_world_pre;

		if(instant) temp = new VRData(roomOrigin, mc.vrSettings.walkMultiplier, this.worldScale, (float) Math.toRadians(mc.vrSettings.vrWorldRotation));

		Vec3d campos = temp.hmd.getPosition().subtract(temp.origin);

		double x,y,z;

		x = player.posX - campos.x;
		z = player.posZ - campos.z;
		y = player.posY;

		
		if(player.isPassenger()){
			Entity e= player.getRidingEntity();
			x = e.posX - campos.x;
			y = e.posY;
			z = e.posZ - campos.z;
		}

		//System.out.println("Snap " + player.posX + " " + player.posY + " " + player.posZ);
		setRoomOrigin(x, y, z, reset);
	}


	public float rotDiff_Degrees(float start, float end){ //calculate shortest difference between 2 angles.
		start = start % 360;
		end = end % 360;

		if (Math.abs(end - start) > 180)
			if (end > start)
				start += 360;
			else
				end += 360;

		return end - start;
	}

	private boolean cartFlip, wasRiding;

	public void checkandUpdateRotateScale(){
		Minecraft mc = Minecraft.getMinecraft();
		if (mc.world == null)
			this.worldScale = 1.0f;
		if (mc.player == null || mc.player.initFromServer == false)
			return;

		if (mc.currentScreen instanceof GuiWinGame) {
			this.worldScale = 1.0f;
		} else if (this.wfCount > 0 && !mc.isGamePaused()) {
			if(this.wfCount < 40){
				this.worldScale-=this.wfMode / 2;
				if(this.worldScale >  mc.vrSettings.vrWorldScale && this.wfMode <0) this.worldScale = mc.vrSettings.vrWorldScale;
				if(this.worldScale <  mc.vrSettings.vrWorldScale && this.wfMode >0) this.worldScale = mc.vrSettings.vrWorldScale;
			} else {
				this.worldScale+=this.wfMode / 2;
				if(this.worldScale >  mc.vrSettings.vrWorldScale*20) this.worldScale = 20;
				if(this.worldScale <  mc.vrSettings.vrWorldScale/10) this.worldScale = 0.1f;				
			}
			this.wfCount--;
		} else {
			this.worldScale = mc.vrSettings.vrWorldScale;
		}

		//handle changes up to this point (menu, buttons, seated)
		VRData testbefore = new VRData(roomOrigin, mc.vrSettings.walkMultiplier, this.worldScale, vrdata_world_pre.rotation);		
		float end = mc.vrSettings.vrWorldRotation;
		float start = (float) Math.toDegrees(vrdata_world_pre.rotation);
		rotateOriginAround(-end+start, testbefore.hmd.getPosition());
		//

		if(!mc.isGamePaused())
		{ //do vehicle rotation, which rotates around a different point.
			if(mc.vrSettings.vehicleRotation && mc.player.isPassenger() && wasRiding){
				Entity e = mc.player.getRidingEntity();		
				end = e.rotationYaw;

				if (e instanceof AbstractHorse && !mc.horseTracker.isActive(mc.player)) {
					AbstractHorse el = (AbstractHorse) e;
					end = el.renderYawOffset;
					if (el.canBeSteered() && el.isHorseSaddled()){
						return;
					}
				}else if (e instanceof EntityLiving) {
					EntityLiving el = (EntityLiving) e; //this is just pigs in vanilla
					end = el.renderYawOffset;
					if (el.canBeSteered()){
						return; 
					}
				}

				start = vrot;			

				if(e instanceof EntityMinecart){ //what a pain in my ass
					end = getMinecartRenderYaw(e);
					if (Math.abs(rotDiff_Degrees(end, start)) > 155){ // the thing just flipped
						cartFlip =! cartFlip;
						end = (end + 180) % 360;
					}
				}

				float difference = rotDiff_Degrees(start, end);

				rotateOriginAround(difference,  e.getPositionVector());

				mc.vrSettings.vrWorldRotation -= difference;
				mc.vrSettings.vrWorldRotation %= 360;
				MCOpenVR.seatedRot = mc.vrSettings.vrWorldRotation;
				///uhh i dont like this at alll.

				if(cartFlip)
					vrot = (end + 180) % 360;
				else
					vrot = end;

			} else {
				cartFlip =false;
//				if(wasRiding){		
//					vrot = mc.player.getRidingEntity().rotationYaw;				
//					if(mc.player.getRidingEntity() instanceof EntityMinecart){ 
//						vrot = getMinecartRenderYaw(mc.player.getRidingEntity());
//					}
//				}
			}
		}
	}

	public void rotateOriginAround(float degrees, Vec3d o){
		Vec3d pt = roomOrigin;


		float rads = (float) Math.toRadians(degrees); //reverse rotate.

		if(rads!=0)
			setRoomOrigin(
					Math.cos(rads) * (pt.x-o.x) - Math.sin(rads) * (pt.z-o.z) + o.x,
					pt.y,
					Math.sin(rads) * (pt.x-o.x) + Math.cos(rads) * (pt.z-o.z) + o.z
					,false);

		VRData test = new VRData(roomOrigin, mc.vrSettings.walkMultiplier, this.worldScale, (float) Math.toRadians(mc.vrSettings.vrWorldRotation));

		Vec3d b = vrdata_world_pre.hmd.getPosition();
		Vec3d a = test.hmd.getPosition();
		double dist = b.distanceTo(a); //should always be 0 (unless in a vehicle)   		
	}

	private float getMinecartRenderYaw(Entity entity){
		Vec3d vec3d = entity.getPositionVector();
		EntityMinecart m = (EntityMinecart) entity;
		if (vec3d != null)
		{
			Vec3d vec3d1 = m.getPosOffset(vec3d.x, vec3d.y, vec3d.z, 0.30000001192092896D);
			Vec3d vec3d2 = m.getPosOffset(vec3d.x, vec3d.y, vec3d.z, -0.30000001192092896D);

			if (vec3d1 == null)	vec3d1 = vec3d;
			if (vec3d2 == null)	vec3d2 = vec3d;

			Vec3d vec3d3 = vec3d2.subtract(vec3d1);

			Vec3d spd = new Vec3d(entity.posX - entity.lastTickPosX, entity.posY - entity.lastTickPosY, entity.posZ - entity.lastTickPosZ);
			boolean flip = false;
			if(vec3d3.dotProduct(spd) < 0){
				vec3d3 = vec3d1.subtract(vec3d2);
				flip = true;
			}

			if (vec3d3.length() != 0.0D)
			{
				vec3d3 = vec3d3.normalize();
				float out = (float)Math.toDegrees((Math.atan2(-vec3d3.x, vec3d3.z)));
				return out;
			}

		}

		return vrot;
	}

	public void onLivingUpdate(EntityPlayerSP player, Minecraft mc, Random rand)
	{
		if(!player.initFromServer) return;

		if(!initdone){

			System.out.println("<Debug info start>");
			System.out.println("Room object: "+this);
			System.out.println("Room origin: " + vrdata_world_pre.origin);
			System.out.println("Hmd position room: " + vrdata_room_pre.hmd.getPosition());
			System.out.println("Hmd position world: " + vrdata_world_pre.hmd.getPosition());
			System.out.println("<Debug info end>");

			initdone =true;
		}

		AutoCalibration.logHeadPos(MCOpenVR.hmdPivotHistory.latest());

		doPlayerMoveInRoom(player);

		for (Tracker tracker : trackers) {
			if (tracker.getEntryPoint() == Tracker.EntryPoint.LIVING_UPDATE) {
				if (tracker.isActive(mc.player)){
					tracker.doProcess(mc.player);
				}else{
					tracker.reset(mc.player);
				}
			}
		}

		if(mc.vrSettings.vrAllowCrawling){         
			//experimental
			//           topofhead = (double) (mc.vrPlayer.getHMDPos_Room().y + .05);
			//           
			//           if(topofhead < .5) {topofhead = 0.5f;}
			//           if(topofhead > 1.8) {topofhead = 1.8f;}
			//           
			//           player.height = (float) topofhead - 0.05f;
			//           player.spEyeHeight = player.height - 1.62f;
			//           player.boundingBox.setMaxY( player.boundingBox.minY +  topofhead);  	   
		} else {
			//    	   player.height = 1.8f;
			//    	   player.spEyeHeight = 0.12f;
		}
		wasRiding = player.isPassenger();
		if(player.isPassenger()){
			Entity e = mc.player.getRidingEntity();		
			if (e instanceof AbstractHorse) {
				AbstractHorse el = (AbstractHorse) e;
				if (el.canBeSteered() && el.isHorseSaddled() && !mc.horseTracker.isActive((EntityPlayerSP)mc.player)){
					  el.renderYawOffset = vrdata_world_pre.getBodyYaw();
				}
			}else if (e instanceof EntityLiving) {
				EntityLiving el = (EntityLiving) e; //this is just pigs in vanilla
				if (el.canBeSteered()){
					el.renderYawOffset = vrdata_world_pre.getBodyYaw();
				}
			}
		}

		mc.profiler.endSection();
	}

	public void doPlayerMoveInRoom(EntityPlayerSP player){

		if(roomScaleMovementDelay > 0){
			roomScaleMovementDelay--;
			return;
		}
		Minecraft mc = Minecraft.getMinecraft();
		if(player == null) return;
		if(player.isSneaking()) {return;} //jrbudda : prevent falling off things or walking up blocks while moving in room scale.
		if(player.isPlayerSleeping()) return; //
		if(mc.jumpTracker.isjumping()) return; //
		if(mc.climbTracker.isGrabbingLadder()) return; //
		if(player.isDead) return; //
		
		VRData temp = new VRData(this.roomOrigin, mc.vrSettings.walkMultiplier, worldScale, (float) Math.toRadians(mc.vrSettings.vrWorldRotation));
		//if(Math.abs(player.motionX) > 0.01) return;
		//if(Math.abs(player.motionZ) > 0.01) return;

		float playerHalfWidth = player.width / 2.0F;

		// move player's X/Z coords as the HMD moves around the room

		//OK this is the first place I've found where we reallly need to update the VR data before doing this calculation.

		Vec3d eyePos = temp.hmd.getPosition();

		double x = eyePos.x;
		double y = player.posY;
		double z = eyePos.z;

		// create bounding box at dest position
		AxisAlignedBB bb = new AxisAlignedBB(
				x - (double) playerHalfWidth,
				y,
				z - (double) playerHalfWidth,
				x + (double) playerHalfWidth,
				y + (double) player.height,
				z + (double) playerHalfWidth);

		Vec3d torso = null;

		if(wasRiding) {
			if (mc.world.getEntitiesWithinAABBExcludingEntity(player, bb).isEmpty()) {
				mc.sneakTracker.sneakCounter = 5;
			}	
			return; 
		}
		
		// valid place to move player to?
		float var27 = 0.0625F;
		boolean emptySpot = mc.world.isCollisionBoxesEmpty(player, bb);
		
		if (emptySpot)
		{


			// don't call setPosition style functions to avoid shifting room origin
			player.posX = x;
			if (!mc.vrSettings.simulateFalling)	{
				player.posY = y;                	
			}
			player.posZ = z;

			player.setEntityBoundingBox(new AxisAlignedBB(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.minY + player.height, bb.maxZ));
			player.fallDistance = 0.0F;

			torso = getEstimatedTorsoPosition(x, y, z);
		}

		//test for climbing up a block
		else if ((mc.vrSettings.walkUpBlocks || (mc.climbTracker.isGrabbingLadder() && mc.vrSettings.realisticClimbEnabled)) && player.fallDistance == 0)
		{
			if (torso == null)
			{
				torso = getEstimatedTorsoPosition(x, y, z);
			}

			// is the player significantly inside a block?
			float climbShrink = player.width * 0.45f;
			double shrunkClimbHalfWidth = playerHalfWidth - climbShrink;
			AxisAlignedBB bbClimb = new AxisAlignedBB(
					torso.x - shrunkClimbHalfWidth,
					bb.minY,
					torso.z - shrunkClimbHalfWidth,
					torso.x + shrunkClimbHalfWidth,
					bb.maxY,
					torso.z + shrunkClimbHalfWidth);

			boolean iscollided = !mc.world.isCollisionBoxesEmpty(player, bbClimb);

			if(iscollided){
				double xOffset = torso.x - x;
				double zOffset = torso.z - z;

				bb = bb.offset(xOffset, 0, zOffset);

				int extra = 0;
				if(player.isOnLadder() && mc.vrSettings.realisticClimbEnabled)
					extra = 6;

				for (int i = 0; i <=10+extra ; i++)
				{
					bb = bb.offset(0, .1, 0);

					emptySpot = mc.world.isCollisionBoxesEmpty(player, bb);
					if (emptySpot)
					{
						x += xOffset;  	
						z += zOffset;
						y += 0.1f*i;

						player.posX = x;
						player.posY = y;
						player.posZ = z;

						player.setEntityBoundingBox(new AxisAlignedBB(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ));

						Vec3d dest  = roomOrigin.add(xOffset, 0.1f*i, zOffset);

						setRoomOrigin(dest.x, dest.y, dest.z, false);

						Vec3d look = player.getLookVec();
						Vec3d forward = new Vec3d(look.x,0,look.z).normalize();
						player.fallDistance = 0.0F;
						mc.player.stepSound(new BlockPos(player.getPositionVector()), player.getPositionVector());

						break;
					}
				}
			}
		}
	}




	// use simple neck modeling to estimate torso location
	public Vec3d getEstimatedTorsoPosition(double x, double y, double z)
	{
		Entity player = Minecraft.getMinecraft().player;
		Vec3d look = player.getLookVec();
		Vec3d forward = new Vec3d(look.x, 0, look.z).normalize();
		float factor = (float)look.y * 0.25f;
		Vec3d torso = new Vec3d(
				x + forward.x * factor,
				y + forward.y * factor,
				z + forward.z * factor);

		return torso;
	}




	public void blockDust(double x, double y, double z, int count, IBlockState bs){
		Random rand = new Random();
		for (int i = 0; i < count; ++i)
		{
			
			Minecraft.getMinecraft().world.spawnParticle(
					new BlockParticleData(Particles.BLOCK, bs), 
					x+ ((double)rand.nextFloat() - 0.5D)*.02f,
					y + ((double)rand.nextFloat() - 0.5D)*.02f,
					z + ((double)rand.nextFloat()- 0.5D)*.02f,
					((double)rand.nextFloat()- 0.5D)*.1f,
					((double)rand.nextFloat()- 0.5D)*.05f,
					((double)rand.nextFloat()- 0.5D)*.1f
					);    
			 	
		}
	}



	public boolean getFreeMove() { return isFreeMoveCurrent; }

	public void setFreeMove(boolean free) { 
		boolean was = isFreeMoveCurrent;
		isFreeMoveCurrent = free;

		if(free != was){
			CPacketCustomPayload pack =	NetworkHelper.getVivecraftClientPacket(PacketDiscriminators.MOVEMODE, isFreeMoveCurrent ?  new byte[]{1} : new byte[]{0});

			if(Minecraft.getMinecraft().getConnection() !=null)
				Minecraft.getMinecraft().getConnection().sendPacket(pack);

			if(Minecraft.getMinecraft().vrSettings.seated){
				Minecraft.getMinecraft().printChatMessage("Movement mode set to: " + (free ? "Free Move: WASD": "Teleport: W"));

			} else {
				Minecraft.getMinecraft().printChatMessage("Movement mode set to: " + (free ? Minecraft.getMinecraft().vrSettings.getButtonDisplayString(VRSettings.VrOptions.FREEMOVE_MODE): "Teleport"));

			}

			if(noTeleportClient && !free){
				Minecraft.getMinecraft().printChatMessage("Warning: This server may not allow teleporting.");
			}

		}
	}



	//	public Vec3d getWalkMultOffset(){
	//		EntityPlayerSP player = Minecraft.getMinecraft().player;
	//		if(player==null || !player.initFromServer)
	//			return Vec3d.ZERO;
	//		float walkmult=Minecraft.getMinecraft().vrSettings.walkMultiplier;
	//		Vec3d pos=vecMult(MCOpenVR.getCenterEyePosition(),interpolatedWorldScale);
	//		return new Vec3d(pos.x*walkmult,pos.y,pos.z*walkmult).subtract(pos);
	//	}


	//	//leave these for now
	//	public FloatBuffer getHMDMatrix_World() {
	//		Matrix4f out = MCOpenVR.hmdRotation;
	//		Matrix4f rot;
	//		
	//		if(vrdata_world_render != null)
	//		 rot = Matrix4f.rotationY(vrdata_world_render.rotation);
	//		else
	//		 rot = Matrix4f.rotationY(vrdata_world_pre.rotation);
	//
	//		return Matrix4f.multiply(rot, out).toFloatBuffer();
	//	}	
	//	
	//	public FloatBuffer getControllerMatrix_World(int controller) {
	//		Matrix4f out = MCOpenVR.getAimRotation(controller);
	//		Matrix4f rot = Matrix4f.rotationY(vrdata_world_post.rotation);
	//		return Matrix4f.multiply(rot,out).toFloatBuffer();
	//	}
	//	
	//	public FloatBuffer getControllerMatrix_World_Transposed(int controller) {
	//		Matrix4f out = MCOpenVR.getAimRotation(controller);
	//		Matrix4f rot;
	//		
	//		if(vrdata_world_render != null)
	//		 rot = Matrix4f.rotationY(vrdata_world_render.rotation);
	//		else
	//		 rot = Matrix4f.rotationY(vrdata_world_pre.rotation);
	//		
	//		return Matrix4f.multiply(rot,out).transposed().toFloatBuffer();
	//	}



	@Override
	public String toString() {
		return "VRPlayer: " +
				"\r\n \t origin: " + this.roomOrigin +
				"\r\n \t rotation: " + String.format("%.3f", Minecraft.getMinecraft().vrSettings.vrWorldRotation) +
				"\r\n \t scale: " + String.format("%.3f", this.worldScale) + 
				"\r\n \t room_pre " + this.vrdata_room_pre + 
				"\r\n \t world_pre " + this.vrdata_world_pre + 
				"\r\n \t world_post " + this.vrdata_world_post + 
				"\r\n \t world_render " + this.vrdata_world_render ;	
	}


	public void doLookOverride(VRData data){
		EntityPlayerSP entity = this.mc.player;
		if(entity == null)return;
		//This is used for all sorts of things both client and server side.

		if(false){  //hmm, to use HMD? literally never.
			//set model view direction to camera
			//entity.rotationYawHead = entity.rotationYaw = (float)mc.vrPlayer.getHMDYaw_World();
			//entity.rotationPitch = (float)mc.vrPlayer.getHMDPitch_World();
		} else { //default to looking 'at' the crosshair position.
			if(mc.entityRenderer.crossVec != null){
				Vec3d playerToCrosshair = entity.getEyePosition(1).subtract(mc.entityRenderer.crossVec); //backwards
				double what = playerToCrosshair.y/playerToCrosshair.length();
				if(what > 1) what = 1;
				if(what < -1) what = -1;
				float pitch = (float)Math.toDegrees(Math.asin(what));
				float yaw = (float)Math.toDegrees(Math.atan2(playerToCrosshair.x, -playerToCrosshair.z));    
				entity.rotationYaw = entity.rotationYawHead = yaw;
				entity.rotationPitch = pitch;
			}
		}
		
		ItemStack i = ((EntityPlayerSP) entity).inventory.getCurrentItem();

		if((entity.isSprinting() && entity.movementInput.jump) || entity.isElytraFlying() || (entity.isSwimming() && entity.moveForward > 0) || (entity.isPassenger() && entity.moveForward > 0)){
			//needed for server side movement.
			if(mc.vrSettings.vrFreeMoveMode == mc.vrSettings.FREEMOVE_HMD ){
				entity.rotationYawHead = entity.rotationYaw = data.hmd.getYaw();
				entity.rotationPitch = -data.hmd.getPitch();
			}else{
				entity.rotationYawHead = entity.rotationYaw = data.getController(1).getYaw();
				entity.rotationPitch = -data.getController(1).getPitch();
			}
		} else if(i.getItem() instanceof ItemSnowball ||
				i.getItem() instanceof ItemEgg ||
				i.getItem() instanceof ItemSpawnEgg ||
				i.getItem() instanceof ItemPotion
				) { //TODO: Check for others?
			//use r_hand aim
			entity.rotationYawHead = entity.rotationYaw =  data.getController(0).getYaw();
			entity.rotationPitch = -data.getController(0).getPitch();
		} else if (BowTracker.isHoldingBowEither(entity) && mc.bowTracker.isNotched()){
			//use bow aim
			Vec3d aim = mc.bowTracker.getAimVector(); //this is actually reversed
			if (aim != null && aim.lengthSquared() > 0) {
				float pitch = (float)Math.toDegrees(Math.asin(aim.y/aim.length()));
				float yaw = (float)Math.toDegrees(Math.atan2(aim.x, -aim.z));   		
				entity.rotationYaw = (float)yaw;
				entity.rotationPitch = (float)pitch;
				entity.rotationYawHead = yaw;	
			}
		}	

		if(mc.swingTracker.shouldIlookatMyHand[0]){
			Vec3d playerToMain = entity.getEyePosition(1).subtract(data.getController(0).getPosition()); //backwards
			float pitch =(float)Math.toDegrees(Math.asin(playerToMain.y/playerToMain.length()));
			float yaw = (float)Math.toDegrees(Math.atan2(playerToMain.x,-playerToMain.z));    
			entity.rotationYawHead  = entity.rotationYaw = yaw;
			entity.rotationPitch = pitch;
		}
		else if(mc.swingTracker.shouldIlookatMyHand[1]){
			Vec3d playerToMain = entity.getEyePosition(1).subtract(data.getController(1).getPosition()); //backwards
			float pitch = (float)Math.toDegrees(Math.asin(playerToMain.y/playerToMain.length()));
			float yaw = (float)Math.toDegrees(Math.atan2(playerToMain.x, -playerToMain.z));    
			entity.rotationYawHead  = entity.rotationYaw = yaw;
			entity.rotationPitch = pitch;
		}
	}

	public Vec3d AimedPointAtDistance(VRData source, int controller, double distance) {
        Vec3d vec3d = source.getController(controller).getPosition();
        Vec3d vec3d1 = source.getController(controller).getDirection();
        return vec3d.add(vec3d1.x * distance, vec3d1.y * distance, vec3d1.z * distance);
	}
	
    public RayTraceResult rayTraceVR(VRData source, int controller, double blockReachDistance, RayTraceFluidMode p_174822_4_)
    {
        Vec3d vec3d = source.getController(controller).getPosition();
        Vec3d vec3d2 = AimedPointAtDistance(source, controller, blockReachDistance);
        return mc.world.rayTraceBlocks(vec3d, vec3d2, p_174822_4_, false, true);
    }
}

