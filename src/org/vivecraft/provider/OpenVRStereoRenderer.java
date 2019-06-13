package org.vivecraft.provider;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import org.vivecraft.gameplay.screenhandlers.GuiHandler;
import org.vivecraft.gameplay.screenhandlers.KeyboardHandler;
import org.vivecraft.gameplay.screenhandlers.RadialHandler;
import org.vivecraft.render.RenderConfigException;
import org.vivecraft.render.ShaderHelper;
import org.vivecraft.render.VRShaders;
import org.vivecraft.render.RenderPass;
import org.vivecraft.settings.VRSettings;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import de.fruitfly.ovr.enums.EyeType;
import de.fruitfly.ovr.structs.FovPort;
import de.fruitfly.ovr.structs.GLConfig;
import de.fruitfly.ovr.structs.Matrix4f;
import de.fruitfly.ovr.structs.RenderTextureInfo;
import de.fruitfly.ovr.structs.RenderTextureSet;
import de.fruitfly.ovr.structs.Sizei;
import jopenvr.HiddenAreaMesh_t;
import jopenvr.HmdMatrix44_t;
import jopenvr.JOpenVRLibrary;
import jopenvr.JOpenVRLibrary.EVRCompositorError;
import jopenvr.OpenVRUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.src.Config;
import net.minecraft.world.dimension.DimensionType;
import net.optifine.shaders.Shaders;

import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
/**
 * Created by jrbudda
 */
public class OpenVRStereoRenderer 
{
	
	public Framebuffer framebufferVrRender;
	public Framebuffer framebufferMR;
	public Framebuffer framebufferUndistorted;
	public Framebuffer framebufferEye0;
	public Framebuffer framebufferEye1;
	public Framebuffer mirrorFB = null;
	public Framebuffer fsaaFirstPassResultFBO;
	public Framebuffer fsaaLastPassResultFBO;
	
	public FloatBuffer[] eyeproj = new FloatBuffer[2]; //i dislike you.
	public FloatBuffer[] cloudeyeproj = new FloatBuffer[2]; //i dislike you too.

	public int mirrorFBWidth;     /* Actual width of the display buffer */
	public int mirrorFBHeight;  
	
	public int lastDisplayFBWidth = 0;
	public int lastDisplayFBHeight = 0;
	public long lastWindow = 0;
	public int lastRenderDistanceChunks = -1;
	public boolean lastFogFancy = true;
	public boolean lastFogFast = false;
	public float lastWorldScale = 0f;
	public boolean lastEnableVsync = true;
	public DimensionType lastDimensionId = DimensionType.OVERWORLD;
	public int lastGuiScale = 0;
	
	public static final String RENDER_SETUP_FAILURE_MESSAGE = "Failed to initialise stereo rendering plugin: ";

	private boolean reinitFramebuffers = true;
	public boolean reinitShadersFlag = false;
	
	// TextureIDs of framebuffers for each eye
	private int LeftEyeTextureId, RightEyeTextureId;

	private HiddenAreaMesh_t[] hiddenMeshes = new HiddenAreaMesh_t[2];
	private float[][] hiddenMesheVertecies = new float[2][];
	
	public GLConfig glConfig = new GLConfig();

	public RenderTextureInfo getRenderTextureSizes(float renderScaleFactor)
	{
		IntByReference rtx = new IntByReference();
		IntByReference rty = new IntByReference();
		MCOpenVR.vrsystem.GetRecommendedRenderTargetSize.apply(rtx, rty);

		RenderTextureInfo info = new RenderTextureInfo();
		info.HmdNativeResolution.w = rtx.getValue();
		info.HmdNativeResolution.h = rty.getValue();
		info.LeftFovTextureResolution.w = (int) (rtx.getValue());
		info.LeftFovTextureResolution.h = (int) (rty.getValue());
		info.RightFovTextureResolution.w = (int) (rtx.getValue());
		info.RightFovTextureResolution.h = (int) (rty.getValue());
		if ( info.LeftFovTextureResolution.w % 2 != 0) info.LeftFovTextureResolution.w++;
		if ( info.LeftFovTextureResolution.h % 2 != 0) info.LeftFovTextureResolution.w++;
		if ( info.RightFovTextureResolution.w % 2 != 0) info.LeftFovTextureResolution.w++;
		if ( info.RightFovTextureResolution.h % 2 != 0) info.LeftFovTextureResolution.w++;

		info.CombinedTextureResolution.w = info.LeftFovTextureResolution.w + info.RightFovTextureResolution.w;
		info.CombinedTextureResolution.h = info.LeftFovTextureResolution.h;

		for (int i = 0; i < 2; i++) {
			hiddenMeshes[i] = MCOpenVR.vrsystem.GetHiddenAreaMesh.apply(i,0);
			hiddenMeshes[i].read();
			int tc = hiddenMeshes[i].unTriangleCount;
			if(tc >0){
				hiddenMesheVertecies[i] = new float[hiddenMeshes[i].unTriangleCount * 3 * 2];
				Pointer arrptr = new Memory(hiddenMeshes[i].unTriangleCount * 3 * 2);
				hiddenMeshes[i].pVertexData.getPointer().read(0, hiddenMesheVertecies[i], 0, hiddenMesheVertecies[i].length);
	
				for (int ix = 0;ix < hiddenMesheVertecies[i].length;ix+=2) {
					hiddenMesheVertecies[i][ix] = hiddenMesheVertecies[i][ix] * info.LeftFovTextureResolution.w;
					hiddenMesheVertecies[i][ix + 1] = hiddenMesheVertecies[i][ix +1] * info.LeftFovTextureResolution.h;
				}
				System.out.println("Stencil mesh loaded for eye " + i);
			} else {
				System.out.println("No stencil mesh found for eye " + i);
			}
		}

		return info;
	}

	public Matrix4f getProjectionMatrix(FovPort fov,
			int eyeType,
			float nearClip,
			float farClip)
	{
		if ( eyeType == 0 )
		{
			HmdMatrix44_t mat = MCOpenVR.vrsystem.GetProjectionMatrix.apply(JOpenVRLibrary.EVREye.EVREye_Eye_Left, nearClip, farClip);
			MCOpenVR.texType0.depth.mProjection = mat;
			MCOpenVR.texType0.depth.write();
			MCOpenVR.hmdProjectionLeftEye = new Matrix4f();
			return OpenVRUtil.convertSteamVRMatrix4ToMatrix4f(mat, MCOpenVR.hmdProjectionLeftEye);
		}else{
			HmdMatrix44_t mat = MCOpenVR.vrsystem.GetProjectionMatrix.apply(JOpenVRLibrary.EVREye.EVREye_Eye_Right, nearClip, farClip);
			MCOpenVR.texType1.depth.mProjection = mat;
			MCOpenVR.texType1.depth.write();
			MCOpenVR.hmdProjectionRightEye = new Matrix4f();
			return OpenVRUtil.convertSteamVRMatrix4ToMatrix4f(mat, MCOpenVR.hmdProjectionRightEye);
		}
	}


	public EyeType eyeRenderOrder(int index)
	{
		return ( index == 1 ) ? EyeType.ovrEye_Right : EyeType.ovrEye_Left;
	}

	public double getFrameTiming() {
		return getCurrentTimeSecs();
	}

	public void deleteRenderTextures() {
		if (LeftEyeTextureId > 0)	GL11.glDeleteTextures(LeftEyeTextureId);
	}

	public String getLastError() { return ""; }

	public boolean setCurrentRenderTextureInfo(int index, int textureIdx, int depthId, int depthWidth, int depthHeight)
	{
		return true;
	}
	
	public double getCurrentTimeSecs()
	{
		return System.nanoTime() / 1000000000d;
	}


	public boolean providesMirrorTexture() { return false; }

	public int createMirrorTexture(int width, int height) { return -1; }

	public void deleteMirrorTexture() { }

	public boolean providesRenderTextures() { return true; }

	public RenderTextureSet createRenderTexture(int lwidth, int lheight)
	{	
		// generate left eye texture
		LeftEyeTextureId = GL11.glGenTextures();
		int boundTextureId = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, LeftEyeTextureId);
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
		GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, lwidth, lheight, 0, GL11.GL_RGBA, GL11.GL_INT, (java.nio.ByteBuffer) null);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, boundTextureId);

		MCOpenVR.texType0.handle= Pointer.createConstant(LeftEyeTextureId);
		MCOpenVR.texType0.eColorSpace = JOpenVRLibrary.EColorSpace.EColorSpace_ColorSpace_Gamma;
		MCOpenVR.texType0.eType = JOpenVRLibrary.ETextureType.ETextureType_TextureType_OpenGL;
		MCOpenVR.texType0.write();
		
		// generate right eye texture
		RightEyeTextureId = GL11.glGenTextures();
		boundTextureId = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, RightEyeTextureId);
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
		GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, lwidth, lheight, 0, GL11.GL_RGBA, GL11.GL_INT, (java.nio.ByteBuffer) null);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, boundTextureId);

		MCOpenVR.texType1.handle=Pointer.createConstant(RightEyeTextureId);
		MCOpenVR.texType1.eColorSpace = JOpenVRLibrary.EColorSpace.EColorSpace_ColorSpace_Gamma;
		MCOpenVR.texType1.eType = JOpenVRLibrary.ETextureType.ETextureType_TextureType_OpenGL;
		MCOpenVR.texType1.write();

		RenderTextureSet textureSet = new RenderTextureSet();
		textureSet.leftEyeTextureIds.add(LeftEyeTextureId);
		textureSet.rightEyeTextureIds.add(RightEyeTextureId);
		return textureSet;
	}

	public void configureRenderer(GLConfig cfg) {

	}

	public boolean endFrame(RenderPass eye)
	{
		return true;
	}

	
	public void endFrame() throws RenderConfigException {

		if(MCOpenVR.vrCompositor.Submit == null) return;
		
		int lret = MCOpenVR.vrCompositor.Submit.apply(
				JOpenVRLibrary.EVREye.EVREye_Eye_Left,
				MCOpenVR.texType0, null,
				JOpenVRLibrary.EVRSubmitFlags.EVRSubmitFlags_Submit_Default);

		int rret = MCOpenVR.vrCompositor.Submit.apply(
				JOpenVRLibrary.EVREye.EVREye_Eye_Right,
				MCOpenVR.texType1, null,
				JOpenVRLibrary.EVRSubmitFlags.EVRSubmitFlags_Submit_Default);


		MCOpenVR.vrCompositor.PostPresentHandoff.apply();
		
		if(lret + rret > 0){
			throw new RenderConfigException("Compositor Error","Texture submission error: Left/Right " + getCompostiorError(lret) + "/" + getCompostiorError(rret));		
		}
	}

	
	public static String getCompostiorError(int code){
		switch (code){
		case EVRCompositorError.EVRCompositorError_VRCompositorError_DoNotHaveFocus:
			return "DoesNotHaveFocus";
		case EVRCompositorError.EVRCompositorError_VRCompositorError_IncompatibleVersion:
			return "IncompatibleVersion";
		case EVRCompositorError.EVRCompositorError_VRCompositorError_IndexOutOfRange:
			return "IndexOutOfRange";
		case EVRCompositorError.EVRCompositorError_VRCompositorError_InvalidTexture:
			return "InvalidTexture";
		case EVRCompositorError.EVRCompositorError_VRCompositorError_IsNotSceneApplication:
			return "IsNotSceneApplication";
		case EVRCompositorError.EVRCompositorError_VRCompositorError_RequestFailed:
			return "RequestFailed";
		case EVRCompositorError.EVRCompositorError_VRCompositorError_SharedTexturesNotSupported:
			return "SharedTexturesNotSupported";
		case EVRCompositorError.EVRCompositorError_VRCompositorError_TextureIsOnWrongDevice:
			return "TextureIsOnWrongDevice";
		case EVRCompositorError.EVRCompositorError_VRCompositorError_TextureUsesUnsupportedFormat:
			return "TextureUsesUnsupportedFormat:";
		case EVRCompositorError.EVRCompositorError_VRCompositorError_None:
			return "None:";
		case EVRCompositorError.EVRCompositorError_VRCompositorError_AlreadySubmitted:
			return "AlreadySubmitted:";
		}
		return "Unknown";
	}

	
	public boolean providesStencilMask() {
		return true;
	}

	public float[] getStencilMask(RenderPass eye) {
		if(hiddenMesheVertecies == null || eye == RenderPass.CENTER || eye == RenderPass.THIRD) return null;
		return eye == RenderPass.LEFT ? hiddenMesheVertecies[0] : hiddenMesheVertecies[1];
	}

	public String getName() {
		return "OpenVR";
	}

	public boolean isInitialized() {
		return MCOpenVR.initSuccess;
	}

	public String getinitError() {
		return MCOpenVR.initStatus;
	}
	
    private void checkGLError(String message)
    {
    	Config.checkGlError(message);
    }

	public boolean clipPlanesChanged()
	{	
		return false;
		//TODO: Update
		
//		boolean changed = false;
//
//		if (this.world != null && this.world.provider != null)
//		{
//			if (this.world.provider.getDimensionType() != this.lastDimensionId)
//			{
//				changed = true;
//			}
//		}
//
//		if( this.gameSettings.renderDistanceChunks != this.lastRenderDistanceChunks ||
//				Config.isFogFancy() != this.lastFogFancy                                ||
//				Config.isFogFast() != this.lastFogFast)
//		{
//			changed = true;
//		}
//
//		
//		lastRenderDistanceChunks = mc.gameSettings.renderDistanceChunks;
//		lastFogFancy = Config.isFogFancy();
//		lastFogFast = Config.isFogFast();
//		if (this.world != null && this.world.provider != null)
//			lastDimensionId = this.world.provider.getDimensionType();
//
//		return changed;
	}

	public void doFSAA(boolean hasShaders) {
		if (this.fsaaFirstPassResultFBO == null){
			this.reinitFrameBuffers("FSAA Setting Changed");
			return;
		} else {

			GlStateManager.disableAlphaTest();
			GlStateManager.disableBlend();
			
			// Setup ortho projection
			GL11.glMatrixMode(GL11.GL_PROJECTION);
			GL11.glPushMatrix();
				GL11.glLoadIdentity();
				GL11.glMatrixMode(GL11.GL_MODELVIEW);
				GL11.glPushMatrix();
					GL11.glLoadIdentity();

					GL11.glTranslatef(0.0f, 0.0f, -.7f);
					// Pass 1 - horizontal
					// Now switch to 1st pass FSAA result target framebuffer
					this.fsaaFirstPassResultFBO.bindFramebuffer(true);
					
					// bind color and depth textures
					GlStateManager.activeTexture(GL13.GL_TEXTURE1);
					framebufferVrRender.bindFramebufferTexture();
					GlStateManager.activeTexture(GL13.GL_TEXTURE2);			
					if (hasShaders) 
						GlStateManager.bindTexture(Shaders.dfbDepthTextures.get(0)); // shadersmod has it's own depth buffer
					else 
						GlStateManager.bindTexture(framebufferVrRender.depthBuffer);
					GlStateManager.activeTexture(GL13.GL_TEXTURE0);

					
					GlStateManager.clearColor(1, 1, 1, 1.0f);
					GlStateManager.clearDepth(1.0D);
					GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);            // Clear Screen And Depth Buffer on the framebuffer

					// Render onto the entire screen framebuffer
					GlStateManager.viewport(0, 0, fsaaFirstPassResultFBO.framebufferWidth, fsaaFirstPassResultFBO.framebufferHeight);

					// Set the downsampling shader as in use
					ARBShaderObjects.glUseProgramObjectARB(VRShaders._Lanczos_shaderProgramId);
				
					// Set up the fragment shader uniforms
					ARBShaderObjects.glUniform1fARB(VRShaders._Lanczos_texelWidthOffsetUniform, 1.0f / (3.0f * (float) fsaaFirstPassResultFBO.framebufferWidth));
					ARBShaderObjects.glUniform1fARB(VRShaders._Lanczos_texelHeightOffsetUniform, 0.0f);
					ARBShaderObjects.glUniform1iARB(VRShaders._Lanczos_inputImageTextureUniform, 1);
					ARBShaderObjects.glUniform1iARB(VRShaders._Lanczos_inputDepthTextureUniform, 2);
					
					GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT);
					
					
					drawQuad();

					// checkGLError("After Lanczos Pass1");

					// Pass 2 - Vertial
					// Now switch to 2nd pass screen framebuffer
					
					fsaaLastPassResultFBO.bindFramebuffer(true);				
					
					// bind color and depth textures
					GlStateManager.activeTexture(GL13.GL_TEXTURE1);
					fsaaFirstPassResultFBO.bindFramebufferTexture();
					GlStateManager.activeTexture(GL13.GL_TEXTURE2);			
					GlStateManager.bindTexture(fsaaFirstPassResultFBO.depthBuffer);
					GlStateManager.activeTexture(GL13.GL_TEXTURE0);
					//					
					
					checkGLError("posttex");
					
					GlStateManager.viewport(0, 0, fsaaLastPassResultFBO.framebufferWidth, fsaaLastPassResultFBO.framebufferHeight);
					
					GlStateManager.clearColor(1, 1, 1, 1.0f);
					GlStateManager.clearDepth(1.0D);
					GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
					checkGLError("postclear");
					// Bind the texture
					GL13.glActiveTexture(GL13.GL_TEXTURE0);
					checkGLError("postact");
					// Set up the fragment shader uniforms for pass 2
					ARBShaderObjects.glUniform1fARB(VRShaders._Lanczos_texelWidthOffsetUniform, 0.0f);
					ARBShaderObjects.glUniform1fARB(VRShaders._Lanczos_texelHeightOffsetUniform, 1.0f / (3.0f * (float) framebufferEye0.framebufferHeight));
					ARBShaderObjects.glUniform1iARB(VRShaders._Lanczos_inputImageTextureUniform, 1);
					ARBShaderObjects.glUniform1iARB(VRShaders._Lanczos_inputDepthTextureUniform, 2);
					
					drawQuad();

					checkGLError("postdraw");
					
					// Stop shader use
					ARBShaderObjects.glUseProgramObjectARB(0);
					// checkGLError("After Lanczos Pass2");
						
					GlStateManager.enableAlphaTest();
					GlStateManager.enableBlend();

					GL11.glMatrixMode(GL11.GL_PROJECTION);
					GL11.glPopMatrix();		
				GL11.glMatrixMode(GL11.GL_MODELVIEW);
				GL11.glPopMatrix();
		}
	}

	private int dispLastWidth, dispLastHeight;
	public boolean wasDisplayResized()
	{
		Minecraft mc = Minecraft.getMinecraft();

		int h = mc.mainWindow.getHeight();
		int w = mc.mainWindow.getWidth();
		
		boolean was = dispLastHeight != h || dispLastWidth != w;
		dispLastHeight = h;
		dispLastWidth = w;
		return was;
	}
	
	public void setupRenderConfiguration() throws Exception 
	{
		Minecraft mc = Minecraft.getMinecraft();
		boolean changeNonDestructiveRenderConfig = false;

		if (clipPlanesChanged())
		{
			reinitFrameBuffers("Clip Planes Changed");
		}

		//why?
//		if (!Display.isActive() && fullscreen)
//		{
//			toggleFullscreen();
//			reinitFramebuffers = true;
//		}

//		if (wasDisplayResized())
//		{
//			//Display.update();     // This will set new display widths accordingly
//			reinitFrameBuffers("Display Resized");
//		}
		
		if (lastGuiScale != mc.gameSettings.guiScale)
		{
			lastGuiScale = mc.gameSettings.guiScale;
			reinitFrameBuffers("GUI Scale Changed");
		}

		//mc.showNativeMouseCursor(!mc.isGameFocused());

		// Check for changes in window handle
		if (mc.mainWindow.getHandle() != lastWindow)
		{
			lastWindow = mc.mainWindow.getHandle();
			reinitFrameBuffers("Window Handle Changed");
		}

//		if (lastShaderIndex != mc.vrSettings.shaderIndex) {
//			reinitFramebuffers = true;
//		}

		if (lastEnableVsync != mc.gameSettings.enableVsync) {
			reinitFrameBuffers("VSync Changed");
		}

		if (reinitFramebuffers)
		{
			//visible = true;
			this.reinitShadersFlag = true;
			checkGLError("Start Init");

			int displayFBWidth = (mc.mainWindow.getWidth() < 1) ? 1 : mc.mainWindow.getWidth();
			int displayFBHeight = (mc.mainWindow.getHeight()  < 1) ? 1 : mc.mainWindow.getHeight();
				
			Sizei EyeTextureSize = new Sizei(); 
			
			EyeTextureSize.w = displayFBWidth;
			EyeTextureSize.h = displayFBHeight;

			FovPort leftFov = null;
			FovPort rightFov = null;

			if (!isInitialized()) {
				throw new RenderConfigException(RENDER_SETUP_FAILURE_MESSAGE + getName(), " " + getinitError());
			}

			RenderTextureInfo renderTextureInfo = getRenderTextureSizes(mc.vrSettings.renderScaleFactor);

			EyeTextureSize.w  = renderTextureInfo.LeftFovTextureResolution.w ;
			EyeTextureSize.h  = renderTextureInfo.LeftFovTextureResolution.h ;

			if (framebufferVrRender != null) {
				framebufferVrRender.deleteFramebuffer();
				framebufferVrRender = null;
			}

			if (framebufferMR != null) {
				framebufferMR.deleteFramebuffer();
				framebufferMR = null;
			}

			if (framebufferUndistorted != null) {
				framebufferUndistorted.deleteFramebuffer();
				framebufferUndistorted = null;
			}

			if (framebufferEye0 != null) {
				framebufferEye0.deleteFramebuffer();
				framebufferEye0 = null;
			}
			
			if (framebufferEye1 != null) {
				framebufferEye1.deleteFramebuffer();
				framebufferEye1 = null;
			}
			
			deleteRenderTextures(); ///TODO should this do something.. ?

			if (GuiHandler.guiFramebuffer != null) {
				GuiHandler.guiFramebuffer.deleteFramebuffer();
				GuiHandler.guiFramebuffer = null;
			}
			
			if (KeyboardHandler.Framebuffer != null) {
				KeyboardHandler.Framebuffer.deleteFramebuffer();
				KeyboardHandler.Framebuffer = null;
			}

			//if (loadingScreen != null) {
			//	loadingScreen.deleteFramebuffer();
			//}

			if (mirrorFB != null) {
				mirrorFB.deleteFramebuffer();
				mirrorFB = null;
			}

			deleteMirrorTexture(); 

			if (fsaaFirstPassResultFBO != null) {
				fsaaFirstPassResultFBO.deleteFramebuffer();
				fsaaFirstPassResultFBO = null;
			}
		
			if (fsaaLastPassResultFBO != null) {
				fsaaLastPassResultFBO.deleteFramebuffer();
				fsaaLastPassResultFBO = null;
			}
			
			int multiSampleCount = 0;
			boolean multiSample = (multiSampleCount > 0 ? true : false);
					
			checkGLError("Mirror framebuffer setup");

			int tex0 = -1, tex1 = -1;

			if (providesRenderTextures())
			{ //always true
				// Source render textures
				RenderTextureSet renderTextures = createRenderTexture(
						EyeTextureSize.w,
						EyeTextureSize.h);
				if (renderTextures == null) {
					throw new RenderConfigException(RENDER_SETUP_FAILURE_MESSAGE + getName(), getLastError());
				}
				mc.print("L Render texture resolution: " + EyeTextureSize.w + " x " + EyeTextureSize.h);
				mc.print("Provider supplied render texture IDs:\n" + renderTextures.toString());

			    tex0 = renderTextures.leftEyeTextureIds.get(0);
				tex1 = renderTextures.rightEyeTextureIds.get(0);

			}
			else
			{
				// Generate our textures
				//renderTexProvider.genTextureIds(GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_INT, EyeTextureSize.w, EyeTextureSize.h, 1);
			}
			checkGLError("Render Texture setup");

			
			if (tex0 == -1 || tex1 == -1) {
				throw new Exception("Failed to create eye textures");
			}
			
			framebufferEye0 = new Framebuffer("L Eye", EyeTextureSize.w, EyeTextureSize.h, true,  false, false, 0, tex0, false);
			mc.print(framebufferEye0.toString());
			checkGLError("Left Eye framebuffer setup");
			
			framebufferEye1 = new Framebuffer("R Eye", EyeTextureSize.w, EyeTextureSize.h, true,  false, false,0, tex1, false);
			mc.print(framebufferEye1.toString());
			checkGLError("Right Eye framebuffer setup");
			
			MCOpenVR.texType0.depth.handle = Pointer.createConstant(framebufferEye0.depthBuffer);	
			MCOpenVR.texType1.depth.handle = Pointer.createConstant(framebufferEye1.depthBuffer);	

			displayFBWidth = (int) Math.ceil(EyeTextureSize.w * mc.vrSettings.renderScaleFactor);
			displayFBHeight = (int) Math.ceil(EyeTextureSize.h * mc.vrSettings.renderScaleFactor);
			
			framebufferVrRender = new Framebuffer("3D Render", displayFBWidth , displayFBHeight, true, false);
			mc.print(framebufferVrRender.toString());
			checkGLError("3D framebuffer setup");
			
			mirrorFBWidth = mc.mainWindow.getFramebufferWidth();
			mirrorFBHeight = mc.mainWindow.getFramebufferHeight();
			if (mc.vrSettings.displayMirrorMode == VRSettings.MIRROR_MIXED_REALITY) {
				mirrorFBWidth = mc.mainWindow.getFramebufferWidth() / 2;
				if(mc.vrSettings.mixedRealityUnityLike)
					mirrorFBHeight = mc.mainWindow.getFramebufferHeight() / 2;
			}

			if (Config.isShaders()) {
				mirrorFBWidth = displayFBWidth;
				mirrorFBHeight = displayFBHeight;
			}

			List<RenderPass> renderPasses = getRenderPasses();

			//debug
			for (RenderPass renderPass : renderPasses) {
				System.out.println("Passes: " + renderPass.toString());
			}
			
			if (renderPasses.contains(RenderPass.THIRD)) {
				framebufferMR = new Framebuffer("Mixed Reality Render", mirrorFBWidth, mirrorFBHeight, true, false);
				mc.print(framebufferMR.toString());
				checkGLError("Mixed reality framebuffer setup");
			}
			
			if (renderPasses.contains(RenderPass.CENTER)) {
				framebufferUndistorted = new Framebuffer("Undistorted View Render", mirrorFBWidth, mirrorFBHeight, true, false);
				mc.print(framebufferUndistorted.toString());
				checkGLError("Undistorted view framebuffer setup");
			}
			
			GuiHandler.guiFramebuffer  = new Framebuffer("GUI", mc.mainWindow.getFramebufferWidth(), mc.mainWindow.getFramebufferHeight(), true, true);
			mc.print(GuiHandler.guiFramebuffer.toString());
			checkGLError("GUI framebuffer setup");

			KeyboardHandler.Framebuffer  = new Framebuffer("Keyboard",  mc.mainWindow.getFramebufferWidth(), mc.mainWindow.getFramebufferHeight(), true, true);
			mc.print(KeyboardHandler.Framebuffer.toString());
			checkGLError("Keyboard framebuffer setup");

			RadialHandler.Framebuffer  = new Framebuffer("Radial Menu",  mc.mainWindow.getFramebufferWidth(), mc.mainWindow.getFramebufferHeight(), true, true);
			mc.print(RadialHandler.Framebuffer.toString());
			checkGLError("Radial framebuffer setup");

			checkGLError("post color");
			
			mc.entityRenderer.setupClipPlanes();

			eyeproj[0] = getProjectionMatrix(null, 0, mc.entityRenderer.minClipDistance, mc.entityRenderer.clipDistance).transposed().toFloatBuffer();
			eyeproj[1] = getProjectionMatrix(null, 1, mc.entityRenderer.minClipDistance, mc.entityRenderer.clipDistance).transposed().toFloatBuffer();
			cloudeyeproj[0] = getProjectionMatrix(null, 0, mc.entityRenderer.minClipDistance, mc.entityRenderer.clipDistance * 4).transposed().toFloatBuffer();
			cloudeyeproj[1] = getProjectionMatrix(null, 1, mc.entityRenderer.minClipDistance, mc.entityRenderer.clipDistance * 4).transposed().toFloatBuffer();

			if (mc.vrSettings.useFsaa)
			{
				try //setup fsaa
				{

					// GL21.GL_SRGB8_ALPHA8
					// GL11.GL_RGBA8
					checkGLError("pre FSAA FBO creation");
					// Lanczos downsample FBOs
					fsaaFirstPassResultFBO = new Framebuffer("FSAA Pass1 FBO",EyeTextureSize.w, displayFBHeight, true, false,false, 0, -1, true);
					//TODO: ugh, support multiple color attachments in Framebuffer....
					fsaaLastPassResultFBO = new Framebuffer("FSAA Pass2 FBO",EyeTextureSize.w, EyeTextureSize.h, true, false,false, 0, -1, true);
			
					mc.print(fsaaFirstPassResultFBO.toString());
					mc.print(fsaaLastPassResultFBO.toString());

					checkGLError("FSAA FBO creation");

					VRShaders.setupFSAA();

					ShaderHelper.checkGLError("FBO init fsaa shader");
				}

				catch (Exception ex)
				{
					// We had an issue. Set the usual suspects to defaults...
					mc.vrSettings.useFsaa = false;
					mc.vrSettings.saveOptions();
					System.out.println(ex.getMessage());
					reinitFramebuffers = true;
					return;
				}
			}
			
			try { //setup other shaders
				mc.framebuffer = this.framebufferVrRender;
				VRShaders.setupDepthMask();
				ShaderHelper.checkGLError("init depth shader");
				VRShaders.setupFOVReduction();
				ShaderHelper.checkGLError("init FOV shader");			
		        mc.renderGlobal.makeEntityOutlineShader();
			} catch (Exception e) {
				System.out.println(e.getMessage());
				System.exit(-1);
			}
			
			// Init screen size
			if (mc.currentScreen != null)
			{
				int k = mc.mainWindow.getScaledWidth();
				int l = mc.mainWindow.getScaledHeight();
				mc.currentScreen.setWorldAndResolution(mc, k, l);
			}


			System.out.println("[Minecrift] New render config:" +
					"\nRender target width:  " + (true ? EyeTextureSize.w + EyeTextureSize.w: mc.mainWindow.getWidth()) +
					", height: " + (true ? Math.max(EyeTextureSize.h, EyeTextureSize.h) : mc.mainWindow.getHeight()) +
					(true ? " [Render scale: " + mc.vrSettings.renderScaleFactor + "]" : "") +
					(mc.vrSettings.useFsaa ? " [FSAA Scale: " + mc.vrSettings.renderScaleFactor + "]" : "") +
					"\nDisplay target width: " + displayFBWidth + ", height: " + displayFBHeight);


			//loadingScreen = new LoadingScreenRenderer(this);
			
			lastDisplayFBWidth = displayFBWidth;
			lastDisplayFBHeight = displayFBHeight;
			lastEnableVsync = mc.gameSettings.enableVsync;
			reinitFramebuffers = false;
		}

		if (changeNonDestructiveRenderConfig || reinitFramebuffers)
		{
			configureRenderer(glConfig); //does nothing for Vive
		}
		
	}

	public List<RenderPass> getRenderPasses() {
		Minecraft mc = Minecraft.getMinecraft();
		List<RenderPass> passes = new ArrayList<>();

		// Always do these for obvious reasons
		passes.add(RenderPass.LEFT);
		passes.add(RenderPass.RIGHT);

		if (mc.vrSettings.displayMirrorMode == VRSettings.MIRROR_FIRST_PERSON) {
			passes.add(RenderPass.CENTER);
		} else if (mc.vrSettings.displayMirrorMode == VRSettings.MIRROR_MIXED_REALITY) {
			if (mc.vrSettings.mixedRealityMRPlusUndistorted && mc.vrSettings.mixedRealityUnityLike)
				passes.add(RenderPass.CENTER);
			passes.add(RenderPass.THIRD);
		} else if (mc.vrSettings.displayMirrorMode == VRSettings.MIRROR_THIRD_PERSON) {
			passes.add(RenderPass.THIRD);
		}

		return passes;
	}
	
	public void doStencilForEye(int i) {
		Minecraft mc = Minecraft.getMinecraft();
		float[] verts = getStencilMask(mc.currentPass);

		//START STENCIL TESTING - Yes I know there's about 15 better ways to do this.
		GL11.glEnable(GL11.GL_STENCIL_TEST);

		GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
		GL11.glStencilMask(0xFF); // Write to stencil buffer
		GlStateManager.clear(GL11.GL_STENCIL_BUFFER_BIT); // Clear stencil buffer (0 by default)
		GL11.glStencilFunc(GL11.GL_ALWAYS, 0xFF, 0xFF); // Set any stencil to 1

		if (verts != null) {
			GlStateManager.disableAlphaTest();
			GlStateManager.disableDepthTest();
			GlStateManager.disableTexture2D();
			GlStateManager.disableCull();

			GlStateManager.color3f(0, 0, 0);
			GlStateManager.depthMask(false); // Don't write to depth buffer
			GlStateManager.matrixMode(GL11.GL_PROJECTION);
			GlStateManager.pushMatrix();
			GlStateManager.loadIdentity();
			GlStateManager.matrixMode(GL11.GL_MODELVIEW);
			GlStateManager.pushMatrix();
			GlStateManager.loadIdentity();
			GlStateManager.ortho(0.0D, framebufferVrRender.framebufferWidth, framebufferVrRender.framebufferHeight, 0.0D, -10, 20.0D);
			GlStateManager.viewport(0, 0, framebufferVrRender.framebufferWidth, framebufferVrRender.framebufferHeight);
			//this viewport might be wrong for some shaders.
			GL11.glBegin(GL11.GL_TRIANGLES);

			for (int ix = 0; ix < verts.length; ix += 2) {
				GL11.glVertex2f(verts[ix] * mc.vrSettings.renderScaleFactor, verts[ix + 1] * mc.vrSettings.renderScaleFactor);
			}
			GL11.glEnd();

			GL11.glMatrixMode(GL11.GL_PROJECTION);
			GlStateManager.popMatrix();
			GL11.glMatrixMode(GL11.GL_MODELVIEW);
			GlStateManager.popMatrix();

			GlStateManager.depthMask(true); // Do write to depth buffer

			GlStateManager.enableDepthTest();
			GlStateManager.enableAlphaTest();
			GlStateManager.enableTexture2D();
			GlStateManager.enableCull();
		}

		GL11.glStencilFunc(GL11.GL_NOTEQUAL, 0xFF, 1);
		GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
		GL11.glStencilMask(0x0); // Dont Write to stencil buffer
		/// END STENCIL TESTING
	}

	public void drawQuad()
	{
		// this func just draws a perfectly normal box with some texture coordinates
		GL11.glBegin(GL11.GL_QUADS);

		// Front Face
		GL11.glTexCoord2f(0.0f, 0.0f); GL11.glVertex3f(-1.0f, -1.0f,  0.0f);  // Bottom Left Of The Texture and Quad
		GL11.glTexCoord2f(1.0f, 0.0f); GL11.glVertex3f( 1.0f, -1.0f,  0.0f);  // Bottom Right Of The Texture and Quad
		GL11.glTexCoord2f(1.0f, 1.0f); GL11.glVertex3f( 1.0f,  1.0f,  0.0f);  // Top Right Of The Texture and Quad
		GL11.glTexCoord2f(0.0f, 1.0f); GL11.glVertex3f(-1.0f,  1.0f,  0.0f);  // Top Left Of The Texture and Quad

		GL11.glEnd();
	}
	
	public void reinitFrameBuffers(String cause) {
		this.reinitFramebuffers  =true;
		System.out.println("Reinit Render: " + cause );
	}
	
}
