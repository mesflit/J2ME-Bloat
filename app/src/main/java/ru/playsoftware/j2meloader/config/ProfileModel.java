/*
 *  Copyright 2020 Yury Kharchenko
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ru.playsoftware.j2meloader.config;

import android.util.SparseIntArray;

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;

import java.io.File;

import javax.microedition.lcdui.keyboard.VirtualKeyboard;
import javax.microedition.util.ContextHolder;

import ru.playsoftware.j2meloader.util.SparseIntArrayAdapter;

public class ProfileModel {
	public static final int VERSION = 4;

	// Default look of the emulator screen and the virtual keys, taken from the app's dark palette
	private static final int SCREEN_BG_COLOR = 0x131318;
	private static final int VK_ALPHA = 100;
	private static final int VK_BG_COLOR = 0x2A292F;
	private static final int VK_FG_COLOR = 0xE4E1E9;
	private static final int VK_BG_COLOR_SELECTED = 0xBCC2FF;
	private static final int VK_FG_COLOR_SELECTED = 0x1B247A;
	private static final int VK_OUTLINE_COLOR = 0x918F9A;
	/** True if this is a new profile (not yet saved to file) */
	public final transient boolean isNew;

	public transient File dir;

	@SerializedName("Version")
	public int version;

	@SerializedName("ScreenWidth")
	public int screenWidth;

	@SerializedName("ScreenHeight")
	public int screenHeight;

	@SerializedName("ScreenBackgroundColor")
	public int screenBackgroundColor;

	@SerializedName("ScreenScaleRatio")
	public int screenScaleRatio;

	@SerializedName("Orientation")
	public int orientation;

	@SerializedName("ScreenScaleToFit")
	public boolean screenScaleToFit;

	@SerializedName("ScreenKeepAspectRatio")
	public boolean screenKeepAspectRatio;

	@SerializedName("ScreenScaleType")
	public int screenScaleType;

	@SerializedName("ScreenGravity")
	public int screenGravity;

	@SerializedName("ScreenFilter")
	public boolean screenFilter;

	@SerializedName("ImmediateMode")
	public boolean immediateMode;

	@SerializedName("HwAcceleration")
	public boolean hwAcceleration;

	@SerializedName("GraphicsMode")
	public int graphicsMode;

	@SerializedName("M3GForceDisableLighting")
	public boolean m3gForceDisableLighting;

	@SerializedName("Shader")
	public ShaderInfo shader;

	@SerializedName("ParallelRedrawScreen")
	public boolean parallelRedrawScreen;

	@SerializedName("ShowFps")
	public boolean showFps;

	@SerializedName("FpsLimit")
	public int fpsLimit;

	@SerializedName("ForceFullscreen")
	public boolean forceFullscreen;

	@SerializedName("FontSizeSmall")
	public int fontSizeSmall;

	@SerializedName("FontSizeMedium")
	public int fontSizeMedium;

	@SerializedName("FontSizeLarge")
	public int fontSizeLarge;

	@SerializedName("FontApplyDimensions")
	public boolean fontApplyDimensions;

	@SerializedName("FontAntiAlias")
	public boolean fontAA;

	@SerializedName("TouchInput")
	public boolean touchInput;

	@SerializedName("ShowKeyboard")
	public boolean showKeyboard;

	@SerializedName("VirtualKeyboardType")
	public int vkType;

	@SerializedName("ButtonShape")
	public int vkButtonShape;

	@SerializedName("VirtualKeyboardAlpha")
	public int vkAlpha;

	@SerializedName("VirtualKeyboardForceOpacity")
	public boolean vkForceOpacity;

	@SerializedName("VirtualKeyboardFeedback")
	public boolean vkFeedback;

	@SerializedName("VirtualKeyboardDelay")
	public int vkHideDelay;

	@SerializedName("VirtualKeyboardColorBackground")
	public int vkBgColor;

	@SerializedName("VirtualKeyboardColorBackgroundSelected")
	public int vkBgColorSelected;

	@SerializedName("VirtualKeyboardColorForeground")
	public int vkFgColor;

	@SerializedName("VirtualKeyboardColorForegroundSelected")
	public int vkFgColorSelected;

	@SerializedName("VirtualKeyboardColorOutline")
	public int vkOutlineColor;

	@SerializedName("Layout")
	public int keyCodesLayout;

	@JsonAdapter(SparseIntArrayAdapter.class)
	@SerializedName("KeyCodeMap")
	public SparseIntArray keyCodeMap;

	@JsonAdapter(SparseIntArrayAdapter.class)
	@SerializedName("KeyMappings")
	public SparseIntArray keyMappings;

	@SerializedName("SoundBank")
	public String soundBank;

	@SerializedName("SystemProperties")
	public String systemProperties;

	@SerializedName("DisablePause")
	public boolean disablePause;

	@SuppressWarnings("unused") // Gson uses default constructor if present
	public ProfileModel() {
		isNew = false;
	}

	/**
	 * Replaces the look that old versions used by default (gray keys with navy labels on a gray
	 * background) with the current one. Colors that the user has changed are left alone.
	 */
	void updateLegacyColors() {
		if (vkBgColor == 0xD0D0D0 && vkFgColor == 0x000080
				&& vkBgColorSelected == 0x000080 && vkFgColorSelected == 0xFFFFFF
				&& vkOutlineColor == 0xFFFFFF) {
			vkBgColor = VK_BG_COLOR;
			vkFgColor = VK_FG_COLOR;
			vkBgColorSelected = VK_BG_COLOR_SELECTED;
			vkFgColorSelected = VK_FG_COLOR_SELECTED;
			vkOutlineColor = VK_OUTLINE_COLOR;
			if (vkAlpha == 64) {
				vkAlpha = VK_ALPHA;
			}
			if (screenBackgroundColor == 0xD0D0D0) {
				screenBackgroundColor = SCREEN_BG_COLOR;
			}
		}
	}

	public ProfileModel(File dir) {
		this.dir = dir;
		isNew = true;
		version = VERSION;
		screenWidth = 240;
		screenHeight = 320;
		screenBackgroundColor = SCREEN_BG_COLOR;
		screenScaleType = 1;
		screenGravity = 1;
		screenScaleRatio = 100;
		screenScaleToFit = true;
		screenKeepAspectRatio = true;
		graphicsMode = 1;

		fontSizeSmall = 18;
		fontSizeMedium = 22;
		fontSizeLarge = 26;
		fontAA = true;

		showKeyboard = true;
		touchInput = true;

		vkButtonShape = VirtualKeyboard.ROUND_RECT_SHAPE;
		vkAlpha = VK_ALPHA;

		vkBgColor = VK_BG_COLOR;
		vkFgColor = VK_FG_COLOR;
		vkBgColorSelected = VK_BG_COLOR_SELECTED;
		vkFgColorSelected = VK_FG_COLOR_SELECTED;
		vkOutlineColor = VK_OUTLINE_COLOR;
		systemProperties = ContextHolder.getAssetAsString("defaults/system.props");
	}
}
