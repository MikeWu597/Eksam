package com.eksam.app;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
	private void applyStatusBarVisibilityForOrientation() {
		int orientation = getResources().getConfiguration().orientation;
		boolean landscape = orientation == Configuration.ORIENTATION_LANDSCAPE;

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			WindowInsetsController controller = getWindow().getInsetsController();
			if (controller == null) return;

			if (landscape) {
				controller.hide(WindowInsets.Type.statusBars());
				controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
			} else {
				controller.show(WindowInsets.Type.statusBars());
			}
			return;
		}

		// Legacy path (< Android 11)
		if (landscape) {
			getWindow().setFlags(
				WindowManager.LayoutParams.FLAG_FULLSCREEN,
				WindowManager.LayoutParams.FLAG_FULLSCREEN
			);
			View decorView = getWindow().getDecorView();
			decorView.setSystemUiVisibility(
				View.SYSTEM_UI_FLAG_FULLSCREEN
					| View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
					| View.SYSTEM_UI_FLAG_LAYOUT_STABLE
			);
		} else {
			getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
			View decorView = getWindow().getDecorView();
			decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
		}
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		// IMPORTANT: custom Capacitor plugins must be registered before Bridge initialization.
		registerPlugin(NativeWsPlugin.class);
		super.onCreate(savedInstanceState);
		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
		applyStatusBarVisibilityForOrientation();
	}

	@Override
	public void onResume() {
		super.onResume();
		applyStatusBarVisibilityForOrientation();
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		applyStatusBarVisibilityForOrientation();
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus) {
			applyStatusBarVisibilityForOrientation();
		}
	}
}
