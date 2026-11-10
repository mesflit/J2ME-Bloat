/*
 * Copyright 2026 mesflit
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.woesss.j2me.installer;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;

import ru.playsoftware.j2meloader.MainActivity;
import ru.playsoftware.j2meloader.R;

/**
 * Keeps the app alive while {@link BatchImporter} works in the background and shows
 * its progress as a notification.
 */
public class ImportService extends Service {
	private static final String TAG = "ImportService";
	/** Opens the import dialog of {@link MainActivity}. */
	public static final String ACTION_SHOW_IMPORT = "ru.playsoftware.j2meloader.SHOW_IMPORT";
	private static final String ACTION_CANCEL = "ru.playsoftware.j2meloader.CANCEL_IMPORT";
	private static final String CHANNEL_ID = "import";
	private static final int PROGRESS_ID = 4201;
	private static final int RESULT_ID = 4202;
	private static final long WAKE_LOCK_TIMEOUT = 6 * 60 * 60 * 1000L;

	private final Observer<BatchImporter.State> observer = this::onStateChanged;
	private NotificationManager notificationManager;
	private PowerManager.WakeLock wakeLock;
	private boolean observing;

	/** Starts the service. Does nothing bad if the system refuses: the import still runs. */
	public static void start(Context context) {
		try {
			ContextCompat.startForegroundService(context, new Intent(context, ImportService.class));
		} catch (RuntimeException e) {
			Log.w(TAG, "Can't start the import service", e);
		}
	}

	/** Removes the "import finished" notification, e.g. when the result is on screen. */
	public static void clearResult(Context context) {
		NotificationManager manager =
				(NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
		if (manager != null) {
			manager.cancel(RESULT_ID);
		}
	}

	@Override
	public void onCreate() {
		super.onCreate();
		notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		NotificationChannelCompat channel = new NotificationChannelCompat.Builder(CHANNEL_ID,
				NotificationManagerCompat.IMPORTANCE_LOW)
				.setName(getString(R.string.import_title))
				.setShowBadge(false)
				.build();
		NotificationManagerCompat.from(this).createNotificationChannel(channel);
		PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
		if (powerManager != null) {
			// The screen may turn off while converting a large collection.
			wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "j2me:import");
			wakeLock.setReferenceCounted(false);
			wakeLock.acquire(WAKE_LOCK_TIMEOUT);
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		BatchImporter importer = BatchImporter.get();
		if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
			importer.cancel();
		}
		try {
			ServiceCompat.startForeground(this, PROGRESS_ID,
					buildProgress(importer.getCurrentState()),
					ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
		} catch (RuntimeException e) {
			Log.w(TAG, "Can't enter the foreground", e);
		}
		if (!observing) {
			observing = true;
			importer.getState().observeForever(observer);
		}
		return START_NOT_STICKY;
	}

	private void onStateChanged(BatchImporter.State s) {
		if (s.isRunning()) {
			notificationManager.notify(PROGRESS_ID, buildProgress(s));
			return;
		}
		ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
		if (s.phase == BatchImporter.PHASE_FINISHED && !BatchImporter.get().isUiVisible()) {
			notificationManager.notify(RESULT_ID, buildResult(s));
		}
		stopSelf();
	}

	private PendingIntent openAppIntent() {
		Intent intent = new Intent(this, MainActivity.class)
				.setAction(ACTION_SHOW_IMPORT)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
		return PendingIntent.getActivity(this, 0, intent,
				PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
	}

	private Notification buildProgress(BatchImporter.State s) {
		String text;
		boolean indeterminate = s.phase != BatchImporter.PHASE_INSTALLING;
		if (s.cancelling) {
			text = getString(R.string.import_cancelling);
		} else if (s.phase == BatchImporter.PHASE_INSTALLING) {
			text = getString(R.string.install_multiple_progress, s.processed, s.total);
		} else if (s.found > 0) {
			text = getString(R.string.import_scanning_found, s.found);
		} else {
			text = getString(R.string.import_scanning);
		}
		Intent cancel = new Intent(this, ImportService.class).setAction(ACTION_CANCEL);
		PendingIntent cancelIntent = PendingIntent.getService(this, 1, cancel,
				PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
				.setSmallIcon(R.drawable.ic_stat_import)
				.setContentTitle(getString(R.string.import_notification_title))
				.setContentText(text)
				.setContentIntent(openAppIntent())
				.setOngoing(true)
				.setOnlyAlertOnce(true)
				.setCategory(NotificationCompat.CATEGORY_PROGRESS)
				.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
				.setProgress(Math.max(1, s.total), Math.max(0, s.processed - 1), indeterminate)
				.addAction(0, getString(android.R.string.cancel), cancelIntent);
		if (s.name != null && !s.name.isEmpty()) {
			builder.setSubText(s.name);
		}
		return builder.build();
	}

	private Notification buildResult(BatchImporter.State s) {
		CharSequence summary = BatchImporter.getSummary(this, s, false);
		return new NotificationCompat.Builder(this, CHANNEL_ID)
				.setSmallIcon(R.drawable.ic_stat_import)
				.setContentTitle(getString(R.string.import_notification_done))
				.setContentText(summary.toString().replace('\n', ' '))
				.setStyle(new NotificationCompat.BigTextStyle().bigText(summary))
				.setContentIntent(openAppIntent())
				.setAutoCancel(true)
				.build();
	}

	/** The system limits how long this kind of service may run; stop cleanly when it says so. */
	@Override
	public void onTimeout(int startId, int fgsType) {
		BatchImporter.get().cancel();
		ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
		stopSelf();
	}

	@Override
	public void onDestroy() {
		if (observing) {
			observing = false;
			BatchImporter.get().getState().removeObserver(observer);
		}
		if (wakeLock != null && wakeLock.isHeld()) {
			wakeLock.release();
		}
		super.onDestroy();
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
}
