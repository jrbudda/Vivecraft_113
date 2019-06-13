package org.vivecraft.provider;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.IntBuffer;
import java.util.*;

import org.vivecraft.api.Vec3History;
import org.vivecraft.control.AxisType;
import org.vivecraft.control.ButtonTuple;
import org.vivecraft.control.ButtonType;
import org.vivecraft.control.ControllerType;
import org.vivecraft.control.TrackedController;
import org.vivecraft.control.TrackedControllerOculus;
import org.vivecraft.control.TrackedControllerVive;
import org.vivecraft.control.TrackedControllerWindowsMR;
import org.vivecraft.control.VRButtonMapping;
import org.vivecraft.control.VRInputEvent;
import org.vivecraft.gameplay.screenhandlers.GuiHandler;
import org.vivecraft.gameplay.screenhandlers.KeyboardHandler;
import org.vivecraft.gameplay.screenhandlers.RadialHandler;
import org.vivecraft.gui.settings.GuiVRControls;
import org.vivecraft.render.RenderPass;
import org.vivecraft.settings.VRHotkeys;
import org.vivecraft.settings.VRSettings;
import org.vivecraft.utils.HardwareType;
import org.vivecraft.utils.InputSimulator;
import org.vivecraft.utils.MCReflection;
import org.vivecraft.utils.MenuWorldExporter;
import org.vivecraft.utils.Utils;
import org.vivecraft.utils.Vector2;
import org.vivecraft.utils.jkatvr;

import com.google.common.util.concurrent.ListenableFuture;
import com.sun.jna.Memory;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.FloatByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import de.fruitfly.ovr.structs.EulerOrient;
import de.fruitfly.ovr.structs.Matrix4f;
import de.fruitfly.ovr.structs.Quatf;
import de.fruitfly.ovr.structs.Vector3f;
import jopenvr.HmdMatrix34_t;
import jopenvr.HmdVector2_t;
import jopenvr.JOpenVRLibrary;
import jopenvr.JOpenVRLibrary.EVREventType;
import jopenvr.OpenVRUtil;
import jopenvr.RenderModel_ComponentState_t;
import jopenvr.RenderModel_ControllerMode_State_t;
import jopenvr.Texture_t;
import jopenvr.TrackedDevicePose_t;
import jopenvr.VRControllerAxis_t;
import jopenvr.VRControllerState_t;
import jopenvr.VREvent_Controller_t;
import jopenvr.VREvent_t;
import jopenvr.VRTextureBounds_t;
import jopenvr.VRTextureDepthInfo_t;
import jopenvr.VRTextureWithDepth_t;
import jopenvr.VR_IVRChaperone_FnTable;
import jopenvr.VR_IVRCompositor_FnTable;
import jopenvr.VR_IVROCSystem_FnTable;
import jopenvr.VR_IVROverlay_FnTable;
import jopenvr.VR_IVRRenderModels_FnTable;
import jopenvr.VR_IVRSettings_FnTable;
import jopenvr.VR_IVRSystem_FnTable;
import net.minecraft.block.BlockTorch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiWinGame;
import net.minecraft.client.main.Main;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovementInputFromOptions;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import org.apache.commons.lang3.ArrayUtils;
import org.lwjgl.glfw.GLFW;

public class MCOpenVR 
{
	static String initStatus;
	private static boolean initialized;
	static Minecraft mc;

	public static VR_IVRSystem_FnTable vrsystem;
	static VR_IVRCompositor_FnTable vrCompositor;
	static VR_IVROverlay_FnTable vrOverlay;
	static VR_IVRSettings_FnTable vrSettings;
	static VR_IVRRenderModels_FnTable vrRenderModels;
	static VR_IVRChaperone_FnTable vrChaperone;
	public static VR_IVROCSystem_FnTable vrOpenComposite;

	private static IntByReference hmdErrorStore = new IntByReference();
	private static IntBuffer hmdErrorStoreBuf;

	private static TrackedDevicePose_t.ByReference hmdTrackedDevicePoseReference;
	private static TrackedDevicePose_t[] hmdTrackedDevicePoses;
	private static TrackedDevicePose_t.ByReference hmdGamePoseReference;
	private static TrackedDevicePose_t[] hmdGamePoses;

	private static Matrix4f[] poseMatrices;
	private static Vec3d[] deviceVelocity;

	private LongByReference oHandle = new LongByReference();

	// position/orientation of headset and eye offsets
	private static final Matrix4f hmdPose = new Matrix4f();
	public static final Matrix4f hmdRotation = new Matrix4f();
	public static Matrix4f hmdProjectionLeftEye;
	public static Matrix4f hmdProjectionRightEye;
	
	static Matrix4f hmdPoseLeftEye = new Matrix4f();
	static Matrix4f hmdPoseRightEye = new Matrix4f();
	static boolean initSuccess = false, flipEyes = false;

	private static IntBuffer hmdDisplayFrequency;

	private static float vsyncToPhotons;
	private static double timePerFrame, frameCountRun;
	private static long frameCount;

	public static Vec3History hmdHistory = new Vec3History();
	public static Vec3History hmdPivotHistory = new Vec3History();
	public static Vec3History[] controllerHistory = new Vec3History[] { new Vec3History(), new Vec3History()};

	/**
	 * Do not make this public and reference it! Call the {@link #getHardwareType()} method instead!
	 */
	private static HardwareType detectedHardware = HardwareType.VIVE;

	// TextureIDs of framebuffers for each eye
	private int LeftEyeTextureId;

	final static VRTextureBounds_t texBounds = new VRTextureBounds_t();
	final static VRTextureWithDepth_t texType0 = new VRTextureWithDepth_t();
	final static VRTextureWithDepth_t texType1 = new VRTextureWithDepth_t();
	// aiming

	static Vec3d[] aimSource = new Vec3d[3];

	public static Vector3f offset=new Vector3f(0,0,0);

	static boolean[] controllerTracking = new boolean[3];
	public static TrackedController[] controllers = new TrackedController[2];

	// Controllers
	public static final int RIGHT_CONTROLLER = 0;
	public static final int LEFT_CONTROLLER = 1;
	public static final int THIRD_CONTROLLER = 2;
	private static Matrix4f[] controllerPose = new Matrix4f[3];
	private static Matrix4f[] controllerRotation = new Matrix4f[3];
	private static Matrix4f[] handRotation = new Matrix4f[3];
	public static int[] controllerDeviceIndex = new int[3];
	private static VRControllerState_t.ByReference[] inputStateRefernceArray = new VRControllerState_t.ByReference[3];
	private static VRControllerState_t[] lastControllerState = new VRControllerState_t[3];
	private static VRControllerState_t[] controllerStateReference = new VRControllerState_t[3];

	private static Queue<VREvent_t> vrEvents = new LinkedList<>();

	private static Queue<VRInputEvent> vrInputEvents = new LinkedList<>();
	private static Map<String, ButtonTuple> activeBindings = new HashMap<>();

	public static double startedOpeningInventory = 0;
	public static boolean hudPopup = true;


	static boolean headIsTracking;

	private static int moveModeSwitchcount = 0;

	public static boolean isWalkingAbout;
	private static boolean isFreeRotate;
	private static ControllerType walkaboutController;
	private static ControllerType freeRotateController;
	private static float walkaboutYawStart;
	private static float hmdForwardYaw;

	public static boolean mrMovingCamActive;
	public static Vec3d mrControllerPos = Vec3d.ZERO;
	public static float mrControllerPitch;
	public static float mrControllerYaw;
	public static float mrControllerRoll;

	public String getName() {
		return "OpenVR";
	}

	public String getID() {
		return "openvr";
	}

	public static final int MODIFIER_COUNT = 2;

	public static final KeyBinding keyModifier1 = new KeyBinding("Modifier 1", GLFW.GLFW_KEY_UNKNOWN, "Vivecraft");
	public static final KeyBinding keyModifier2 = new KeyBinding("Modifier 2", GLFW.GLFW_KEY_UNKNOWN, "Vivecraft");
	public static final KeyBinding keyHotbarNext = new KeyBinding("Hotbar Next", GLFW.GLFW_KEY_PAGE_UP, "Vivecraft");
	public static final KeyBinding keyHotbarPrev = new KeyBinding("Hotbar Prev", GLFW.GLFW_KEY_PAGE_DOWN, "Vivecraft");
	public static final KeyBinding keyRotateLeft = new KeyBinding("Rotate Left", GLFW.GLFW_KEY_LEFT, "Vivecraft");
	public static final KeyBinding keyRotateRight = new KeyBinding("Rotate Right", GLFW.GLFW_KEY_RIGHT, "Vivecraft");
	public static final KeyBinding keyWalkabout = new KeyBinding("Walkabout", GLFW.GLFW_KEY_END, "Vivecraft");
	public static final KeyBinding keyRotateFree = new KeyBinding("Rotate Free", GLFW.GLFW_KEY_HOME, "Vivecraft");
	public static final KeyBinding keyQuickTorch = new KeyBinding("Quick Torch", GLFW.GLFW_KEY_INSERT, "Vivecraft");
	public static final KeyBinding keyMenuButton = new KeyBinding("In-Game Menu Button", GLFW.GLFW_KEY_UNKNOWN, "Vivecraft");
	public static final KeyBinding keyExportWorld = new KeyBinding("Export Menu World", GLFW.GLFW_KEY_UNKNOWN, "Vivecraft");
	public static final KeyBinding keyRadialMenu = new KeyBinding("Open Radial Menu", GLFW.GLFW_KEY_UNKNOWN, "Vivecraft");
	public static final KeyBinding keySwapMirrorView = new KeyBinding("Swap Mirror View", GLFW.GLFW_KEY_UNKNOWN, "Vivecraft");
	public static final KeyBinding keyToggleKeyboard = new KeyBinding("Show/Hide Keyboard", GLFW.GLFW_KEY_UNKNOWN, "Vivecraft");
	public static final KeyBinding keyMoveThirdPersonCam = new KeyBinding("Move Third Person Camera", GLFW.GLFW_KEY_UNKNOWN, "Vivecraft");
	public static final KeyBinding keyInteractVRprimary = new KeyBinding("Interact VR Primary",GLFW.GLFW_KEY_UNKNOWN,"Vivecraft");
	public static final KeyBinding keyInteractVRsecondary = new KeyBinding("Interact VR Secondary",GLFW.GLFW_KEY_UNKNOWN,"Vivecraft");
	
	
	public MCOpenVR()
	{
		super();

		for (int c=0;c<3;c++)
		{
			aimSource[c] = new Vec3d(0.0D, 0.0D, 0.0D);
			controllerPose[c] = new Matrix4f();
			controllerRotation[c] = new Matrix4f();
			handRotation[c] = new Matrix4f();
			controllerDeviceIndex[c] = -1;

			lastControllerState[c] = new VRControllerState_t();
			controllerStateReference[c] = new VRControllerState_t();
			inputStateRefernceArray[c] = new VRControllerState_t.ByReference();

			inputStateRefernceArray[c].setAutoRead(false);
			inputStateRefernceArray[c].setAutoWrite(false);
			inputStateRefernceArray[c].setAutoSynch(false);
			for (int i = 0; i < 5; i++)
			{
				lastControllerState[c].rAxis[i] = new VRControllerAxis_t();
			}


		}
	}

	private static boolean tried;

	public static boolean init()  throws Exception
	{
		if ( initialized )
			return true;

		if ( tried )
			return initialized;

		tried = true;

		mc = Minecraft.getMinecraft();

		String osname = System.getProperty("os.name").toLowerCase();
		String osarch= System.getProperty("os.arch").toLowerCase();

		String osFolder = "win";

		if( osname.contains("linux")){
			osFolder = "linux";
		}
		else if( osname.contains("mac")){
			osFolder = "osx";
		}

		if (osarch.contains("64"))
		{
			osFolder += "64";
		} else {
			osFolder += "32";
		}

		Utils.unpackNatives(osFolder);

		String openVRPath = new File("openvr/" + osFolder).getAbsolutePath();
		System.out.println("Adding OpenVR search path: " + openVRPath);
		NativeLibrary.addSearchPath("openvr_api", openVRPath);

		if(jopenvr.JOpenVRLibrary.VR_IsHmdPresent() == 0){
			initStatus =  "VR Headset not detected.";
			return false;
		}

		try {
			initializeJOpenVR();
			initOpenVRCompositor() ;
			initOpenVRSettings();
			initOpenVRRenderModels();
			initOpenVRChaperone();
			initOpenComposite();
		} catch (Exception e) {
			e.printStackTrace();
			initSuccess = false;
			initStatus = e.getLocalizedMessage();
			return false;
		}

		System.out.println( "OpenVR initialized & VR connected." );

		HardwareType hw = getHardwareType();
		if (hw == HardwareType.WINDOWSMR) {
			controllers[RIGHT_CONTROLLER] = new TrackedControllerWindowsMR(ControllerType.RIGHT);
			controllers[LEFT_CONTROLLER] = new TrackedControllerWindowsMR(ControllerType.LEFT);
		} else if (hw == HardwareType.OCULUS) {
			controllers[RIGHT_CONTROLLER] = new TrackedControllerOculus(ControllerType.RIGHT);
			controllers[LEFT_CONTROLLER] = new TrackedControllerOculus(ControllerType.LEFT);
		} else {
			controllers[RIGHT_CONTROLLER] = new TrackedControllerVive(ControllerType.RIGHT);
			controllers[LEFT_CONTROLLER] = new TrackedControllerVive(ControllerType.LEFT);
		}

		if (controllers[RIGHT_CONTROLLER] instanceof TrackedControllerVive)
			((TrackedControllerVive)controllers[RIGHT_CONTROLLER]).setTouchpadMode(mc.vrSettings.rightTouchpadMode);
		if (controllers[LEFT_CONTROLLER] instanceof TrackedControllerVive)
			((TrackedControllerVive)controllers[LEFT_CONTROLLER]).setTouchpadMode(mc.vrSettings.leftTouchpadMode);

		deviceVelocity = new Vec3d[JOpenVRLibrary.k_unMaxTrackedDeviceCount];

		for(int i=0;i<poseMatrices.length;i++)
		{
			poseMatrices[i] = new Matrix4f();
			deviceVelocity[i] = new Vec3d(0,0,0);
		}

		HmdMatrix34_t matL = vrsystem.GetEyeToHeadTransform.apply(JOpenVRLibrary.EVREye.EVREye_Eye_Left);
		OpenVRUtil.convertSteamVRMatrix3ToMatrix4f(matL, hmdPoseLeftEye);

		HmdMatrix34_t matR = vrsystem.GetEyeToHeadTransform.apply(JOpenVRLibrary.EVREye.EVREye_Eye_Right);
		OpenVRUtil.convertSteamVRMatrix3ToMatrix4f(matR, hmdPoseRightEye);

		initialized = true;

		if(Main.katvr){
			try {
				System.out.println( "Waiting for KATVR...." );
				Utils.unpackNatives("katvr");
				NativeLibrary.addSearchPath(jkatvr.KATVR_LIBRARY_NAME, new File("openvr/katvr").getAbsolutePath());
				jkatvr.Init(1);
				jkatvr.Launch();
				if(jkatvr.CheckForLaunch()){
					System.out.println( "KATVR Loaded" );
				}else {
					System.out.println( "KATVR Failed to load" );
				}

			} catch (Exception e) {
				System.out.println( "KATVR crashed: " + e.getMessage() );
			}
		}

		return true;
	}

	public static boolean isError(){
		return hmdErrorStore.getValue() != 0 || hmdErrorStoreBuf.get(0) != 0;
	}

	public static int getError(){
		return hmdErrorStore.getValue() != 0 ? hmdErrorStore.getValue() : hmdErrorStoreBuf.get(0);
	}

	public static KeyBinding[] initializeBindings(KeyBinding[] keyBindings) {
		keyBindings = ArrayUtils.add(keyBindings, keyModifier1);
		keyBindings = ArrayUtils.add(keyBindings, keyModifier2);
		keyBindings = ArrayUtils.add(keyBindings, keyRotateLeft);
		keyBindings = ArrayUtils.add(keyBindings, keyRotateRight);
		keyBindings = ArrayUtils.add(keyBindings, keyRotateFree);
		keyBindings = ArrayUtils.add(keyBindings, keyWalkabout);
		keyBindings = ArrayUtils.add(keyBindings, keyQuickTorch);
		keyBindings = ArrayUtils.add(keyBindings, keyHotbarNext);
		keyBindings = ArrayUtils.add(keyBindings, keyHotbarPrev);
		keyBindings = ArrayUtils.add(keyBindings, keyMenuButton);
		keyBindings = ArrayUtils.add(keyBindings, keyRadialMenu);
		keyBindings = ArrayUtils.add(keyBindings, keyInteractVRprimary);
		keyBindings = ArrayUtils.add(keyBindings, keyInteractVRsecondary);
		keyBindings = ArrayUtils.add(keyBindings, keySwapMirrorView);
		keyBindings = ArrayUtils.add(keyBindings, keyExportWorld);
		keyBindings = ArrayUtils.add(keyBindings, keyToggleKeyboard);
		keyBindings = ArrayUtils.add(keyBindings, keyMoveThirdPersonCam);
		keyBindings = ArrayUtils.add(keyBindings, GuiHandler.keyMenuButton);
		keyBindings = ArrayUtils.add(keyBindings, GuiHandler.keyLeftClick);
		keyBindings = ArrayUtils.add(keyBindings, GuiHandler.keyRightClick);
		keyBindings = ArrayUtils.add(keyBindings, GuiHandler.keyMiddleClick);
		keyBindings = ArrayUtils.add(keyBindings, GuiHandler.keyShift);
		keyBindings = ArrayUtils.add(keyBindings, GuiHandler.keyCtrl);
		keyBindings = ArrayUtils.add(keyBindings, GuiHandler.keyAlt);
		keyBindings = ArrayUtils.add(keyBindings, GuiHandler.keyScrollUp);
		keyBindings = ArrayUtils.add(keyBindings, GuiHandler.keyScrollDown);

		// TODO: Forge?
		Map<String, Integer> co = (Map<String, Integer>) MCReflection.KeyBinding_CATEGORY_ORDER.get(null);
		co.put("Vivecraft", Integer.valueOf(8));
		co.put("Vivecraft GUI", Integer.valueOf(9));

		return keyBindings;
	}

	private static void initializeJOpenVR() throws Exception { 
		hmdErrorStoreBuf = IntBuffer.allocate(1);
		vrsystem = null;
		JOpenVRLibrary.VR_InitInternal(hmdErrorStoreBuf, JOpenVRLibrary.EVRApplicationType.EVRApplicationType_VRApplication_Scene);

		if(!isError()) {
			// ok, try and get the vrsystem pointer..
			vrsystem = new VR_IVRSystem_FnTable(JOpenVRLibrary.VR_GetGenericInterface(JOpenVRLibrary.IVRSystem_Version, hmdErrorStoreBuf));
		}

		if( vrsystem == null || isError()) {
			throw new Exception(jopenvr.JOpenVRLibrary.VR_GetVRInitErrorAsEnglishDescription(getError()).getString(0));		
		} else {

			vrsystem.setAutoSynch(false);
			vrsystem.read();

			System.out.println("OpenVR initialized & VR connected.");

			hmdDisplayFrequency = IntBuffer.allocate(1);
			hmdDisplayFrequency.put( (int) JOpenVRLibrary.ETrackedDeviceProperty.ETrackedDeviceProperty_Prop_DisplayFrequency_Float);
			hmdTrackedDevicePoseReference = new TrackedDevicePose_t.ByReference();
			hmdTrackedDevicePoses = (TrackedDevicePose_t[])hmdTrackedDevicePoseReference.toArray(JOpenVRLibrary.k_unMaxTrackedDeviceCount);
			poseMatrices = new Matrix4f[JOpenVRLibrary.k_unMaxTrackedDeviceCount];
			for(int i=0;i<poseMatrices.length;i++) poseMatrices[i] = new Matrix4f();

			timePerFrame = 1.0 / hmdDisplayFrequency.get(0);

			// disable all this stuff which kills performance
			hmdTrackedDevicePoseReference.setAutoRead(false);
			hmdTrackedDevicePoseReference.setAutoWrite(false);
			hmdTrackedDevicePoseReference.setAutoSynch(false);
			
			for(int i=0;i<JOpenVRLibrary.k_unMaxTrackedDeviceCount;i++) {
				hmdTrackedDevicePoses[i].setAutoRead(false);
				hmdTrackedDevicePoses[i].setAutoWrite(false);
				hmdTrackedDevicePoses[i].setAutoSynch(false);
			}

			initSuccess = true;
		}
	}

	private static Pointer ptrFomrString(String in){
		Pointer p = new Memory(in.length()+1);
		p.setString(0, in);
		return p;

	}

	static void debugOut(int deviceindex){
		System.out.println("******************* VR DEVICE: " + deviceindex + " *************************");
		for(Field i :JOpenVRLibrary.ETrackedDeviceProperty.class.getDeclaredFields()){
			try {
				String[] ts = i.getName().split("_");
				String Type = ts[ts.length - 1];
				String out = "";
				if (Type.equals("Float")) {
					out += i.getName() + " " + vrsystem.GetFloatTrackedDeviceProperty.apply(deviceindex, i.getInt(null), hmdErrorStore);
				}				else if (Type.equals("String")) {
					Pointer pointer = new Memory(JOpenVRLibrary.k_unMaxPropertyStringSize);
					int len = vrsystem.GetStringTrackedDeviceProperty.apply(deviceindex, i.getInt(null), pointer, JOpenVRLibrary.k_unMaxPropertyStringSize - 1, hmdErrorStore);
					out += i.getName() + " " + pointer.getString(0);
				} else if (Type.equals("Bool")) {
					out += i.getName() + " " + vrsystem.GetBoolTrackedDeviceProperty.apply(deviceindex, i.getInt(null), hmdErrorStore);
				} else if (Type.equals("Int32")) {
					out += i.getName() + " " + vrsystem.GetInt32TrackedDeviceProperty.apply(deviceindex, i.getInt(null), hmdErrorStore);
				} else if (Type.equals("Uint64")) {
					out += i.getName() + " " + vrsystem.GetUint64TrackedDeviceProperty.apply(deviceindex, i.getInt(null), hmdErrorStore);
				}else {
					out += i.getName() + " (skipped)" ; 
				}
				System.out.println(out.replace("ETrackedDeviceProperty_Prop_", ""));
			}catch (IllegalAccessException e){
				e.printStackTrace();
			}
		}
		System.out.println("******************* END VR DEVICE: " + deviceindex + " *************************");

	}


	public static void initOpenVRSettings() throws Exception
	{
		vrSettings = new VR_IVRSettings_FnTable(JOpenVRLibrary.VR_GetGenericInterface(JOpenVRLibrary.IVRSettings_Version, hmdErrorStoreBuf));
		if (!isError()) {
			vrSettings.setAutoSynch(false);
			vrSettings.read();					
			System.out.println("OpenVR Settings initialized OK");
		} else {
			if (getError() != 0) {
				System.out.println("VRSettings init failed: " + jopenvr.JOpenVRLibrary.VR_GetVRInitErrorAsEnglishDescription(getError()).getString(0));
				vrSettings = null;
			} else {
				throw new Exception(jopenvr.JOpenVRLibrary.VR_GetVRInitErrorAsEnglishDescription(getError()).getString(0));
			}	
		}
	}


	public static void initOpenVRRenderModels() throws Exception
	{
		vrRenderModels = new VR_IVRRenderModels_FnTable(JOpenVRLibrary.VR_GetGenericInterface(JOpenVRLibrary.IVRRenderModels_Version, hmdErrorStoreBuf));
		if (!isError()) {
			vrRenderModels.setAutoSynch(false);
			vrRenderModels.read();			
			System.out.println("OpenVR RenderModels initialized OK");
		} else {
			if (getError() != 0) {
				System.out.println("VRRenderModels init failed: " + jopenvr.JOpenVRLibrary.VR_GetVRInitErrorAsEnglishDescription(getError()).getString(0));
				vrRenderModels = null;
			} else {
				throw new Exception(jopenvr.JOpenVRLibrary.VR_GetVRInitErrorAsEnglishDescription(getError()).getString(0));
			}
		}
	}

	private static void initOpenVRChaperone() throws Exception {
		vrChaperone = new VR_IVRChaperone_FnTable(JOpenVRLibrary.VR_GetGenericInterface(JOpenVRLibrary.IVRChaperone_Version, hmdErrorStoreBuf));
		if (!isError()) {
			vrChaperone.setAutoSynch(false);
			vrChaperone.read();
			System.out.println("OpenVR chaperone initialized.");
		} else {
			if (getError() != 0) {
				System.out.println("VRChaperone init failed: " + jopenvr.JOpenVRLibrary.VR_GetVRInitErrorAsEnglishDescription(getError()).getString(0));
				vrChaperone = null;
			} else {
				throw new Exception(jopenvr.JOpenVRLibrary.VR_GetVRInitErrorAsEnglishDescription(getError()).getString(0));
			}
		}
	}

	private static void initOpenComposite() throws Exception {
		vrOpenComposite = new VR_IVROCSystem_FnTable(JOpenVRLibrary.VR_GetGenericInterface(VR_IVROCSystem_FnTable.Version, hmdErrorStoreBuf));
		if (!isError()) {
			vrOpenComposite.setAutoSynch(false);
			vrOpenComposite.read();
			System.out.println("OpenComposite initialized.");
		} else {
			System.out.println("OpenComposite not found: " + jopenvr.JOpenVRLibrary.VR_GetVRInitErrorAsEnglishDescription(getError()).getString(0));
			vrOpenComposite = null;
		}
	}

	private static boolean getXforms = true;

	private static Map<String, Matrix4f[]> controllerComponentTransforms;
	private static Map<Long, String> controllerComponentNames;

	private static void getTransforms(){
		if (vrRenderModels == null) return;

		if(getXforms == true) {
			controllerComponentTransforms = new HashMap<String, Matrix4f[]>();
		}

		if(controllerComponentNames == null) {
			controllerComponentNames = new HashMap<Long, String>();
		}

		int count = vrRenderModels.GetRenderModelCount.apply();
		Pointer pointer = new Memory(JOpenVRLibrary.k_unMaxPropertyStringSize);

		List<String> componentNames = new ArrayList<String>(); //TODO get the controller-specific list

		componentNames.add("tip");
		componentNames.add("base");
		componentNames.add("handgrip");
		componentNames.add("status");
		boolean failed = false;
		
		for (String comp : componentNames) {
			controllerComponentTransforms.put(comp, new Matrix4f[2]); 			
			Pointer p = ptrFomrString(comp);

			for (int i = 0; i < 2; i++) {

				//	debugOut(controllerDeviceIndex[i]);

				vrsystem.GetStringTrackedDeviceProperty.apply(controllerDeviceIndex[i], JOpenVRLibrary.ETrackedDeviceProperty.ETrackedDeviceProperty_Prop_RenderModelName_String, pointer, JOpenVRLibrary.k_unMaxPropertyStringSize - 1, hmdErrorStore);

				//doing this next bit for each controller because pointer
				long button = vrRenderModels.GetComponentButtonMask.apply(pointer, p);   		
				if(button > 0){ //see now... wtf openvr, '0' is the system button, it cant also be the error value!
					controllerComponentNames.put(button, comp); //u get 1 button per component, nothing more
				}
				//
				RenderModel_ControllerMode_State_t modeState = new RenderModel_ControllerMode_State_t();
				RenderModel_ComponentState_t componentState = new RenderModel_ComponentState_t();
				byte ret = vrRenderModels.GetComponentState.apply(pointer, p, controllerStateReference[i], modeState, componentState);
				if(ret == 0) {
					//System.out.println("Failed getting transform: " + comp + " controller " + i);
					failed = true; // Oculus does not seem to raise ANY trackedDevice events. So just keep trying...
					continue;
				}
				Matrix4f xform = new Matrix4f();
				OpenVRUtil.convertSteamVRMatrix3ToMatrix4f(componentState.mTrackingToComponentLocal, xform);
				controllerComponentTransforms.get(comp)[i] = xform;
			//	System.out.println("Transform: " + comp + " controller: " + i +" button: " + button + "\r" + Utils.convertOVRMatrix(xform).toString());

				if (!failed && i == 0) {
					try {

						Matrix4f tip = getControllerComponentTransform(0,"tip");
						Matrix4f hand = getControllerComponentTransform(0,"base");

						Vector3f tipvec = tip.transform(forward);
						Vector3f handvec = hand.transform(forward);

						double dot = Math.abs(tipvec.normalised().dot(handvec.normalised()));
						
						double anglerad = Math.acos(dot);
						double angledeg = Math.toDegrees(anglerad);

						double angletestrad = Math.acos(tipvec.normalised().dot(forward.normalised()));
						double angletestdeg = Math.toDegrees(angletestrad);

					//	System.out.println("gun angle " + angledeg + " default angle " + angletestdeg);
						
						gunStyle = angledeg > 10;

					} catch (Exception e) {
						failed = true;
					}
				}
			}
		}
		
		getXforms = failed;
	}

	public static Matrix4f getControllerComponentTransform(int controllerIndex, String componenetName){
		if(controllerComponentTransforms == null || !controllerComponentTransforms.containsKey(componenetName)  || controllerComponentTransforms.get(componenetName)[controllerIndex] == null)
			return OpenVRUtil.Matrix4fSetIdentity(new Matrix4f());
		return controllerComponentTransforms.get(componenetName)[controllerIndex];
	}

	public static Matrix4f getControllerComponentTransformFromButton(int controllerIndex, long button){
		if (controllerComponentNames == null || !controllerComponentNames.containsKey(button))
			return getControllerComponentTransform(controllerIndex, "status");

		return getControllerComponentTransform(controllerIndex, controllerComponentNames.get(button));
	}

	public static boolean hasOpenComposite() {
		return vrOpenComposite != null;
	}

	public static void initOpenVRCompositor() throws Exception
	{
		if(vrsystem != null ) {
			vrCompositor = new VR_IVRCompositor_FnTable(JOpenVRLibrary.VR_GetGenericInterface(JOpenVRLibrary.IVRCompositor_Version, hmdErrorStoreBuf));
			if(vrCompositor != null && !isError()){                
				System.out.println("OpenVR Compositor initialized OK.");
				vrCompositor.setAutoSynch(false);
				vrCompositor.read();
				vrCompositor.SetTrackingSpace.apply(JOpenVRLibrary.ETrackingUniverseOrigin.ETrackingUniverseOrigin_TrackingUniverseStanding);

				int buffsize=20;
				Pointer s=new Memory(buffsize);

				System.out.println("TrackingSpace: "+vrCompositor.GetTrackingSpace.apply());

				vrsystem.GetStringTrackedDeviceProperty.apply(JOpenVRLibrary.k_unTrackedDeviceIndex_Hmd,JOpenVRLibrary.ETrackedDeviceProperty.ETrackedDeviceProperty_Prop_ManufacturerName_String,s,buffsize,hmdErrorStore);
				String id=s.getString(0);
				System.out.println("Device manufacturer is: "+id);

				detectedHardware = HardwareType.fromManufacturer(id);
				mc.vrSettings.loadOptions();

			} else {
				throw new Exception(jopenvr.JOpenVRLibrary.VR_GetVRInitErrorAsEnglishDescription(getError()).getString(0));			 
			}
		}
		if( vrCompositor == null ) {
			System.out.println("Skipping VR Compositor...");
			if( vrsystem != null ) {
				vsyncToPhotons = vrsystem.GetFloatTrackedDeviceProperty.apply(JOpenVRLibrary.k_unTrackedDeviceIndex_Hmd, JOpenVRLibrary.ETrackedDeviceProperty.ETrackedDeviceProperty_Prop_SecondsFromVsyncToPhotons_Float, hmdErrorStore);
			} else {
				vsyncToPhotons = 0f;
			}
		}

		// left eye
		texBounds.uMax = 1f;
		texBounds.uMin = 0f;
		texBounds.vMax = 1f;
		texBounds.vMin = 0f;
		texBounds.setAutoSynch(false);
		texBounds.setAutoRead(false);
		texBounds.setAutoWrite(false);
		texBounds.write();


		// texture type
		texType0.eColorSpace = JOpenVRLibrary.EColorSpace.EColorSpace_ColorSpace_Gamma;
		texType0.eType = JOpenVRLibrary.ETextureType.ETextureType_TextureType_OpenGL;
		texType0.handle = Pointer.createConstant(-1);
		VRTextureDepthInfo_t info = new VRTextureDepthInfo_t();
		info.vRange = new HmdVector2_t(new float[]{0,1});
		texType0.depth = info;
		texType0.setAutoSynch(false);
		texType0.setAutoRead(false);
		texType0.setAutoWrite(false);
		texType0.write();


		// texture type
		texType1.eColorSpace = JOpenVRLibrary.EColorSpace.EColorSpace_ColorSpace_Gamma;
		texType1.eType = JOpenVRLibrary.ETextureType.ETextureType_TextureType_OpenGL;
		texType1.handle = Pointer.createConstant(-1);
		VRTextureDepthInfo_t info2 = new VRTextureDepthInfo_t();
		info2.vRange = new HmdVector2_t(new float[]{0,1});
		texType0.depth = info2;
		texType1.setAutoSynch(false);
		texType1.setAutoRead(false);
		texType1.setAutoWrite(false);
		texType1.write();

		System.out.println("OpenVR Compositor initialized OK.");

	}

	public boolean initOpenVRControlPanel()
	{
		return true;
		//		vrControlPanel = new VR_IVRSettings_FnTable(JOpenVRLibrary.VR_GetGenericInterface(JOpenVRLibrary.IVRControlPanel_Version, hmdErrorStore));
		//		if(vrControlPanel != null && hmdErrorStore.getValue() == 0){
		//			System.out.println("OpenVR Control Panel initialized OK.");
		//			return true;
		//		} else {
		//			initStatus = "OpenVR Control Panel error: " + JOpenVRLibrary.VR_GetStringForHmdError(hmdErrorStore.getValue()).getString(0);
		//			return false;
		//		}
	}

	private String lasttyped = "";

	public static boolean paused =false; 

	public static void poll(long frameIndex)
	{
		boolean sleeping = (mc.world !=null && mc.player != null && mc.player.isPlayerSleeping());

		paused = vrsystem.ShouldApplicationPause.apply() != 0;

		mc.profiler.startSection("events");
		pollVREvents();

		if(!mc.vrSettings.seated){
			mc.profiler.endStartSection("controllers");

			for (TrackedController controller : controllers) {
				controller.updateState();
				controller.processInput();
			}

			updateControllerButtonState(); // Still used by tip transforms

			boolean freemoveJoyPad = mc.vrPlayer.getFreeMove() && mc.vrSettings.vrFreeMoveMode == VRSettings.FREEMOVE_JOYPAD;
			HardwareType hw = getHardwareType();
			if (hw == HardwareType.OCULUS) {
				((TrackedControllerOculus)controllers[LEFT_CONTROLLER]).setStickButtonsEnabled(!freemoveJoyPad);
			} else if (hw == HardwareType.WINDOWSMR) {
				TrackedControllerWindowsMR controller = ((TrackedControllerWindowsMR)controllers[LEFT_CONTROLLER]);
				controller.setSwipeEnabled(!freemoveJoyPad || mc.vrSettings.freemoveWMRStick);
				controller.setStickButtonsEnabled(!freemoveJoyPad || !mc.vrSettings.freemoveWMRStick);
			} else {
				((TrackedControllerVive)controllers[LEFT_CONTROLLER]).setSwipeEnabled(!freemoveJoyPad);
			}

			// GUI controls

			mc.profiler.startSection("gui");

			if(mc.currentScreen == null && mc.vrSettings.vrTouchHotbar && mc.vrSettings.vrHudLockMode != mc.vrSettings.HUD_LOCK_HEAD && hudPopup){
				processHotbar();
			}

			mc.profiler.endSection();
		}

		mc.profiler.endStartSection("processEvents");
		processVREvents();

		mc.profiler.endStartSection("updatePose");
		updatePose();

		mc.profiler.endSection();
	}

	private static int quickTorchPreviousSlot;

	private static Vec3d vecFromVector(Vector3f in){
		return new Vec3d(in.x, in.y, in.z);
	}
	private static void processHotbar() {

		if(mc.player == null) return;
		if(mc.player.inventory == null) return;
		
		if(mc.climbTracker.isGrabbingLadder() && 
				mc.climbTracker.isClaws(mc.player.getHeldItemMainhand())) return;

		Vec3d main = getAimSource(0);
		Vec3d off = getAimSource(1);

		Vec3d barStartos = null,barEndos = null;

		int i = 1;
		if(mc.vrSettings.vrReverseHands) i = -1;

		if (mc.vrSettings.vrHudLockMode == VRSettings.HUD_LOCK_WRIST){
			barStartos = vecFromVector( getAimRotation(1).transform(new Vector3f(i*0.02f,0.05f,0.26f)));
			barEndos = vecFromVector( getAimRotation(1).transform(new Vector3f(i*0.02f,0.05f,0.01f)));
		} else if (mc.vrSettings.vrHudLockMode == VRSettings.HUD_LOCK_HAND){
			barStartos = vecFromVector( getAimRotation(1).transform(new Vector3f(i*-.18f,0.08f,-0.01f)));
			barEndos = vecFromVector( getAimRotation(1).transform(new Vector3f(i*0.19f,0.04f,-0.08f)));
		} else return; //how did u get here


		Vec3d barStart = off.add(barStartos.x, barStartos.y, barStartos.z);	
		Vec3d barEnd = off.add(barEndos.x, barEndos.y, barEndos.z);

		Vec3d u = barStart.subtract(barEnd);
		Vec3d pq = barStart.subtract(main);
		float dist = (float) (pq.crossProduct(u).length() / u.length());

		if(dist > 0.06) return;

		float fact = (float) (pq.dotProduct(u) / (u.x*u.x + u.y*u.y + u.z*u.z));

		if(fact < 0) return;

		Vec3d w2 = u.scale(fact).subtract(pq);

		Vec3d point = main.subtract(w2);
		float linelen = (float) barStart.subtract(barEnd).length();
		float ilen = (float) barStart.subtract(point).length();

		float pos = ilen / linelen * 9; 

		if(mc.vrSettings.vrReverseHands) pos = 9 - pos;

		int box = (int) Math.floor(pos);
		if(pos - Math.floor(pos) < 0.1) return;

		if(box > 8) return;
		if(box < 0) return;
		//all that maths for this.
		if(box != mc.player.inventory.currentItem){
			mc.player.inventory.currentItem = box;	
			triggerHapticPulse(0, 750);
		}
	}

	

	public static void destroy()
	{
		if (initialized)
		{
			try {
				JOpenVRLibrary.VR_ShutdownInternal();
				initialized = false;
				if(Main.katvr)
					jkatvr.Halt();
			} catch (Throwable e) { // wtf valve
				e.printStackTrace();
			}

		}
	}

	//	public HmdParameters getHMDInfo()
	//	{
	//		HmdParameters hmd = new HmdParameters();
	//		if ( isInitialized() )
	//		{
	//			IntBuffer rtx = IntBuffer.allocate(1);
	//			IntBuffer rty = IntBuffer.allocate(1);
	//			vrsystem.GetRecommendedRenderTargetSize.apply(rtx, rty);
	//
	//			hmd.Type = HmdType.ovrHmd_Other;
	//			hmd.ProductName = "OpenVR";
	//			hmd.Manufacturer = "Unknown";
	//			hmd.AvailableHmdCaps = 0;
	//			hmd.DefaultHmdCaps = 0;
	//			hmd.AvailableTrackingCaps = HmdParameters.ovrTrackingCap_Orientation | HmdParameters.ovrTrackingCap_Position;
	//			hmd.DefaultTrackingCaps = HmdParameters.ovrTrackingCap_Orientation | HmdParameters.ovrTrackingCap_Position;
	//			hmd.Resolution = new Sizei( rtx.get(0) * 2, rty.get(0) );
	//
	//			float topFOV = vrsystem.GetFloatTrackedDeviceProperty.apply(JOpenVRLibrary.k_unTrackedDeviceIndex_Hmd, JOpenVRLibrary.ETrackedDeviceProperty.ETrackedDeviceProperty_Prop_FieldOfViewTopDegrees_Float, hmdErrorStore);
	//			float bottomFOV = vrsystem.GetFloatTrackedDeviceProperty.apply(JOpenVRLibrary.k_unTrackedDeviceIndex_Hmd, JOpenVRLibrary.ETrackedDeviceProperty.ETrackedDeviceProperty_Prop_FieldOfViewBottomDegrees_Float, hmdErrorStore);
	//			float leftFOV = vrsystem.GetFloatTrackedDeviceProperty.apply(JOpenVRLibrary.k_unTrackedDeviceIndex_Hmd, JOpenVRLibrary.ETrackedDeviceProperty.ETrackedDeviceProperty_Prop_FieldOfViewLeftDegrees_Float, hmdErrorStore);
	//			float rightFOV = vrsystem.GetFloatTrackedDeviceProperty.apply(JOpenVRLibrary.k_unTrackedDeviceIndex_Hmd, JOpenVRLibrary.ETrackedDeviceProperty.ETrackedDeviceProperty_Prop_FieldOfViewRightDegrees_Float, hmdErrorStore);
	//
	//			hmd.DefaultEyeFov[0] = new FovPort((float)Math.tan(topFOV),(float)Math.tan(bottomFOV),(float)Math.tan(leftFOV),(float)Math.tan(rightFOV));
	//			hmd.DefaultEyeFov[1] = new FovPort((float)Math.tan(topFOV),(float)Math.tan(bottomFOV),(float)Math.tan(leftFOV),(float)Math.tan(rightFOV));
	//			hmd.MaxEyeFov[0] = new FovPort((float)Math.tan(topFOV),(float)Math.tan(bottomFOV),(float)Math.tan(leftFOV),(float)Math.tan(rightFOV));
	//			hmd.MaxEyeFov[1] = new FovPort((float)Math.tan(topFOV),(float)Math.tan(bottomFOV),(float)Math.tan(leftFOV),(float)Math.tan(rightFOV));
	//			hmd.DisplayRefreshRate = 90.0f;
	//		}
	//
	//		return hmd;
	//	}



	private static void findControllerDevices()
	{
		controllerDeviceIndex[RIGHT_CONTROLLER] = -1;
		controllerDeviceIndex[LEFT_CONTROLLER] = -1;
		controllerDeviceIndex[THIRD_CONTROLLER] = -1;

		if(mc.vrSettings.vrReverseHands){
			controllerDeviceIndex[RIGHT_CONTROLLER]  = vrsystem.GetTrackedDeviceIndexForControllerRole.apply(JOpenVRLibrary.ETrackedControllerRole.ETrackedControllerRole_TrackedControllerRole_LeftHand);
			controllerDeviceIndex[LEFT_CONTROLLER] = vrsystem.GetTrackedDeviceIndexForControllerRole.apply(JOpenVRLibrary.ETrackedControllerRole.ETrackedControllerRole_TrackedControllerRole_RightHand);
		}else {
			controllerDeviceIndex[LEFT_CONTROLLER]  = vrsystem.GetTrackedDeviceIndexForControllerRole.apply(JOpenVRLibrary.ETrackedControllerRole.ETrackedControllerRole_TrackedControllerRole_LeftHand);
			controllerDeviceIndex[RIGHT_CONTROLLER] = vrsystem.GetTrackedDeviceIndexForControllerRole.apply(JOpenVRLibrary.ETrackedControllerRole.ETrackedControllerRole_TrackedControllerRole_RightHand);
		}

		controllers[RIGHT_CONTROLLER].deviceIndex = controllerDeviceIndex[RIGHT_CONTROLLER];
		controllers[LEFT_CONTROLLER].deviceIndex = controllerDeviceIndex[LEFT_CONTROLLER];

	}

	private static void updateControllerButtonState()
	{
		for (int c = 0; c < 2; c++) //each controller
		{
			// store previous state
			lastControllerState[c].unPacketNum = controllerStateReference[c].unPacketNum;
			lastControllerState[c].ulButtonPressed = controllerStateReference[c].ulButtonPressed;
			lastControllerState[c].ulButtonTouched = controllerStateReference[c].ulButtonTouched;

			for (int i = 0; i < 5; i++) //5 axes but only [0] and [1] is anything, trigger and touchpad
			{
				if (controllerStateReference[c].rAxis[i] != null)
				{
					lastControllerState[c].rAxis[i].x = controllerStateReference[c].rAxis[i].x;
					lastControllerState[c].rAxis[i].y = controllerStateReference[c].rAxis[i].y;
				}
			}

			// read new state
			if (controllerDeviceIndex[c] != -1)
			{			
				vrsystem.GetControllerState.apply(controllerDeviceIndex[c], inputStateRefernceArray[c], inputStateRefernceArray[c].size());
				inputStateRefernceArray[c].read();
				controllerStateReference[c] = inputStateRefernceArray[c];			
			} else
			{
				// controller not connected, clear state
				lastControllerState[c].ulButtonPressed = 0;
				lastControllerState[c].ulButtonPressed = 0;

				for (int i = 0; i < 5; i++)
				{
					if (controllerStateReference[c].rAxis[i] != null)
					{
						lastControllerState[c].rAxis[i].x = 0.0f;
						lastControllerState[c].rAxis[i].y = 0.0f;
					}
				}
				try{
					controllerStateReference[c] = lastControllerState[c];					
				} catch (Throwable e){

				}
			}
		}
	}

	public static float joyPadX, joyPadZ;

	public static void processInputs() {
		if(Main.viewonly) {
			vrInputEvents.clear();
			return;
		}

		outer: while (hasNextInputEvent()) {
			VRInputEvent event = nextInputEvent();
			
			if (event.isButtonPressEvent()) {
				if (event.getButtonState() && mc.currentScreen instanceof GuiVRControls && ((GuiVRControls)mc.currentScreen).pressMode) {
					((GuiVRControls)mc.currentScreen).bindSingleButton(new ButtonTuple(event.getButton(), event.getController().getType()));
					continue;
				}

				if (!isBindingBound(keyMoveThirdPersonCam)) {
					if (event.getButtonState() && event.getController().getType() == ControllerType.RIGHT && (event.getButton() == ButtonType.VIVE_APPMENU || event.getButton() == ButtonType.OCULUS_BY)) {
						if ((controllers[RIGHT_CONTROLLER].isButtonPressed(ButtonType.VIVE_GRIP) || controllers[RIGHT_CONTROLLER].isButtonPressed(ButtonType.OCULUS_HAND_TRIGGER))
								&& (mc.vrSettings.displayMirrorMode == VRSettings.MIRROR_MIXED_REALITY || mc.vrSettings.displayMirrorMode == VRSettings.MIRROR_THIRD_PERSON)) {
							if (!Main.kiosk) {
								//VRHotkeys.snapMRCam(0);
								VRHotkeys.startMovingThirdPersonCam(0);
							}
							continue;
						}
					}
				}
			}

			if(KeyboardHandler.handleInputEvent(event)) continue;
			if(RadialHandler.handleInputEvent(event)) continue;

			if (!isBindingBound(keyMoveThirdPersonCam)) {
				if (VRHotkeys.isMovingThirdPersonCam()) {
					if ((MCOpenVR.isVive() && (!controllers[RIGHT_CONTROLLER].isButtonPressed(ButtonType.VIVE_GRIP) || !controllers[RIGHT_CONTROLLER].isButtonPressed(ButtonType.VIVE_APPMENU)))
							|| (!MCOpenVR.isVive() && (!controllers[RIGHT_CONTROLLER].isButtonPressed(ButtonType.OCULUS_HAND_TRIGGER) || !controllers[RIGHT_CONTROLLER].isButtonPressed(ButtonType.OCULUS_BY)))) {
						VRHotkeys.stopMovingThirdPersonCam();
						mc.vrSettings.saveOptions();
					}
				}
			}
							
			// GUI bindings
			for (VRButtonMapping binding : mc.vrSettings.buttonMappings.values()) {
				if (binding.buttons.contains(new ButtonTuple(event.getButton(), event.getController().getType(), event.isButtonTouchEvent()))) {
					if (!binding.isModifierBinding() && (keyModifier1.isKeyDown() != binding.hasModifier(0) || keyModifier2.isKeyDown() != binding.hasModifier(1))) {
						continue;
					}

					if (event.getButtonState()) {
						if ((mc.currentScreen != null || KeyboardHandler.Showing || RadialHandler.isUsingController(event.getController().getType())) && (binding.isGUIBinding() || binding.isKeyboardBinding())) {
							binding.press();
							if (binding.keyBinding != null)
								activeBindings.put(binding.keyBinding.getKeyDescription(), new ButtonTuple(event.getButton(), event.getController().getType()));
							continue outer; // GUI bindings override in-game ones
						}
					} else {
						boolean unpress = true;
						for (ButtonTuple button : binding.buttons) {
							if (!controllers[button.controller.ordinal()].isButtonActive(button.button))
								continue;
							if (button.isTouch && controllers[button.controller.ordinal()].isButtonTouched(button.button)) {
								unpress = false;
								break;
							}
							if (!button.isTouch && controllers[button.controller.ordinal()].isButtonPressed(button.button)) {
								unpress = false;
								break;
							}
						}

						if (unpress) {
							binding.scheduleUnpress(1);

							if (binding.isModifierBinding()) {
								int modifier = 0;
								if (binding.keyBinding == keyModifier2)
									modifier = 1;

								// Unpress any bindings using this modifier
								for (VRButtonMapping binding2 : mc.vrSettings.buttonMappings.values()) {
									if (binding2.hasModifier(modifier))
										binding2.scheduleUnpress(1);
								}
							}
						}
					}
				}
			}

			// In-game bindings
			ArrayList<VRButtonMapping> affectedBindings=new ArrayList<>();
			for (VRButtonMapping binding : mc.vrSettings.buttonMappings.values()) {
				if (binding.buttons.contains(new ButtonTuple(event.getButton(), event.getController().getType(), event.isButtonTouchEvent()))) {
					affectedBindings.add(binding);
				}
			}
			
			affectedBindings.sort(Comparator.comparingInt(VRButtonMapping::getPriority).reversed());
			
			for(VRButtonMapping binding: affectedBindings){
					if (!binding.isModifierBinding() && (keyModifier1.isKeyDown() != binding.hasModifier(0) || keyModifier2.isKeyDown() != binding.hasModifier(1))) {
						continue;
					}

					if (event.getButtonState()) {
						// Right controller blocked in GUI since it's the pointer
						if ((!binding.isGUIBinding() || binding.isKeyboardBinding()) && (mc.currentScreen == null && !RadialHandler.isUsingController(event.getController().getType()) /*|| event.getController().getType() == ControllerType.LEFT*/)) {
							boolean consumed=binding.press();
							if (binding.keyBinding != null)
								activeBindings.put(binding.keyBinding.getKeyDescription(), new ButtonTuple(event.getButton(), event.getController().getType()));
							if(consumed)
								break;
						}
					} else {
						boolean unpress = true;
						for (ButtonTuple button : binding.buttons) {
							if (!controllers[button.controller.ordinal()].isButtonActive(button.button))
								continue;
							if (button.isTouch && controllers[button.controller.ordinal()].isButtonTouched(button.button)) {
								unpress = false;
								break;
							}
							if (!button.isTouch && controllers[button.controller.ordinal()].isButtonPressed(button.button)) {
								unpress = false;
								break;
							}
						}

						if (unpress) {
							binding.scheduleUnpress(1);

							if (binding.isModifierBinding()) {
								int modifier = 0;
								if (binding.keyBinding == keyModifier2)
									modifier = 1;

								// Unpress any bindings using this modifier
								for (VRButtonMapping binding2 : mc.vrSettings.buttonMappings.values()) {
									if (binding2.hasModifier(modifier))
										binding2.scheduleUnpress(1);
								}
							}
						}
					
				}
			}
		}

		if (controllers[LEFT_CONTROLLER] instanceof TrackedControllerWindowsMR && mc.vrSettings.freemoveWMRStick) {
			Vector2 axis = controllers[LEFT_CONTROLLER].getAxis(AxisType.WMR_STICK);
			if (Math.abs(axis.getX()) > mc.vrSettings.analogDeadzone)
				joyPadX = -axis.getX();
			else joyPadX = 0;
			if (Math.abs(axis.getY()) > mc.vrSettings.analogDeadzone)
				joyPadZ = axis.getY();
			else joyPadZ = 0;
		} else if (controllers[LEFT_CONTROLLER] instanceof TrackedControllerVive) {
			if (controllers[LEFT_CONTROLLER].isButtonTouched(ButtonType.VIVE_TOUCHPAD)) {
				Vector2 axis = controllers[LEFT_CONTROLLER].getAxis(AxisType.VIVE_TOUCHPAD);
				joyPadX = -axis.getX();
				joyPadZ = axis.getY();
			} else {
				joyPadX = 0;
				joyPadZ = 0;
			}
		} else if (controllers[LEFT_CONTROLLER] instanceof TrackedControllerOculus) {
			Vector2 axis = controllers[LEFT_CONTROLLER].getAxis(AxisType.OCULUS_STICK);
			if (Math.abs(axis.getX()) > mc.vrSettings.analogDeadzone)
				joyPadX = -axis.getX();
			else joyPadX = 0;
			if (Math.abs(axis.getY()) > mc.vrSettings.analogDeadzone)
				joyPadZ = axis.getY();
			else joyPadZ = 0;
		}
	}

	public static void processBindings() {
		//VIVE SPECIFIC FUNCTIONALITY
		//TODO: Find a better home for these. (uh?)

		boolean sleeping = (mc.world !=null && mc.player != null && mc.player.isPlayerSleeping());
		boolean gui = mc.currentScreen != null;

		//handle movementtoggle
		if (mc.gameSettings.keyBindPickBlock.isKeyDown() && !VRHotkeys.isMovingThirdPersonCam()) {
			if(mc.vrSettings.vrAllowLocoModeSwotch){
				moveModeSwitchcount++;
				if (moveModeSwitchcount >= 20 * 4) {
					moveModeSwitchcount = 0;
					mc.vrPlayer.setFreeMove(!mc.vrPlayer.getFreeMove());
				}
			}
		} else {
			moveModeSwitchcount = 0;
		}

		Vec3d main = getAimVector(0);
		Vec3d off = getAimVector(1);

		float myaw = (float) Math.toDegrees(Math.atan2(-main.x, main.z));
		float oyaw= (float) Math.toDegrees(Math.atan2(-off.x, off.z));;

		if(!gui){
			if(keyWalkabout.isKeyDown()){
				float yaw = myaw;

				//oh this is ugly. TODO: cache which hand when binding button.
				TrackedController controller = findActiveBindingController(keyWalkabout);
				if (controller != null && controller.getType() == ControllerType.LEFT) {
					yaw = oyaw;
				}

				if (!isWalkingAbout){
					isWalkingAbout = true;
					walkaboutYawStart = mc.vrSettings.vrWorldRotation - yaw;  
				}
				else {
					mc.vrSettings.vrWorldRotation = walkaboutYawStart + yaw;
					mc.vrSettings.vrWorldRotation %= 360; // Prevent stupidly large values (can they even happen here?)
					//	mc.vrPlayer.checkandUpdateRotateScale(true);
				}
			} else {
				isWalkingAbout = false;
			}

			if(keyRotateFree.isKeyDown()){
				float yaw = myaw;

				//oh this is ugly. TODO: cache which hand when binding button.
				TrackedController controller = findActiveBindingController(keyRotateFree);
				if (controller != null && controller.getType() == ControllerType.LEFT) {
					yaw = oyaw;
				}

				if (!isFreeRotate){
					isFreeRotate = true;
					walkaboutYawStart = mc.vrSettings.vrWorldRotation + yaw;  
				}
				else {
					mc.vrSettings.vrWorldRotation = walkaboutYawStart - yaw;
					//	mc.vrPlayer.checkandUpdateRotateScale(true,0);
				}
			} else {
				isFreeRotate = false;
			}
		}


		if(keyHotbarNext.isPressed()) {
			changeHotbar(-1);
			MCOpenVR.triggerBindingHapticPulse(keyHotbarNext, 250);
		}

		if(keyHotbarPrev.isPressed()){
			changeHotbar(1);
			MCOpenVR.triggerBindingHapticPulse(keyHotbarPrev, 250);
		}

		if(keyQuickTorch.isPressed() && mc.player != null){
			for (int slot=0;slot<9;slot++)
			{  
				ItemStack itemStack = mc.player.inventory.getStackInSlot(slot);
				if (itemStack.getItem() instanceof ItemBlock && ((ItemBlock)itemStack.getItem()).getBlock() instanceof BlockTorch  && mc.currentScreen == null)
				{
					quickTorchPreviousSlot = mc.player.inventory.currentItem;
					mc.player.inventory.currentItem = slot;
					mc.rightClickMouse();
					// switch back immediately
					mc.player.inventory.currentItem = quickTorchPreviousSlot;
					quickTorchPreviousSlot = -1;
					break;
				}
			}
		}

		// if you start teleporting, close any UI
		if (gui && !sleeping && mc.gameSettings.keyBindForward.isKeyDown() && !(mc.currentScreen instanceof GuiWinGame))
		{
			if(mc.player !=null) mc.player.closeScreen();
		}

		if(!mc.gameSettings.keyBindInventory.isKeyDown()){
			startedOpeningInventory = 0;
		}

		//GuiContainer.java only listens directly to the keyboard to close.
		if(gui && !(mc.currentScreen instanceof GuiWinGame) && mc.gameSettings.keyBindInventory.isKeyDown()){ //inventory will repeat open/close while button is held down. TODO: fix.
			if((getCurrentTimeSecs() - startedOpeningInventory) > 0.5 && mc.player != null) mc.player.closeScreen();
			VRButtonMapping.unpressKey(mc.gameSettings.keyBindInventory); //minecraft.java will open a new window otherwise.
		}

		if(mc.vrSettings.vrWorldRotationIncrement == 0){
			ButtonTuple button = MCOpenVR.findAnyBindingButton(keyRotateLeft);
			float ax= 0;
			if (button != null) 
				ax=MovementInputFromOptions.getMovementAxisValue(button);
			if(keyRotateLeft.isKeyDown() || (!isVive() && ax > 0)){ //require button press for trackpad.
				float analogRotSpeed = 5;
				if(ax > 0)	analogRotSpeed= 10 * ax;
				mc.vrSettings.vrWorldRotation+=analogRotSpeed;
				mc.vrSettings.vrWorldRotation = mc.vrSettings.vrWorldRotation % 360;
			}
		}else{
			if(keyRotateLeft.isPressed()){
				mc.vrSettings.vrWorldRotation+=mc.vrSettings.vrWorldRotationIncrement;
				mc.vrSettings.vrWorldRotation = mc.vrSettings.vrWorldRotation % 360;
			}
		}

		if(mc.vrSettings.vrWorldRotationIncrement == 0){
			ButtonTuple button = MCOpenVR.findAnyBindingButton(keyRotateRight);
			float ax= 0;
			if (button != null) 
				ax=MovementInputFromOptions.getMovementAxisValue(button);
			if(keyRotateRight.isKeyDown() || (!isVive() && ax > 0)){//require button press for trackpad.
				float analogRotSpeed = 5;
				if(ax > 0)	analogRotSpeed = 10 * ax;
				mc.vrSettings.vrWorldRotation-=analogRotSpeed;
				mc.vrSettings.vrWorldRotation = mc.vrSettings.vrWorldRotation % 360;
			}
		}else{
			if(keyRotateRight.isPressed()){
				mc.vrSettings.vrWorldRotation-=mc.vrSettings.vrWorldRotationIncrement;
				mc.vrSettings.vrWorldRotation = mc.vrSettings.vrWorldRotation % 360;
			}
		}

		seatedRot = mc.vrSettings.vrWorldRotation;

		if(keyRadialMenu.isPressed()) {
			if(!gui) {
				RadialHandler.setOverlayShowing(!RadialHandler.isShowing(), findActiveBindingButton(keyRadialMenu));
			}
		}

		if (keySwapMirrorView.isPressed()) {
			if (mc.vrSettings.displayMirrorMode == VRSettings.MIRROR_THIRD_PERSON)
				mc.vrSettings.displayMirrorMode = VRSettings.MIRROR_FIRST_PERSON;
			else if (mc.vrSettings.displayMirrorMode == VRSettings.MIRROR_FIRST_PERSON)
				mc.vrSettings.displayMirrorMode = VRSettings.MIRROR_THIRD_PERSON;
			mc.stereoProvider.reinitFrameBuffers("Mirror Setting Changed");
		}

		if (keyToggleKeyboard.isPressed()) {
			KeyboardHandler.setOverlayShowing(!KeyboardHandler.Showing);
		}

		if (isBindingBound(keyMoveThirdPersonCam)) {
			if (keyMoveThirdPersonCam.isPressed() && !Main.kiosk && (mc.vrSettings.displayMirrorMode == VRSettings.MIRROR_MIXED_REALITY || mc.vrSettings.displayMirrorMode == VRSettings.MIRROR_THIRD_PERSON)) {
				TrackedController controller = MCOpenVR.findActiveBindingController(keyMoveThirdPersonCam);
				if (controller != null)
					VRHotkeys.startMovingThirdPersonCam(controller.getType().ordinal());
			}
			if (!keyMoveThirdPersonCam.isKeyDown() && VRHotkeys.isMovingThirdPersonCam()) {
				VRHotkeys.stopMovingThirdPersonCam();
				mc.vrSettings.saveOptions();
			}
		}

		if(keyMenuButton.isPressed()) { //handle menu directly
			if(!gui) {
				if(!Main.kiosk){
						mc.displayInGameMenu();
				}
			}
			KeyboardHandler.setOverlayShowing(false);
		}

		if (keyExportWorld.isPressed()) {
			if (mc.world != null && mc.player != null) {
				try {
					final BlockPos pos = mc.player.getPosition();
					final int size = 320;
					final File file = new File("worldexport.mmw");
					System.out.println("Exporting world... area size: " + size);
					System.out.println("Saving to " + file.getAbsolutePath());
					if (mc.isIntegratedServerRunning()) {
						final World world = mc.getIntegratedServer().getWorld(mc.player.dimension);
						ListenableFuture task = mc.getIntegratedServer().addScheduledTask(new Runnable() {
							@Override
							public void run() {
								try {
									MenuWorldExporter.saveAreaToFile(world, pos.getX() - size / 2, pos.getZ() - size / 2, size, size, pos.getY(), file);
								} catch (IOException e) {
									e.printStackTrace();
								}
							}
						});
						while (!task.isDone()) {
							Thread.sleep(10);
						}
					} else {
						MenuWorldExporter.saveAreaToFile(mc.world, pos.getX() - size / 2, pos.getZ() - size / 2, size, size, pos.getY(), file);
					}
					mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentString("World export complete... area size: " + size));
					mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentString("Saved to " + file.getAbsolutePath()));
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
		
		if(KeyboardHandler.Showing && mc.currentScreen == null && GuiHandler.keyMenuButton.isPressed()) { //super special case.
			KeyboardHandler.setOverlayShowing(false);
		}
		
		if(RadialHandler.isShowing() && GuiHandler.keyMenuButton.isPressed()) { //super special case.
			RadialHandler.setOverlayShowing(false, null);
		}

		if (mc.currentScreen != null) 
			GuiHandler.processBindingsGui();
	}


	public static void postProcessBindings() {
		outer: for (VRButtonMapping mapping : mc.vrSettings.buttonMappings.values()) {
			if (mapping.keyBinding != null) {
				
				for (ButtonTuple button : mapping.buttons) {
					if (button.isTouch && button.controller.getController().isButtonTouched(button.button))
						continue outer;
					if (!button.isTouch && button.controller.getController().isButtonPressed(button.button))
						continue outer;
				}
				activeBindings.remove(mapping.keyBinding.getKeyDescription());
			}
		}
	}

	private static void changeHotbar(int dir){
		if(mc.player == null || (mc.climbTracker.isGrabbingLadder() && 
				mc.climbTracker.isClaws(mc.player.getHeldItemMainhand()))) //never let go, jack.
		{}
		else{
			//if (Reflector.forgeExists() && mc.currentScreen == null && Display.isActive())
			//	KeyboardSimulator.robot.mouseWheel(-dir * 120);
			//else
				mc.player.inventory.changeCurrentItem(dir);
		}
	}

	private static String findEvent(int eventcode) {
		Field[] fields = EVREventType.class.getFields();

		for (Field field : fields) {
			if (field.getType() == Integer.TYPE) {
				String n = field.getName();
				int val;
				try {
					val = field.getInt(null);
					if(val == eventcode) return n;
				} catch (IllegalArgumentException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (IllegalAccessException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		return "";
	}

	// Valve why do we have to poll events before we can get updated controller state?
	private static void pollVREvents()
	{
		if (vrsystem == null) return;
		for (VREvent_t event = new VREvent_t(); vrsystem.PollNextEvent.apply(event, event.size()) > 0; event = new VREvent_t()) {
			vrEvents.add(event);
		}
	}

	//jrbuda:: oh hello there you are.
	private static void processVREvents() {
		while (!vrEvents.isEmpty()) {
			VREvent_t event = vrEvents.poll();
			//System.out.println("SteamVR Event: " + findEvent(event.eventType));

			switch (event.eventType) {
				/*case EVREventType.EVREventType_VREvent_KeyboardClosed:
					//'huzzah'
					keyboardShowing = false;
					if (mc.currentScreen instanceof GuiChat && !mc.vrSettings.seated) {
						GuiTextField field = (GuiTextField)MCReflection.getField(MCReflection.GuiChat_inputField, mc.currentScreen);
						if (field != null) {
							String s = field.getText().trim();
							if (!s.isEmpty()) {
								mc.currentScreen.sendChatMessage(s);
							}
						}
						//mc.displayGuiScreen((GuiScreen)null);
					}
					break;
				case EVREventType.EVREventType_VREvent_KeyboardCharInput:
					byte[] inbytes = event.data.getPointer().getByteArray(0, 8);
					int len = 0;
					for (byte b : inbytes) {
						if(b>0)len++;
					}
					String str = new String(inbytes,0,len, StandardCharsets.UTF_8);
					if (mc.currentScreen != null && !mc.vrSettings.alwaysSimulateKeyboard) { // experimental, needs testing
						try {
							for (char ch : str.toCharArray()) {
								int[] codes = KeyboardSimulator.getLWJGLCodes(ch);
								int code = codes.length > 0 ? codes[codes.length - 1] : 0;
								if (InputInjector.isSupported()) InputInjector.typeKey(code, ch);
								else mc.currentScreen.keyTypedPublic(ch, code);
								break;
							}
						} catch (Exception e) {
							e.printStackTrace();
						}
					} else {
						KeyboardSimulator.type(str); //holy shit it works.
					}
					break;*/
				case EVREventType.EVREventType_VREvent_ButtonTouch:
					if (!mc.vrSettings.seated) handleButtonEvent(event, true, false);
					break;
				case EVREventType.EVREventType_VREvent_ButtonPress:
					if (!mc.vrSettings.seated) handleButtonEvent(event, true, true);
					break;
				case EVREventType.EVREventType_VREvent_ButtonUntouch:
					if (!mc.vrSettings.seated) handleButtonEvent(event, false, false);
					break;
				case EVREventType.EVREventType_VREvent_ButtonUnpress:
					if (!mc.vrSettings.seated) handleButtonEvent(event, false, true);
					break;
				case EVREventType.EVREventType_VREvent_Quit:
					mc.shutdown();
					break;
				case EVREventType.EVREventType_VREvent_TrackedDeviceActivated:
				case EVREventType.EVREventType_VREvent_TrackedDeviceDeactivated:
				case EVREventType.EVREventType_VREvent_TrackedDeviceRoleChanged:
				case EVREventType.EVREventType_VREvent_TrackedDeviceUpdated:
				case EVREventType.EVREventType_VREvent_ModelSkinSettingsHaveChanged:
					getXforms = true;
					break;
				default:
					break;
			}
		}
	}

	private static void handleButtonEvent(VREvent_t event, boolean state, boolean press) {
		VREvent_Controller_t controllerEvent = new VREvent_Controller_t(event.data.getPointer());
		controllerEvent.read();
		if (event.trackedDeviceIndex == -1) return;
		if (controllerDeviceIndex[RIGHT_CONTROLLER] == event.trackedDeviceIndex) {
			controllers[RIGHT_CONTROLLER].processButtonEvent(controllerEvent.button, state, press);
		}
		if (controllerDeviceIndex[LEFT_CONTROLLER] == event.trackedDeviceIndex) {
			controllers[LEFT_CONTROLLER].processButtonEvent(controllerEvent.button, state, press);
		}
	}

	public static boolean isBound(KeyBinding binding) {
		VRButtonMapping vb = mc.vrSettings.buttonMappings.get(binding.getKeyDescription());
		return vb != null && vb.buttons.isEmpty() == false;
	}
	
	public static TrackedController findActiveBindingController(KeyBinding binding) {
		if (activeBindings.containsKey(binding.getKeyDescription())) {
			return activeBindings.get(binding.getKeyDescription()).controller.getController();
		}
		return null;
	}

	public static ButtonTuple findAnyBindingButton(KeyBinding binding) {
		ButtonTuple but = findActiveBindingButton(binding);
		if(but != null) return but;

		VRButtonMapping vb = mc.vrSettings.buttonMappings.get(binding.getKeyDescription());
		if (!vb.isModifierBinding() && (keyModifier1.isKeyDown() != vb.hasModifier(0) || keyModifier2.isKeyDown() != vb.hasModifier(1))) {
			return null;
		}
		for (ButtonTuple tuple : vb.buttons) {
			if (tuple.controller.getController().isButtonActive(tuple.button))
				return tuple;
		}
		return null;
	}



	public static ButtonTuple findActiveBindingButton(KeyBinding binding) {
		if (activeBindings.containsKey(binding.getKeyDescription())) {
			return activeBindings.get(binding.getKeyDescription());
		}
		return null;
	}

	public static boolean isBindingBound(KeyBinding binding) {
		VRButtonMapping vb = mc.vrSettings.buttonMappings.get(binding.getKeyDescription());
		for (ButtonTuple tuple : vb.buttons) {
			if (tuple.controller.getController().isButtonActive(tuple.button))
				return true;
		}
		return false;
	}

	public static void triggerBindingHapticPulse(KeyBinding binding, int duration) {
		TrackedController controller = findActiveBindingController(binding);
		if (controller != null) controller.triggerHapticPulse(duration);
	}

	public static void queueInputEvent(TrackedController controller, ButtonType button, AxisType axis, boolean buttonState, boolean buttonPress, Vector2 axisDelta) {
		vrInputEvents.add(new VRInputEvent(controller, button, axis, buttonState, buttonPress, axisDelta));
	}

	public static boolean hasNextInputEvent() {
		return !vrInputEvents.isEmpty();
	}

	public static VRInputEvent nextInputEvent() {
		return vrInputEvents.poll();
	}

	private static void updatePose()
	{
		if ( vrsystem == null || vrCompositor == null )
			return;

		int ret = vrCompositor.WaitGetPoses.apply(hmdTrackedDevicePoseReference, JOpenVRLibrary.k_unMaxTrackedDeviceCount, null, 0);

		if (ret>0)
			System.out.println("Compositor Error: GetPoseError " + OpenVRStereoRenderer.getCompostiorError(ret)); 

		if(ret == 101){ //this is so dumb but it works.
			triggerHapticPulse(0, 500);
			triggerHapticPulse(1, 500);
		}

		if (getXforms == true) { //set null by events.
			getTransforms(); //do we want the dynamic info? I don't think so...
			findControllerDevices(); 
		}

		for (int nDevice = 0; nDevice < JOpenVRLibrary.k_unMaxTrackedDeviceCount; ++nDevice )
		{
			hmdTrackedDevicePoses[nDevice].read();
			if ( hmdTrackedDevicePoses[nDevice].bPoseIsValid != 0 )
			{
				jopenvr.OpenVRUtil.convertSteamVRMatrix3ToMatrix4f(hmdTrackedDevicePoses[nDevice].mDeviceToAbsoluteTracking, poseMatrices[nDevice]);
				deviceVelocity[nDevice] = new Vec3d(hmdTrackedDevicePoses[nDevice].vVelocity.v[0],hmdTrackedDevicePoses[nDevice].vVelocity.v[1],hmdTrackedDevicePoses[nDevice].vVelocity.v[2]);
				if(mc.vrSettings.displayMirrorMode == VRSettings.MIRROR_MIXED_REALITY || mc.vrSettings.displayMirrorMode == VRSettings.MIRROR_THIRD_PERSON){
					if(controllerDeviceIndex[0]!= -1 && controllerDeviceIndex[1] != -1 ){
						int c = vrsystem.GetTrackedDeviceClass.apply(nDevice);
						int r = vrsystem.GetControllerRoleForTrackedDeviceIndex.apply(nDevice);
						if((c == 2 && r == 0) || c == 3) {
							controllerDeviceIndex[THIRD_CONTROLLER] = nDevice;
						}
					} 
				}
			}		
		}

		if (hmdTrackedDevicePoses[JOpenVRLibrary.k_unTrackedDeviceIndex_Hmd].bPoseIsValid != 0 )
		{
			OpenVRUtil.Matrix4fCopy(poseMatrices[JOpenVRLibrary.k_unTrackedDeviceIndex_Hmd], hmdPose);
			headIsTracking = true;
		}
		else
		{
			headIsTracking = false;
			OpenVRUtil.Matrix4fSetIdentity(hmdPose);
			hmdPose.M[1][3] = 1.62f;
		}

		for (int c=0;c<3;c++)
		{
			if (controllerDeviceIndex[c] != -1)
			{
				controllerTracking[c] = true;
				if (c < 2) controllers[c].tracking = true;
				OpenVRUtil.Matrix4fCopy(poseMatrices[controllerDeviceIndex[c]], controllerPose[c]);
			}
			else
			{
				controllerTracking[c] = false;
				if (c < 2) controllers[c].tracking = false;
				//OpenVRUtil.Matrix4fSetIdentity(controllerPose[c]);
			}
		}

		updateAim();
		//VRHotkeys.snapMRCam(mc, 0);

	}

	/**
	 * @return The coordinate of the 'center' eye position relative to the head yaw plane
	 */

	public static Vec3d getCenterEyePosition() {
		Vector3f pos = OpenVRUtil.convertMatrix4ftoTranslationVector(hmdPose);
		if (mc.vrSettings.seated || mc.vrSettings.allowStandingOriginOffset)
			pos=pos.add(offset);
		return new Vec3d(pos.x, pos.y, pos.z);
	}

	/**
	 * @return The coordinate of the left or right eye position relative to the head yaw plane
	 */

	public static Vec3d getEyePosition(RenderPass eye)
	{
		Matrix4f hmdToEye = hmdPoseRightEye;
		if ( eye == RenderPass.LEFT)
		{
			hmdToEye = hmdPoseLeftEye;
		} else if ( eye == RenderPass.RIGHT)
		{
			hmdToEye = hmdPoseRightEye;
		} else {
			hmdToEye = null;
		}

		if(hmdToEye == null){
			Matrix4f pose = hmdPose;
			Vector3f pos = OpenVRUtil.convertMatrix4ftoTranslationVector(pose);
			if (mc.vrSettings.seated || mc.vrSettings.allowStandingOriginOffset)
				pos=pos.add(offset);
			return new Vec3d(pos.x, pos.y, pos.z);
		} else {
			Matrix4f pose = Matrix4f.multiply( hmdPose, hmdToEye );
			Vector3f pos = OpenVRUtil.convertMatrix4ftoTranslationVector(pose);
			if (mc.vrSettings.seated || mc.vrSettings.allowStandingOriginOffset)
				pos=pos.add(offset);
			return new Vec3d(pos.x, pos.y, pos.z);
		}
	}

	/**
	 *
	 * @return Play area size or null if not valid
	 */
	public static float[] getPlayAreaSize() {
		if (vrChaperone == null || vrChaperone.GetPlayAreaSize == null) return null;
		FloatByReference bufz = new FloatByReference();
		FloatByReference bufx = new FloatByReference();
		byte valid = vrChaperone.GetPlayAreaSize.apply(bufx, bufz);
		if (valid == 1) return new float[]{bufx.getValue()*mc.vrSettings.walkMultiplier, bufz.getValue()*mc.vrSettings.walkMultiplier};
		return null;
	}

	/**
	 * Gets the orientation quaternion
	 *
	 * @return quaternion w, x, y & z components
	 */

	static EulerOrient getOrientationEuler()
	{
		Quatf orient = OpenVRUtil.convertMatrix4ftoRotationQuat(hmdPose);
		return OpenVRUtil.getEulerAnglesDegYXZ(orient);
	}

	final String k_pch_SteamVR_Section = "steamvr";
	final String k_pch_SteamVR_RenderTargetMultiplier_Float = "renderTargetMultiplier";



	//-------------------------------------------------------
	// IBodyAimController

	float getBodyPitchDegrees() {
		return 0; //Always return 0 for body pitch
	}

	public static Vec3d getAimVector( int controller ) {
		Vector3f v = controllerRotation[controller].transform(forward);
		return new Vec3d(v.x, v.y, v.z);

	}

	public static Vec3d getHmdVector() {
		Vector3f v = hmdRotation.transform(forward);
		return new Vec3d(v.x, v.y, v.z);
	}

	public static Vec3d getHandVector( int controller ) {
		Vector3f forward = new Vector3f(0,0,-1);
		Matrix4f aimRotation = handRotation[controller];
		Vector3f controllerDirection = aimRotation.transform(forward);
		Vec3d out = new Vec3d(controllerDirection.x, controllerDirection.y,controllerDirection.z);
		return out;
	}

	public static Matrix4f getAimRotation( int controller ) {
		return controllerRotation[controller];
	}

	public static Matrix4f getHandRotation( int controller ) {
		return handRotation[controller];
	}


	public boolean initBodyAim() throws Exception
	{
		return init();
	}


	public static Vec3d getAimSource( int controller ) {
		Vec3d out = new Vec3d(aimSource[controller].x, aimSource[controller].y, aimSource[controller].z);
		if(!mc.vrSettings.seated && mc.vrSettings.allowStandingOriginOffset)
			out = out.add(offset.x, offset.y, offset.z);
		return out;
	}

	public static void triggerHapticPulse(int controller, int strength) {
		if(Minecraft.getMinecraft().vrSettings.seated) return;
		if (controllerDeviceIndex[controller]==-1)
			return;
		vrsystem.TriggerHapticPulse.apply(controllerDeviceIndex[controller], 0, (short)strength);
	}
	
	public static void triggerHapticPulse(ControllerType controller, int strength) {
		triggerHapticPulse(controller.ordinal(), strength);
	}

	public static float seatedRot;

	public static Vector3f forward = new Vector3f(0,0,-1);
	static double aimPitch = 0; //needed for seated mode.


	private static void updateAim() {
		if (mc==null)
			return;

		{//hmd
			hmdRotation.M[0][0] = hmdPose.M[0][0];
			hmdRotation.M[0][1] = hmdPose.M[0][1];
			hmdRotation.M[0][2] = hmdPose.M[0][2];
			hmdRotation.M[0][3] = 0.0F;
			hmdRotation.M[1][0] = hmdPose.M[1][0];
			hmdRotation.M[1][1] = hmdPose.M[1][1];
			hmdRotation.M[1][2] = hmdPose.M[1][2];
			hmdRotation.M[1][3] = 0.0F;
			hmdRotation.M[2][0] = hmdPose.M[2][0];
			hmdRotation.M[2][1] = hmdPose.M[2][1];
			hmdRotation.M[2][2] = hmdPose.M[2][2];
			hmdRotation.M[2][3] = 0.0F;
			hmdRotation.M[3][0] = 0.0F;
			hmdRotation.M[3][1] = 0.0F;
			hmdRotation.M[3][2] = 0.0F;
			hmdRotation.M[3][3] = 1.0F;


			Vec3d eye = getCenterEyePosition();
			hmdHistory.add(eye);
			Vector3f v3 = MCOpenVR.hmdRotation.transform(new Vector3f(0,-.1f, .1f));
			hmdPivotHistory.add(new Vec3d(v3.x+eye.x, v3.y+eye.y, v3.z+eye.z));

		}

		{//right controller
			handRotation[0].M[0][0] = controllerPose[0].M[0][0];
			handRotation[0].M[0][1] = controllerPose[0].M[0][1];
			handRotation[0].M[0][2] = controllerPose[0].M[0][2];
			handRotation[0].M[0][3] = 0.0F;
			handRotation[0].M[1][0] = controllerPose[0].M[1][0];
			handRotation[0].M[1][1] = controllerPose[0].M[1][1];
			handRotation[0].M[1][2] = controllerPose[0].M[1][2];
			handRotation[0].M[1][3] = 0.0F;
			handRotation[0].M[2][0] = controllerPose[0].M[2][0];
			handRotation[0].M[2][1] = controllerPose[0].M[2][1];
			handRotation[0].M[2][2] = controllerPose[0].M[2][2];
			handRotation[0].M[2][3] = 0.0F;
			handRotation[0].M[3][0] = 0.0F;
			handRotation[0].M[3][1] = 0.0F;
			handRotation[0].M[3][2] = 0.0F;
			handRotation[0].M[3][3] = 1.0F;	

			if(mc.vrSettings.seated){
				controllerPose[0] = hmdPose.inverted().inverted();
				controllerPose[1] = hmdPose.inverted().inverted();
			} else	
				controllerPose[0] = Matrix4f.multiply(controllerPose[0], getControllerComponentTransform(0,"tip"));

			// grab controller position in tracker space, scaled to minecraft units
			Vector3f controllerPos = OpenVRUtil.convertMatrix4ftoTranslationVector(controllerPose[0]);
			aimSource[0] = new Vec3d(
					controllerPos.x,
					controllerPos.y,
					controllerPos.z);

			controllerHistory[0].add(aimSource[0]);

			// build matrix describing controller rotation
			controllerRotation[0].M[0][0] = controllerPose[0].M[0][0];
			controllerRotation[0].M[0][1] = controllerPose[0].M[0][1];
			controllerRotation[0].M[0][2] = controllerPose[0].M[0][2];
			controllerRotation[0].M[0][3] = 0.0F;
			controllerRotation[0].M[1][0] = controllerPose[0].M[1][0];
			controllerRotation[0].M[1][1] = controllerPose[0].M[1][1];
			controllerRotation[0].M[1][2] = controllerPose[0].M[1][2];
			controllerRotation[0].M[1][3] = 0.0F;
			controllerRotation[0].M[2][0] = controllerPose[0].M[2][0];
			controllerRotation[0].M[2][1] = controllerPose[0].M[2][1];
			controllerRotation[0].M[2][2] = controllerPose[0].M[2][2];
			controllerRotation[0].M[2][3] = 0.0F;
			controllerRotation[0].M[3][0] = 0.0F;
			controllerRotation[0].M[3][1] = 0.0F;
			controllerRotation[0].M[3][2] = 0.0F;
			controllerRotation[0].M[3][3] = 1.0F;

			Vec3d hdir = getHmdVector();

			if(mc.vrSettings.seated && mc.currentScreen == null){
				org.vivecraft.utils.lwjgl.Matrix4f temp = new org.vivecraft.utils.lwjgl.Matrix4f();

				float hRange = 110;
				float vRange = 180;
				double h = mc.mouseHelper.getMouseX() / (double) mc.mainWindow.getWidth() * hRange - (hRange / 2);
			
				h = MathHelper.clamp(h, -hRange/2, hRange/2);

				int hei  = mc.mainWindow.getHeight();
				if(hei % 2 != 0)
					hei-=1; //fix drifting vertical mouse.

				double v = -mc.mouseHelper.getMouseY() / (double) hei * vRange + (vRange / 2);		
				double nPitch=-v;
				if(mc.isGameFocused()){
					float rotStart = mc.vrSettings.keyholeX;
					float rotSpeed = 2000 * mc.vrSettings.xSensitivity;
					int leftedge=(int)((-rotStart + (hRange / 2)) *(double) mc.mainWindow.getWidth() / hRange )+1;
					int rightedge=(int)((rotStart + (hRange / 2)) *(double) mc.mainWindow.getWidth() / hRange )-1;
					float rotMul = ((float)Math.abs(h) - rotStart) / ((hRange / 2) - rotStart); // Scaled 0...1 from rotStart to FOV edge
					if(rotMul > 0.15) rotMul = 0.15f;

					double xpos = mc.mouseHelper.getMouseX();
					
					if(h < -rotStart){
						seatedRot += rotSpeed * rotMul * mc.getFrameDelta();
						seatedRot %= 360; // Prevent stupidly large values
						hmdForwardYaw = (float)Math.toDegrees(Math.atan2(hdir.x, hdir.z));   
						xpos = leftedge;
						h=-rotStart;
					}
					if(h > rotStart){
						seatedRot -= rotSpeed * rotMul * mc.getFrameDelta();
						seatedRot %= 360; // Prevent stupidly large values
						hmdForwardYaw = (float)Math.toDegrees(Math.atan2(hdir.x, hdir.z));    	
						xpos = rightedge;
						h=rotStart;
					}

					double ySpeed=0.5 * mc.vrSettings.ySensitivity;
					nPitch=aimPitch+(v)*ySpeed;
					nPitch=MathHelper.clamp(nPitch,-89.9,89.9);
							
					InputSimulator.setMousePos(xpos, hei/2);
					GLFW.glfwSetCursorPos(mc.mainWindow.getHandle(), xpos, hei/2);
					
					temp.rotate((float) Math.toRadians(-nPitch), new org.vivecraft.utils.lwjgl.Vector3f(1,0,0));
					temp.rotate((float) Math.toRadians(-180 + h - hmdForwardYaw), new org.vivecraft.utils.lwjgl.Vector3f(0,1,0));
				}


				controllerRotation[0].M[0][0] = temp.m00;
				controllerRotation[0].M[0][1] = temp.m01;
				controllerRotation[0].M[0][2] = temp.m02;

				controllerRotation[0].M[1][0] = temp.m10;
				controllerRotation[0].M[1][1] = temp.m11;
				controllerRotation[0].M[1][2] = temp.m12;

				controllerRotation[0].M[2][0] = temp.m20;
				controllerRotation[0].M[2][1] = temp.m21;
				controllerRotation[0].M[2][2] = temp.m22;
			}	

			Vec3d dir = getAimVector(0);
			aimPitch = (float)Math.toDegrees(Math.asin(dir.y/dir.length()));
		}

		{//left controller
			handRotation[1].M[0][0] = controllerPose[1].M[0][0];
			handRotation[1].M[0][1] = controllerPose[1].M[0][1];
			handRotation[1].M[0][2] = controllerPose[1].M[0][2];
			handRotation[1].M[0][3] = 0.0F;
			handRotation[1].M[1][0] = controllerPose[1].M[1][0];
			handRotation[1].M[1][1] = controllerPose[1].M[1][1];
			handRotation[1].M[1][2] = controllerPose[1].M[1][2];
			handRotation[1].M[1][3] = 0.0F;
			handRotation[1].M[2][0] = controllerPose[1].M[2][0];
			handRotation[1].M[2][1] = controllerPose[1].M[2][1];
			handRotation[1].M[2][2] = controllerPose[1].M[2][2];
			handRotation[1].M[2][3] = 0.0F;
			handRotation[1].M[3][0] = 0.0F;
			handRotation[1].M[3][1] = 0.0F;
			handRotation[1].M[3][2] = 0.0F;
			handRotation[1].M[3][3] = 1.0F;	

			// update off hand aim
			if(!mc.vrSettings.seated) 
				controllerPose[1] = Matrix4f.multiply(controllerPose[1], getControllerComponentTransform(1,"tip"));

			Vector3f leftControllerPos = OpenVRUtil.convertMatrix4ftoTranslationVector(controllerPose[1]);
			aimSource[1] = new Vec3d(
					leftControllerPos.x,
					leftControllerPos.y,
					leftControllerPos.z);
			controllerHistory[1].add(aimSource[1]);

			// build matrix describing controller rotation
			controllerRotation[1].M[0][0] = controllerPose[1].M[0][0];
			controllerRotation[1].M[0][1] = controllerPose[1].M[0][1];
			controllerRotation[1].M[0][2] = controllerPose[1].M[0][2];
			controllerRotation[1].M[0][3] = 0.0F;
			controllerRotation[1].M[1][0] = controllerPose[1].M[1][0];
			controllerRotation[1].M[1][1] = controllerPose[1].M[1][1];
			controllerRotation[1].M[1][2] = controllerPose[1].M[1][2];
			controllerRotation[1].M[1][3] = 0.0F;
			controllerRotation[1].M[2][0] = controllerPose[1].M[2][0];
			controllerRotation[1].M[2][1] = controllerPose[1].M[2][1];
			controllerRotation[1].M[2][2] = controllerPose[1].M[2][2];
			controllerRotation[1].M[2][3] = 0.0F;
			controllerRotation[1].M[3][0] = 0.0F;
			controllerRotation[1].M[3][1] = 0.0F;
			controllerRotation[1].M[3][2] = 0.0F;
			controllerRotation[1].M[3][3] = 1.0F;

			if(mc.vrSettings.seated){
				aimSource[1] = getCenterEyePosition();
				aimSource[0] = getCenterEyePosition();
			}

		}

		boolean debugThirdController = false;
		if(debugThirdController) controllerPose[2] = controllerPose[0];

		// build matrix describing controller rotation
		controllerRotation[2].M[0][0] = controllerPose[2].M[0][0];
		controllerRotation[2].M[0][1] = controllerPose[2].M[0][1];
		controllerRotation[2].M[0][2] = controllerPose[2].M[0][2];
		controllerRotation[2].M[0][3] = 0.0F;
		controllerRotation[2].M[1][0] = controllerPose[2].M[1][0];
		controllerRotation[2].M[1][1] = controllerPose[2].M[1][1];
		controllerRotation[2].M[1][2] = controllerPose[2].M[1][2];
		controllerRotation[2].M[1][3] = 0.0F;
		controllerRotation[2].M[2][0] = controllerPose[2].M[2][0];
		controllerRotation[2].M[2][1] = controllerPose[2].M[2][1];
		controllerRotation[2].M[2][2] = controllerPose[2].M[2][2];
		controllerRotation[2].M[2][3] = 0.0F;
		controllerRotation[2].M[3][0] = 0.0F;
		controllerRotation[2].M[3][1] = 0.0F;
		controllerRotation[2].M[3][2] = 0.0F;
		controllerRotation[2].M[3][3] = 1.0F;

		if(controllerDeviceIndex[THIRD_CONTROLLER]!=-1 && (mc.vrSettings.displayMirrorMode == VRSettings.MIRROR_MIXED_REALITY || mc.vrSettings.displayMirrorMode == VRSettings.MIRROR_THIRD_PERSON )|| debugThirdController) {
			mrMovingCamActive = true;
			Vector3f thirdControllerPos = OpenVRUtil.convertMatrix4ftoTranslationVector(controllerPose[2]);
			aimSource[2] = new Vec3d(
					thirdControllerPos.x,
					thirdControllerPos.y,
					thirdControllerPos.z);
		} else {
			mrMovingCamActive = false;
			aimSource[2] = new Vec3d(
					mc.vrSettings.vrFixedCamposX,
					mc.vrSettings.vrFixedCamposY,
					mc.vrSettings.vrFixedCamposZ);
		}


	}

	public static void debugOutput(){

	}

	public static double getCurrentTimeSecs()
	{
		return System.nanoTime() / 1000000000d;
	}

	public static HardwareType getHardwareType() {
		return mc.vrSettings.forceHardwareDetection > 0 ? HardwareType.values()[mc.vrSettings.forceHardwareDetection - 1] : detectedHardware;
	}

	public static boolean isVive() {
		switch (getHardwareType()) {
		case VIVE:
		case WINDOWSMR:
			return true;
		default:
			return false;
		}
	}
	
	private static boolean gunStyle = false; 
	
	public static boolean isGunStyle() {
		return gunStyle;
	}

	public static void resetPosition() {
		Vec3d pos= getCenterEyePosition().scale(-1).add(offset.x,offset.y,offset.z);
		offset=new Vector3f((float) pos.x,(float)pos.y+1.62f,(float)pos.z);
	}

	public static void clearOffset() {
		offset=new Vector3f(0,0,0);
	}

	public static boolean isVivecraftBinding(KeyBinding kb) {
		return kb.getKeyCategory().startsWith("Vivecraft");
	}

	public static boolean isHMDTracking() {
		return headIsTracking;
	}
}
