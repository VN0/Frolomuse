package com.frolo.muse.engine.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.*
import android.support.v4.media.session.MediaSessionCompat
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.frolo.muse.App
import com.frolo.muse.Logger
import com.frolo.muse.R
import com.frolo.muse.engine.*
import com.frolo.muse.engine.audiofx.AudioFx_Impl
import com.frolo.muse.engine.service.PlayerService.Companion.newIntent
import com.frolo.muse.engine.service.PlayerService.PlayerBinder
import com.frolo.muse.headset.createHeadsetHandler
import com.frolo.muse.interactor.media.DispatchSongPlayedUseCase
import com.frolo.muse.model.media.Song
import com.frolo.muse.repository.Preferences
import com.frolo.muse.repository.PresetRepository
import com.frolo.muse.rx.SchedulerProvider
import com.frolo.muse.sleeptimer.PlayerSleepTimer
import com.frolo.muse.ui.main.MainActivity
import io.reactivex.disposables.Disposable
import javax.inject.Inject


/**
 * The heart of the app. It must be running as long as the app is alive.
 *
 * Here is the whole logic of playing, navigating and dispatching events.
 *
 * Communication with the service can be through binding to [PlayerBinder] or intents (see [newIntent]]).
 *
 * A little tutorial for this service.
 * When the service creates, it starts foreground by posting a notification;
 * The notification may be cancelled by user, it this case case the server stops foreground and STOPS ITSELF.
 * (!) If so and the service is NOT bound then the service will be destroyed.
 * Otherwise, if the service is bound, then it keeps working as if nothing has changed.
 * (!) If the service gets unbound and the notification was cancelled before, then it will be destroyed.
 * Thus the service may be in the following conditions:
 * 1) Not foreground and not bound => will be destroyed by the system asap (~60 seconds);
 * 2) Foreground but unbound => will keep working;
 * 3) Not foreground but bound => will keep working;
 * 4) Foreground and bound => will keep working;
 * So to be working as long as needed, the service must be at least foreground or bound.
 * See https://stackoverflow.com/a/17883828/9437681
 */
class PlayerService: Service() {
    companion object {
        private const val TAG = "PlayerService"

        private const val RC_COMMAND_SKIP_TO_PREVIOUS = 151
        private const val RC_COMMAND_TOGGLE = 152
        private const val RC_COMMAND_SKIP_TO_NEXT = 153
        private const val RC_OPEN_PLAYER = 157
        private const val RC_CANCEL_NOTIFICATION = 159

        private const val EXTRA_COMMAND = "command"

        // commands
        const val COMMAND_EMPTY = 10
        const val COMMAND_SKIP_TO_PREVIOUS = 11
        const val COMMAND_SKIP_TO_NEXT = 12
        const val COMMAND_TOGGLE = 13
        const val COMMAND_SWITCH_TO_NEXT_REPEAT_MODE = 14
        const val COMMAND_SWITCH_TO_NEXT_SHUFFLE_MODE = 15
        const val COMMAND_STOP = 18
        const val COMMAND_CANCEL_NOTIFICATION = 19
        const val COMMAND_SHOW_NOTIFICATION = 20

        // notification
        @Deprecated("Use CHANNEL_ID_PLAYBACK instead")
        private const val CHANNEL_ID_PLAYBACK_OLD = "audio_playback"
        private const val CHANNEL_ID_PLAYBACK = "playback"
        private const val NOTIFICATION_ID_PLAYBACK = 1001
        private val MODERN_PLAYBACK_NOTIFICATION = true //Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

        /**
         * Starts the PlayerService in foreground.
         */
        @JvmStatic
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, newIntent(context))
        }

        @JvmStatic
        fun newIntent(context: Context): Intent = Intent(context, PlayerService::class.java)

        @JvmStatic
        fun newIntent(context: Context, command: Int): Intent = Intent(context, PlayerService::class.java)
                .putExtra(EXTRA_COMMAND, command)

        private fun getCommandExtra(intent: Intent) = intent.getIntExtra(EXTRA_COMMAND, COMMAND_EMPTY)
    }

    class PlayerBinder constructor(val service: Player): Binder()

    override fun onBind(intent: Intent?): IBinder {
        isBound = true
        Logger.d(TAG, "Service gets bound")
        return PlayerBinder(player)
    }

    override fun onRebind(intent: Intent?) {
        isBound = true
        Logger.d(TAG, "Service gets rebound")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isBound = false
        Logger.d(TAG, "Service gets unbound")
        if (notificationCancelled) {
            // Service is unbound and not in foreground. We don't know why it got unbound.
            // The user may have closed the app or the system killed activities due to low memory. Who knows.
            // Give it the last chance to be alive? NO!

            Logger.w(TAG, "Service is not in foreground. STOP IT!")
            // The service is not in foreground. It may live only 60 seconds if it's Android API v26+
            // There is no sense to continue running at all: no bound clients and no notification.
            // Then STOP IT.
            stopSelf()
        }
        // return true to get onRebind called later
        return true
    }

    private val serviceName = "com.frolo.muse.engine.service.PlayerService"
    private var isBound = false // indicates whether the service is bound or not
    private var notificationCancelled = false
    private var notificationDisposable: Disposable? = null

    private lateinit var player: Player

    @Inject
    lateinit var preferences: Preferences
    @Inject
    lateinit var presetRepository: PresetRepository
    @Inject
    lateinit var schedulerProvider: SchedulerProvider
    @Inject
    lateinit var dispatchSongPlayedUseCase: DispatchSongPlayedUseCase

    // You may be interested, why we use broadcast receiver (PendingIntent.getBroadcast) instead of simply starting service (PendingIntent.getService).
    // Well, this receiver is registered only while the service is running, so it will not receive any intent if the service is destroyed.
    // On the other hand, if we use PendingIntent.getService, the service will be started even the app is not running.
    private val sleepTimerHandler = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent != null && intent.action == PlayerSleepTimer.ACTION_ALARM_TRIGGERED) {
                // Need to reset the current sleep timer because its pending intent is still retained,
                // therefore the app settings may think that an alarm is still set.
                PlayerSleepTimer.resetCurrentSleepTimer(context)
                Logger.d(TAG, "Received sleep timer broadcast message. Pausing playback")
                player.pause()
            }
        }
    }

    // Handling for headsets
    private val headsetHandler = createHeadsetHandler(
        onConnected = {
            if (preferences.shouldResumeOnPluggedIn()) {
                player.start()
            }
        },
        onDisconnected = {
            if (preferences.shouldPauseOnUnplugged()) {
                player.pause()
            }
        },
        onBecomeWeird = {
            // no actions
        }
    )

    // Handling headset button actions
    private lateinit var mediaSession: MediaSessionCompat
    private val mediaSessionCallback = object : MediaSessionCallback() {
        override fun onTogglePlayback() = player.toggle()
        override fun onSkipToNext() = player.skipToNext()
        override fun onSkipToPrevious() = player.skipToPrevious()
    }

    /**
     * Initializing all resources here
     */
    override fun onCreate() {
        super.onCreate()
        (application as App).appComponent.inject(this)

        mediaSession = MediaSessionCompat(applicationContext, packageName).apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS)
            isActive = true
            setCallback(mediaSessionCallback)
        }

        // This is the first what we have to do.
        // Why? Because initialization may take some time.
        // And I want to be sure that it will not crash due to the Foreground service policy.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Creating notification channel
            createPlaybackNotificationChannel()
            postPromiseNotification()
        }

        // Creating engine and ui handlers
        val thread = HandlerThread("Service[$serviceName]", Process.THREAD_PRIORITY_URGENT_AUDIO)
        thread.start()

        // Handlers
        val engineHandler = Handler(thread.looper)
        val eventHandler = Handler(Looper.getMainLooper())

        // Creating eq impl
        val audioFxApplicable: AudioFxApplicable =
                AudioFx_Impl.getInstance(this, Const.AUDIO_FX_PREFERENCES)

        val observerRegistry = ObserverRegistry(this) { player, forceNotify ->
            notifyAboutPlayback(forceNotify)
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager?
        player = PlayerEngine(engineHandler, eventHandler, audioFxApplicable, observerRegistry, audioManager)

        headsetHandler.subscribe(this)
        registerReceiver(sleepTimerHandler, IntentFilter(PlayerSleepTimer.ACTION_ALARM_TRIGGERED))

        // setting up modes after preferences initialized
        preferences.apply {
            player.setRepeatMode(loadRepeatMode())
            player.setShuffleMode(loadShuffleMode())
        }

        player.registerObserver(PlayerStateObserver(preferences))
        player.registerObserver(SongPlayCountObserver(schedulerProvider, dispatchSongPlayedUseCase))

        Logger.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return if (intent != null) {
            when(getCommandExtra(intent)) {
                COMMAND_SKIP_TO_PREVIOUS -> player.skipToPrevious()

                COMMAND_SKIP_TO_NEXT -> player.skipToNext()

                COMMAND_TOGGLE -> player.toggle()

                COMMAND_SWITCH_TO_NEXT_REPEAT_MODE ->
                    player.switchToNextRepeatMode()

                COMMAND_SWITCH_TO_NEXT_SHUFFLE_MODE ->
                    player.switchToNextShuffleMode()

                COMMAND_CANCEL_NOTIFICATION -> cancelNotification()

                COMMAND_STOP -> player.pause()

                COMMAND_SHOW_NOTIFICATION -> showNotification()
            }
            START_STICKY
        } else START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Logger.d(TAG, "Task removed!")
    }

    override fun onDestroy() {
        Logger.d(TAG, "Service died. Cleaning callbacks")

        // notifying observers that player is shutting down and removing them all
        player.shutdown()

        headsetHandler.dispose()
        unregisterReceiver(sleepTimerHandler)

        mediaSession.release()

        notificationDisposable?.dispose()

        super.onDestroy()
    }

    /********************************
     ********* NOTIFICATION *********
     *******************************/

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createPlaybackNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE)
                as? NotificationManager

        if (manager != null) {
            Logger.d(TAG, "Deleting old notification channel for playback")
            manager.deleteNotificationChannel(CHANNEL_ID_PLAYBACK_OLD)

            Logger.d(TAG, "Creating notification channel for playback")
            val channelName = getString(R.string.playback_channel_name)
            val channelDesc = getString(R.string.playback_channel_desc)
            val channel = NotificationChannel(
                    CHANNEL_ID_PLAYBACK,
                    channelName,
                    NotificationManager.IMPORTANCE_LOW).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                description = channelDesc
            }
            manager.createNotificationChannel(channel)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun postPromiseNotification() {
        startForeground(NOTIFICATION_ID_PLAYBACK, buildPromiseNotification())
    }

    private fun cancelNotification() {
        notificationCancelled = true
        player.pause()
        Logger.d(TAG, "Notification cancelled. Stopping foreground")
        stopForeground(true)
        if (isBound.not()) {
            Logger.w(TAG, "No bound clients. STOP IT!")
            // No clients bound to the service. It may live only 60 seconds if it's Android API v26+
            // It makes no sense to continue running at all: no bound clients and no notification.
            // Then STOP IT.
            stopSelf()
        }
    }

    private fun showNotification() {
        Logger.d(TAG, "Showing notification")
        notificationCancelled = false
        notifyAboutPlayback(false)
    }

    private fun buildPromiseNotification(): Notification {
        return buildPlaybackNotification(null, false, null)
    }

    private fun buildPlaybackNotification(song: Song?, isPlaying: Boolean, art: Bitmap?): Notification {

        val context = this@PlayerService

        val cancelPendingIntent = newIntent(context, COMMAND_CANCEL_NOTIFICATION).let { intent ->
            PendingIntent.getService(context, RC_CANCEL_NOTIFICATION, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        }

        val previousPendingIntent = newIntent(context, COMMAND_SKIP_TO_PREVIOUS).let { intent ->
            PendingIntent.getService(context, RC_COMMAND_SKIP_TO_PREVIOUS, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        }

        val togglePendingIntent = newIntent(context, COMMAND_TOGGLE).let { intent ->
            PendingIntent.getService(context, RC_COMMAND_TOGGLE, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        }

        val nextPendingIntent = newIntent(context, COMMAND_SKIP_TO_NEXT).let { intent ->
            PendingIntent.getService(context, RC_COMMAND_SKIP_TO_NEXT, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        }

        val openPendingIntent = MainActivity.newIntent(context = context, openPlayer = true).let { intent ->
            PendingIntent.getActivity(context, RC_OPEN_PLAYER, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        }

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID_PLAYBACK).apply {
            setSmallIcon(R.drawable.ic_app_brand)

            setContentTitle(song?.title.orEmpty())
            setContentText(song?.artist.orEmpty())

            setContentIntent(openPendingIntent)

            setPriority(NotificationCompat.PRIORITY_LOW)
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        }

        if (MODERN_PLAYBACK_NOTIFICATION) {

            notificationBuilder.apply {
                addAction(R.drawable.ntf_ic_previous, "Previous", previousPendingIntent)
                if (isPlaying) {
                    addAction(R.drawable.ntf_ic_pause, "Pause", togglePendingIntent)
                } else {
                    addAction(R.drawable.ntf_ic_play, "Play", togglePendingIntent)
                }
                addAction(R.drawable.ntf_ic_next, "Next", nextPendingIntent)
                addAction(R.drawable.ntf_ic_cancel, "Cancel", cancelPendingIntent)

                setStyle(
                    androidx.media.app.NotificationCompat.MediaStyle()
                        .setShowActionsInCompactView(0, 1, 2)
                        .setMediaSession(mediaSession.sessionToken)
                        .setShowCancelButton(false)
                )

                setLargeIcon(art)
            }

        } else {

            val remoteViews = RemoteViews(context.packageName, R.layout.notification_playback).apply {
                setTextViewText(R.id.tv_song_name, song?.title ?: "")
                setTextViewText(R.id.tv_artist_name, song?.artist ?: "")
                setImageViewResource(R.id.btn_play, if (isPlaying) R.drawable.ic_cpause else R.drawable.ic_play)
                setImageViewBitmap(R.id.imv_album_art, art)

                setOnClickPendingIntent(R.id.btn_cancel, cancelPendingIntent)
                setOnClickPendingIntent(R.id.btn_skip_to_previous, previousPendingIntent)
                setOnClickPendingIntent(R.id.btn_play, togglePendingIntent)
                setOnClickPendingIntent(R.id.btn_skip_to_next, nextPendingIntent)
                setOnClickPendingIntent(R.id.root_container, openPendingIntent)
            }

            notificationBuilder.apply {
                //setStyle(NotificationCompat.DecoratedCustomViewStyle())
                //setCustomContentView(remoteViews)
                setCustomBigContentView(remoteViews)
                setStyle(
                    androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.sessionToken)
                )
            }

        }

        return notificationBuilder.build()
    }

    private fun notifyAboutPlayback(forceNotify: Boolean) {
        notificationDisposable?.dispose()
        notificationDisposable = null

        if (notificationCancelled && !forceNotify) {
            return
        }

        val song = player.getCurrent()
        val isPlaying = player.isPlaying()

        notificationDisposable = Notifications.getPlaybackArt(this, song)
            .doOnSuccess { startForeground(NOTIFICATION_ID_PLAYBACK, buildPlaybackNotification(song, isPlaying, it)) }
            .doOnError { startForeground(NOTIFICATION_ID_PLAYBACK, buildPlaybackNotification(song, isPlaying, null)) }
            .ignoreElement()
            .subscribe({ /*stub*/ }, { /*stub*/ })

        // We're about to post the notification. It's not cancelled now
        notificationCancelled = false
    }
}