package org.vivecraft.gameplay.trackers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.vivecraft.api.NetworkHelper;
import org.vivecraft.api.NetworkHelper.PacketDiscriminators;
import org.vivecraft.control.VRButtonMapping;
import org.vivecraft.provider.MCOpenVR;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLadder;
import net.minecraft.block.BlockVine;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.init.MobEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.CPacketCustomPayload;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.shapes.VoxelShape;

public class ClimbTracker extends Tracker{

	private boolean[] latched = new boolean[2];
	private boolean[] wasinblock = new boolean[2];
	private boolean[] wasbutton= new boolean[2];
	private boolean[] waslatched = new boolean[2];

	public Set<Block> blocklist = new HashSet<>();

	public byte serverblockmode = 0;

	private boolean gravityOverride=false;

	public Vec3d[] latchStart = new Vec3d[]{new Vec3d(0,0,0), new Vec3d(0,0,0)};
	public Vec3d[] latchStart_room = new Vec3d[]{new Vec3d(0,0,0), new Vec3d(0,0,0)};
	public Vec3d[] latchStartBody  = new Vec3d[]{new Vec3d(0,0,0), new Vec3d(0,0,0)};

	public int latchStartController = -1;
	boolean wantjump = false;
	AxisAlignedBB box[] = new AxisAlignedBB[2];
	AxisAlignedBB latchbox[] = new AxisAlignedBB[2];
	int[] meta = new int[2];

	//						2: Ladder facing north
	//						3: Ladder facing south
	//						4: Ladder facing west
	//						5: Ladder facing east


	private AxisAlignedBB northbb= new AxisAlignedBB(.1, 0, .9,
			.9, 1, 1.1);
	private AxisAlignedBB southBB= new AxisAlignedBB(.1, 0, -.1,
			.9, 1, .1);
	private AxisAlignedBB westBB= new AxisAlignedBB(.9, 0, .1,
			1.1, 1, .9);
	private AxisAlignedBB eastBB= new AxisAlignedBB(-.1, 0, .1,
			.1, 1, .9);
	private AxisAlignedBB upBB= new AxisAlignedBB(0, .9, 0,
			1, 1.1, 1);

	public ClimbTracker(Minecraft mc) {
		super(mc);
	}

	public boolean isGrabbingLadder(){
		return latched[0] || latched[1];
	}
	
	public boolean isGrabbingLadder(int controller){
		return latched[controller];
	}

	public boolean isClaws(ItemStack i){
		if(i.isEmpty())return false;
		if(!i.hasDisplayName()) return false;
		if(i.getItem() != Items.SHEARS) return false;
		if(!(i.getTag().getBoolean("Unbreakable"))) return false;
		return i.getDisplayName().getString().equals("Climb Claws");
	}

	public boolean isActive(EntityPlayerSP p){
		if(mc.vrSettings.seated)
			return false;
		if(!mc.vrPlayer.getFreeMove() && !Minecraft.getMinecraft().vrSettings.simulateFalling)
			return false;
		if(!mc.vrSettings.realisticClimbEnabled)
			return false;
		if(p==null || p.isDead)
			return false;
		if(p.isPassenger())
			return false;
		if(!isClimbeyClimbEquipped() && p.moveForward > 0 && Minecraft.getMinecraft().vrSettings.vrFreeMove ) 
			return false;
		return true;
	}

	public boolean isClimbeyClimb(){
		if(!this.isActive(mc.player)) return false;
		return(isClimbeyClimbEquipped());
	}

	public boolean isClimbeyClimbEquipped(){
		return(NetworkHelper.serverAllowsClimbey && mc.player.isClimbeyClimbEquipped());
	}

	private Random rand = new Random(); 
	boolean unsetflag;

	private boolean canstand(BlockPos bp, EntityPlayerSP p){
		AxisAlignedBB t = p.world.getBlockState(bp).getCollisionShape(p.world, bp).getBoundingBox();
		if(t == null || t.maxY == 0)
			return false;	
		BlockPos bp1 = bp.up();	
		AxisAlignedBB a = p.world.getBlockState(bp1).getCollisionShape(p.world, bp1).getBoundingBox();
		if(a != null && a.maxY>0)
			return false;
		BlockPos bp2 = bp1.up();	
		AxisAlignedBB a1 = p.world.getBlockState(bp2).getCollisionShape(p.world, bp2).getBoundingBox();
		if(a1 != null && a1.maxY>0)
			return false;		
		return true;
	}

	@Override
	public void reset(EntityPlayerSP player) {
		latchStartController = -1;
		latched[0] = false;
		latched[1] = false;
		player.setNoGravity(false);
	}

	public void doProcess(EntityPlayerSP player){

		boolean[] button = new boolean[2];
		boolean[] inblock = new boolean[2];
		boolean[] allowed = new boolean [2];

		Vec3d[] cpos = new Vec3d[2];

		boolean nope = false;

		boolean grabbed = false;
		
		boolean jump = false;
		boolean ladder = false;
		for(int c=0;c<2;c++){	
			cpos[c] = mc.vrPlayer.vrdata_world_pre.getController(c).getPosition();
			Vec3d controllerDir = mc.vrPlayer.vrdata_world_pre.getController(c).getDirection();

			BlockPos bp = new BlockPos(cpos[c]);
			IBlockState bs = mc.world.getBlockState(bp);
			Block b = bs.getBlock();
			VoxelShape vs = bs.getCollisionShape(mc.world, bp);

			if (vs.isEmpty()) {
				box[c] = null;
			} else {
				box[c] = vs.getBoundingBox();
			}

			if(!mc.climbTracker.isClimbeyClimb()){	

				Vec3d controllerPosNear = mc.vrPlayer.vrdata_world_pre.getController(c).getPosition().subtract(controllerDir.scale(0.2));

				AxisAlignedBB conBB = new AxisAlignedBB(cpos[c], controllerPosNear);

				ladder = true;			

				boolean ok = b instanceof BlockLadder|| b instanceof BlockVine;

				if (!ok){ //check other end of conbb.
					BlockPos bp2 = new BlockPos(controllerPosNear);
					IBlockState bs2 = mc.world.getBlockState(bp2);
					Block b2 = bs2.getBlock();

					if (b2 instanceof BlockLadder|| b2 instanceof BlockVine) {
						bp = bp2;
						bs = bs2;
						b = bs2.getBlock();
						cpos[c]  = controllerPosNear;					
						VoxelShape vs2 = bs.getCollisionShape(mc.world, bp2);

						if (vs2.isEmpty()) {
							box[c] = null;
							ok = false;
						} else {
							ok = true;
							box[c] = vs2.getBoundingBox();
						}
					}
				}

				if(ok) {

					List<AxisAlignedBB> bbs = new ArrayList<AxisAlignedBB>();

					if(b instanceof BlockLadder) {
						switch(bs.get(BlockLadder.FACING)) {
						case DOWN:
							//Marilyn??
							ok = false;
							break;
						case EAST:
							bbs.add(eastBB);
							break;
						case NORTH:
							bbs.add(northbb);
							break;
						case SOUTH:
							bbs.add(southBB);
							break;
						case UP:
							//Where the shit did you find a jungle gym?
							bbs.add(upBB);
							break;
						case WEST:
							bbs.add(westBB);
							break;
						default:
							ok = false;
							break;						
						}
					}

					if(b instanceof BlockVine) {	
						ok = true;
						box[c] = new AxisAlignedBB(0, 0, 0, 1, 1, 1);

						//Not vanilla-y to allow climbing on top vines.
						//						if (bs.get(BlockVine.UP) && mc.world.getBlockState(bp.up()).isFullCube())
						//							bbs.add(upBB);

						if (bs.get(BlockVine.NORTH) && mc.world.getBlockState(bp.north()).isSolid())
							bbs.add(southBB);

						if (bs.get(BlockVine.EAST) && mc.world.getBlockState(bp.east()).isSolid())
							bbs.add(westBB);

						if (bs.get(BlockVine.SOUTH) && mc.world.getBlockState(bp.south()).isSolid())
							bbs.add(northbb);

						if (bs.get(BlockVine.WEST) && mc.world.getBlockState(bp.west()).isSolid())
							bbs.add(eastBB);
					}
					
					inblock[c] = false;

					if(ok){
						for (AxisAlignedBB bb : bbs) {
							if (conBB.intersects(bb.offset(bp))) {
								inblock[c] = true;
								if(bb == northbb)
									meta[c] = 2;
								else if (bb == southBB)
									meta[c] = 3;
								else if (bb == eastBB)
									meta[c] = 5;
								else if (bb == westBB)
									meta[c] = 4;
								break;
							}
						}
					} 
				} 
				else {
					Vec3d hdel = latchStart[c].subtract(cpos[c] );
					double dist = hdel.length();
					if(dist > 0.5f) 
						inblock[c] = false;
					else {
						BlockPos lbp = new BlockPos(latchStart[c]);
						IBlockState lbs = mc.world.getBlockState(lbp);
						inblock[c] = wasinblock[c] && lbs.getBlock() instanceof BlockLadder|| lbs.getBlock() instanceof BlockVine;
					}
				}
				
				button[c] = inblock[c];
				allowed[c] = inblock[c];

			} else { //Climbey
				//TODO whitelist by block type

				if(mc.player.onGround)
					mc.player.onGround = !latched[0] && !latched[1];

				if(c == 0)
					button[c] = mc.gameSettings.keyBindAttack.isKeyDown();
				else 
					button[c] = mc.gameSettings.keyBindForward.isKeyDown() && !mc.player.onGround;

				inblock[c] = box[c] != null && box[c].offset(bp).contains(cpos[c]);

				if(!inblock[c]){
					Vec3d hdel = latchStart[c].subtract(cpos[c] );
					double dist = hdel.length();
					if(dist > 0.5f) 
						button[c] = false;
				}


				allowed[c] = allowed(bs);
			}						

			waslatched[c] = latched[c];

			if(!button[c] && latched[c]){ //let go 
				latched[c] = false;
				if(c == 0)
					VRButtonMapping.unpressKey(mc.gameSettings.keyBindAttack);
				else 
					VRButtonMapping.unpressKey(mc.gameSettings.keyBindForward);

				jump = true;
			} 

			if(!latched[c] && !nope){ //grab
				if((!wasinblock[c] && inblock[c] && button[c]) ||
						(!wasbutton[c] && button[c] && inblock[c])){ //Grab
					if(allowed[c]){
						grabbed = true;
						wantjump = false;
						latchStart[c] = cpos[c];
						latchStart_room[c] = mc.vrPlayer.vrdata_room_pre.getController(c).getPosition();
						latchStartBody[c] = player.getPositionVector();
						latchStartController = c;
						latchbox[c] = box[c];
						latched[c] = true;
						if(c==0){
							latched[1] = false;
							nope = true;
						}
						else 
							latched[0] = false;
						MCOpenVR.triggerHapticPulse(c, 2000);
						mc.player.stepSound(bp, latchStart[c]);
						if(!ladder)mc.vrPlayer.blockDust(latchStart[c].x, latchStart[c].y, latchStart[c].z, 5, bs);

					}
				}
			}		

			wasbutton[c] = button[c];
			wasinblock[c] = inblock[c];

		}

		if(!latched[0] && !latched[1]){ 
			//check in case they let go with one hand, and other hand should take over.
			for(int c=0;c<2;c++){
				if(inblock[c] && button[c] && allowed[c]){
					grabbed = true;
					latchStart[c] = cpos[c];
					latchStart_room[c] = mc.vrPlayer.vrdata_room_pre.getController(c).getPosition();
					latchStartBody[c] = player.getPositionVector();
					latchStartController = c;
					latched[c] = true;
					latchbox[c] = box[c];
					wantjump = false;
					MCOpenVR.triggerHapticPulse(c, 2000);
					BlockPos bp = new BlockPos(latchStart[c]);
					IBlockState bs = mc.world.getBlockState(bp);
					if(!ladder)mc.vrPlayer.blockDust(latchStart[c].x, latchStart[c].y, latchStart[c].z, 5, bs);
				}
			}
		}		


		if(!wantjump && !ladder) 
			wantjump = mc.gameSettings.keyBindJump.isKeyDown() && mc.jumpTracker.isClimbeyJumpEquipped();

		jump &= wantjump;

		if((latched[0] || latched[1]) && !gravityOverride) {
			unsetflag = true;
			player.setNoGravity(true);
			gravityOverride=true;
		}

		if(!latched[0] && !latched[1] && gravityOverride){
			player.setNoGravity(false);
			gravityOverride=false;
		}

		if(!latched[0] && !latched[1] && !jump){
			if(player.onGround && unsetflag){
				unsetflag = false;
				VRButtonMapping.unpressKey(mc.gameSettings.keyBindForward);
			}
			latchStartController = -1;
			return; //fly u fools
		}

		if((latched[0] || latched[1]) && rand.nextInt(20) == 10 ) {
			mc.player.addExhaustion(.1f);    
			BlockPos bp = new BlockPos(latchStart[latchStartController]);
			IBlockState bs = mc.world.getBlockState(bp);
			if(!ladder) mc.vrPlayer.blockDust(latchStart[latchStartController].x, latchStart[latchStartController].y, latchStart[latchStartController].z, 1, bs);
		}


		Vec3d now = mc.vrPlayer.vrdata_world_pre.getController(latchStartController).getPosition();
		Vec3d start = mc.vrPlayer.room_to_world_pos(latchStart_room[latchStartController], mc.vrPlayer.vrdata_world_pre);

		Vec3d delta= now.subtract(start);

		latchStart_room[latchStartController] = mc.vrPlayer.vrdata_room_pre.getController(latchStartController).getPosition();

		if(wantjump) //bzzzzzz
			MCOpenVR.triggerHapticPulse(latchStartController, 200);

		if(!jump){

			Vec3d grab = latchStart[latchStartController];

			player.motionY = 0;
			
			if(grabbed) {
				player.motionX = 0;
				player.motionZ = 0;
			}
			
			player.fallDistance = 0;


			double x = player.posX;
			double y = player.posY;
			double z = player.posZ;

			double nx = player.posX;
			double ny = player.posY;
			double nz = player.posZ;

			ny = y - delta.y;		
			BlockPos b = new BlockPos(grab);

			if(!ladder){
				nx = x - delta.x;	
				nz = z - delta.z;		
			} else {
				//BlockPos bp = new BlockPos(grab);
				//IBlockState bs = mc.world.getBlockState(bp);
				int m = meta[latchStartController];

				if(m ==2 || m== 3){ //allow sideways
					nx = x - delta.x;	
					nz = b.getZ()+0.5f;
				}
				else if(m ==4 || m == 5){ //allow sideways
					nz = z - delta.z;		
					nx = b.getX()+0.5f;
				}
			}

			double hmd = mc.vrPlayer.vrdata_room_pre.hmd.getPosition().y;	
			double con = mc.vrPlayer.vrdata_room_pre.getController(latchStartController).getPosition().y;

			//check for getting off on top
			if(!wantjump //not jumping 
					&& latchbox[latchStartController] != null //uhh why? 
					&& con <= hmd/2  // hands down below waist
					&&	latchStart[latchStartController].y > latchbox[latchStartController].maxY*0.8 + b.getY() // latched onto top 20% of block 
					){		
				Vec3d dir = mc.vrPlayer.vrdata_world_pre.hmd.getDirection().scale(0.1f);
				Vec3d hdir = new Vec3d(dir.x, 0, dir.z).normalize().scale(0.1); //check if free spot


				boolean ok = mc.world.isCollisionBoxesEmpty(player, player.getEntityBoundingBox()
						.offset(hdir.x,(latchbox[latchStartController].maxY + b.getY()) - player.posY , + hdir.z));
				if(ok){
					nx = player.posX + hdir.x;
					ny = latchbox[latchStartController].maxY + b.getY();
					nz = player.posZ + hdir.z;
					latchStartController = -1;
					latched[0] = false;
					latched[1] = false;
					wasinblock[0] = false;
					wasinblock[1] = false;
					player.setNoGravity(false);
				}
			}

			boolean free = false;

			for (int i = 0; i < 8; i++) {
				double ax = nx;
				double ay = ny;
				double az = nz;

				switch (i) {
				case 1:
					break;
				case 2:
					ay = y;
					break;
				case 3:
					az = z;
					break;
				case 4:
					ax = x;
					break;
				case 5:
					ax = x;
					az = z;
					break;
				case 6:
					ax = x;
					ay = y;
					break;
				case 7:
					ay = y;
					az = z;
					break;
				default:
					break;
				}
				player.setPosition(ax, ay, az);
				AxisAlignedBB bb = player.getEntityBoundingBox();
				free = mc.world.isCollisionBoxesEmpty(player,bb);
				if(free) {
					if( i > 1){
						MCOpenVR.triggerHapticPulse(0, 100); //ouch!
						MCOpenVR.triggerHapticPulse(1, 100);
					}
					break;
				}
			}

			if(!free) {
				player.setPosition(x, y, z);
				MCOpenVR.triggerHapticPulse(0, 100); //ouch!
				MCOpenVR.triggerHapticPulse(1, 100);
			}

			if(mc.isIntegratedServerRunning()) { //handle server falling.
				for (EntityPlayerMP p : mc.getIntegratedServer().getPlayerList().getPlayers()) {
					if (p.getEntityId() == mc.player.getEntityId())
						p.fallDistance = 0;
				}
			} else {
				CPacketCustomPayload pack =	NetworkHelper.getVivecraftClientPacket(PacketDiscriminators.CLIMBING, new byte[]{});
				if(mc.getConnection() !=null)
					mc.getConnection().sendPacket(pack);
			}

		} else { //jump!
			wantjump = false;
			Vec3d pl = player.getPositionVector().subtract(delta);

			Vec3d m = MCOpenVR.controllerHistory[latchStartController].netMovement(0.3);

			float limit = 1f;
			if(m.length() > limit) m = m.scale(limit/m.length());

			if (player.isPotionActive(MobEffects.JUMP_BOOST))
				m=m.scale((player.getActivePotionEffect(MobEffects.JUMP_BOOST).getAmplifier() + 1.5));

			m=m.rotateYaw(mc.vrPlayer.vrdata_world_pre.rotation_radians);

			player.motionX=-m.x;
			player.motionY=-m.y;
			player.motionZ=-m.z;

			player.lastTickPosX = pl.x;
			player.lastTickPosY = pl.y;
			player.lastTickPosZ = pl.z;			
			pl = pl.add(player.motionX, player.motionY, player.motionZ);					
			player.setPosition(pl.x, pl.y, pl.z);
			mc.vrPlayer.snapRoomOriginToPlayerEntity(player, false, false);	
			mc.player.addExhaustion(.3f);    

		}

	}

	private boolean allowed(IBlockState bs) {
		if (serverblockmode == 0) { // none
			return true;
		} else if (serverblockmode == 1) { // whitelist
			return blocklist.contains(bs.getBlock());
		} else if (serverblockmode == 2) { // blacklist
			return !blocklist.contains(bs.getBlock());
		}
		return false; //how did u get here?
	}
}
