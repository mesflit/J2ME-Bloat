package ru.playsoftware.j2meloader.base;

import static ru.playsoftware.j2meloader.util.Constants.PREF_BLACK_BACKGROUND;

import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.color.MaterialColors;

import ru.playsoftware.j2meloader.R;

public class BaseActivity extends AppCompatActivity {

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		if (sp.getBoolean(PREF_BLACK_BACKGROUND, false)) {
			getTheme().applyStyle(R.style.ThemeOverlay_App_Dark, true);
		}
		super.onCreate(savedInstanceState);
		EdgeToEdge.enable(this, getStatusBarStyle(), getNavigationBarStyle());
		View view = findViewById(android.R.id.content);
		ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
			Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
			v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
			return WindowInsetsCompat.CONSUMED;
		});
	}

	/** The toolbar is surface-colored, so the system bars follow it. */
	protected SystemBarStyle getStatusBarStyle() {
		int surface = MaterialColors.getColor(this,
				com.google.android.material.R.attr.colorSurface, Color.BLACK);
		int nightMode = getResources().getConfiguration().uiMode
				& Configuration.UI_MODE_NIGHT_MASK;
		if (nightMode == Configuration.UI_MODE_NIGHT_YES) {
			return SystemBarStyle.dark(surface);
		}
		return SystemBarStyle.light(surface, surface);
	}

	protected SystemBarStyle getNavigationBarStyle() {
		return getStatusBarStyle();
	}
}
