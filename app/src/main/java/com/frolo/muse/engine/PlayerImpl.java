package com.frolo.muse.engine;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import com.frolo.muse.BuildConfig;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Android-specific thread-safe implementation of {@link Player}.
 * Methods prefixed with '_' are of type Runnable and must be processed with
 * {@link PlayerImpl#processEngineTask(boolean, Runnable)} or {@link PlayerImpl#processEngineTask(Runnable)} methods.
 * All public methods are non-blocking and can be called from any thread.
 * Events are dispatched on the main thread using {@link PlayerObserverRegistry}.
 * The implementation uses {@link MediaPlayer} as the engine and uses {@link AudioFxApplicable} as the AudioFx.
 */
public final class PlayerImpl implements Player {

    private static final boolean DEBUG = BuildConfig.DEBUG;

    private static final int NO_AUDIO_SESSION = 0;

    private static final int NO_POSITION_IN_QUEUE = -1;

    private static final MathUtil.Range SPEED_RANGE = new MathUtil.Range(0f, 2f);
    private static final MathUtil.Range PITCH_RANGE = new MathUtil.Range(0f, 2f);

    public static class PlayerException extends RuntimeException {
        public PlayerException(String message) {
            super(message);
        }

        public PlayerException(Throwable cause) {
            super(cause);
        }
    }

    /**
     * Wrapper for engine tasks. This does additional work,
     * including checking the execution thread and the shutdown status of the player.
     */
    private final class EngineTaskWrapper implements Runnable {

        final Runnable mTask;

        EngineTaskWrapper(@NotNull Runnable task) {
            mTask = task;
        }

        @Override
        public void run() {
            // At first, making sure that we're on the engine thread
            assertEngineThread();
            // Then, we need to check if the player is shutdown
            if (isShutdown()) return;
            // Finally, executing the task
            mTask.run();
        }
    }

    /**
     * Factory method that creates a new implementation of {@link Player}.
     * @param context context
     * @param audioFx audioFx
     * @return a new implementation
     */
    public static Player create(@NotNull Context context, @NotNull AudioFxApplicable audioFx) {
        return new PlayerImpl(context, audioFx);
    }

    /**
     * Creates a new handler that will work on a background thread.
     * The method is a little bit blocking, it's better not to call it on the main thread.
     * @return a new engine handler
     */
    @NotNull
    static Handler createEngineHandler() {
        final HandlerThread thread = new HandlerThread("PlayerEngine");
        thread.start();
        return new Handler(thread.getLooper());
    }

    @NotNull
    static Handler createEventHandler() {
        return new Handler(Looper.getMainLooper());
    }

    /**
     * Maps error codes of {@link MediaPlayer} to String.
     * @param what error code
     * @param extra error extra
     * @return error message
     */
    @NotNull
    private static String getEngineErrorMessage(int what, /* unused */ int extra) {
        switch (what) {
            case MediaPlayer.MEDIA_ERROR_IO:
                return "IO";
            case MediaPlayer.MEDIA_ERROR_MALFORMED:
                return "MALFORMED";
            case MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK:
                return "NOT_VALID_FOR_PROGRESSIVE_PLAYBACK";
            case MediaPlayer.MEDIA_ERROR_SERVER_DIED:
                return "SERVER_DIED";
            case MediaPlayer.MEDIA_ERROR_TIMED_OUT:
                return "TIMED_OUT";
            case MediaPlayer.MEDIA_ERROR_UNKNOWN:
                return "UNKNOWN";
            case MediaPlayer.MEDIA_ERROR_UNSUPPORTED:
                return "UNSUPPORTED";
            default:
                return "null";
        }
    }

    // Guard for internal state including mCurrentQueue, mCurrentItem, mCurrentPositionInQueue
    private final Object mStateLock = new Object();
    // Guard for mEngine
    private final Object mEngineLock = new Object();

    // Flag that indicates whether the player is shutdown
    private final AtomicBoolean mShutdown = new AtomicBoolean(false);

    // Context
    private final Context mContext;

    // Handlers
    private final Handler mEngineHandler;

    // Audio Focus Requester
    @NotNull
    private final AudioFocusRequester mAudioFocusRequester;

    // AudioFx
    @NotNull
    private final AudioFxApplicable mAudioFx;

    // Observer Registry
    @NotNull
    private final PlayerObserverRegistry mObserverRegistry;

    // Engine
    @Nullable
    private volatile MediaPlayer mEngine;

    /**
     * Error handler for {@link MediaPlayer}.
     */
    private final MediaPlayer.OnErrorListener mOnErrorListener = new MediaPlayer.OnErrorListener() {
        @Override
        public boolean onError(MediaPlayer mp, int what, int extra) {
            processEngineTask(_handleEngineError(mp, what, extra));
            // We always should return true,
            // because, returning false stops the playback.
            return true;
        }
    };

    /**
     * Completion handler for {@link MediaPlayer}.
     */
    private final MediaPlayer.OnCompletionListener mOnCompletionListener = new MediaPlayer.OnCompletionListener() {
        @Override
        public void onCompletion(MediaPlayer mp) {
            processEngineTask(_skipToNext(false));
        }
    };

    // Internal state
    @Nullable
    private volatile AudioSourceQueue mOriginQueue = null;
    @NotNull
    private volatile AudioSourceQueue mCurrentQueue = AudioSourceQueue.empty();
    @Nullable
    private volatile AudioSource mCurrentItem = null;
    private volatile int mCurrentPositionInQueue = NO_POSITION_IN_QUEUE;

    private volatile boolean mIsPreparedFlag = false;
    private volatile boolean mIsPlayingFlag = false;

    @RepeatMode
    private volatile int mRepeatMode = Player.REPEAT_OFF;
    @ShuffleMode
    private volatile int mShuffleMode = Player.SHUFFLE_OFF;

    // AB Engine
    @NotNull
    private final ABEngine mABEngine = new ABEngine();

    // Cross-Fade
    @Nullable
    private volatile CrossFadeStrategy mCrossFadeStrategy;
    private volatile Timer mCrossFadeTimer;

    private PlayerImpl(@NotNull Context context, @NotNull AudioFxApplicable audioFx) {
        mContext = context;
        mAudioFx = audioFx;
        mEngineHandler = createEngineHandler();
        mObserverRegistry = PlayerObserverRegistry.create(context, this);
        mAudioFocusRequester = AudioFocusRequesterImpl.create(context, this);
        startCrossFadeTimer();
    }

    /**
     * Checks if the execution happens on the engine thread.
     * If not, then {@link IllegalStateException} is thrown.
     * NOTE: this only works for debug builds.
     */
    private void assertEngineThread() {
        if (DEBUG) {
            final boolean isOnEngineThread = mEngineHandler.getLooper().isCurrentThread();
            if (!isOnEngineThread) {
                throw new IllegalStateException("Called not on engine thread");
            }
        }
    }

    /**
     * Reports the given <code>error</code> that occurred in this player.
     * @param error to report
     */
    private void report(Throwable error) {
        final PlayerException playerException = error instanceof PlayerException
                ? (PlayerException) error
                : new PlayerException(error);
        Log.e("PlayerImpl", "Error", playerException);
    }

    /**
     * Processes the given <code>task</code> cancelling all previous ones if necessary.
     * If the call occurs on the engine thread, then the task is processed immediately.
     * Otherwise the task is posted to the engine thread through the appropriate handler.
     * @param cancelPreviousTasks if true, then all posted tasks are cancelled in the engine handler
     * @param task to process
     */
    private void processEngineTask(boolean cancelPreviousTasks, @NotNull Runnable task) {
        if (cancelPreviousTasks) {
            mEngineHandler.removeCallbacksAndMessages(null);
        }

        final Runnable wrapped = new EngineTaskWrapper(task);
        if (mEngineHandler.getLooper().isCurrentThread()) {
            wrapped.run();
        } else {
            mEngineHandler.post(wrapped);
        }
    }

    /**
     * Does the same as {@link PlayerImpl#processEngineTask(boolean, Runnable)}
     * but without cancelling previously posted tasks.
     * @param task to process
     */
    private void processEngineTask(@NotNull Runnable task) {
        processEngineTask(false, task);
    }

    /**
     * Executes <code>action</code> on the engine thread.
     * Posting is async in the sense that if the call occurs on the engine thread,
     * the execution will not be performed immediately, but in the near future.
     * @param action to execute on the engine thread
     */
    public void postOnEngineThread(@NotNull Runnable action) {
        mEngineHandler.post(action);
    }

    /**
     * Tries to request audio focus.
     * Returns true if the focus is granted and the player can start playing, false - otherwise.
     * @return true if the audio focus is granted, false - otherwise.
     */
    private boolean tryRequestAudioFocus() {
        try {
            return mAudioFocusRequester.requestAudioFocus();
        } catch (Throwable error) {
            report(error);
            // TODO: return true ?
            return true;
        }
    }

    private Player self() {
        return this;
    }

    /**
     * Returns true if {@link Player#shutdown()} was called on this instance.
     * If so, then the instance can no longer be used and any method calls should be ignored.
     * @return true if the player is shutdown, false - otherwise
     */
    public boolean isShutdown() {
        return mShutdown.get();
    }

    @NotNull
    protected final Context getContext() {
        return mContext;
    }

    /**
     * Returns the current instance of the player engine.
     * Unlike {@link PlayerImpl#getEngine()} this does not create a new instance if the current one is null.
     * This may be useful when we need to do something with the engine, but only if it has been created.
     * @return the current engine instance, nullable
     */
    @Nullable
    private MediaPlayer maybeGetEngine() {
        return mEngine;
    }

    /**
     * Returns the current instance of the player engine.
     * Unlike {@link PlayerImpl#maybeGetEngine()} if the current instance is null, then a new one will be created and returned.
     * Use this method if you want to get a non-null instantiated engine.
     * @return the current engine instance, not null
     */
    @NotNull
    private MediaPlayer getEngine() {
        MediaPlayer engine = mEngine;

        if (engine == null) {
            synchronized (mEngineLock) {
                engine = mEngine;
                if (engine == null) {
                    mEngine = engine = createEngine();
                }
            }
        }

        return engine;
    }

    /**
     * Creates an new instance of {@link MediaPlayer}.
     * @return an new instance of {@link MediaPlayer}.
     */
    @NotNull
    private MediaPlayer createEngine() {
        final MediaPlayer engine = new MediaPlayer();

        engine.setAuxEffectSendLevel(1.0f);
        engine.setAudioStreamType(AudioManager.STREAM_MUSIC);

        final AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setLegacyStreamType(AudioManager.STREAM_MUSIC)
            .build();
        engine.setAudioAttributes(attrs);

        engine.setOnErrorListener(mOnErrorListener);
        engine.setOnCompletionListener(mOnCompletionListener);

        return engine;
    }

    private Runnable _handleEngineError(final MediaPlayer engine, final int what, final int extra) {
        return new Runnable() {
            @Override
            public void run() {
                // Always reporting errors to better understand what problems users do experience
                report(new PlayerException(getEngineErrorMessage(what, extra)));

                // Critical error, the engine is not valid anymore.
                // In this case, the current engine instance should be released and gc-ed.
                if (what == MediaPlayer.MEDIA_ERROR_SERVER_DIED) {

                    synchronized (mStateLock) {

                        try {
                            engine.release();
                        } catch (Throwable error) {
                            report(error);
                        }

                        mEngine = null;

                        mIsPreparedFlag = false;
                        mIsPreparedFlag = false;

                        final AudioSource currentItem = mCurrentItem;

                        // TODO: try also to restore the playback position
                        if (currentItem != null) {
                            // This creates a new instance
                            final MediaPlayer engine = getEngine();

                            try {
                                engine.reset();
                                engine.setDataSource(currentItem.getSource());
                                engine.prepare();
                                mIsPreparedFlag = true;
                                mObserverRegistry.dispatchPrepared();
                                mAudioFx.apply(engine);
                            } catch (Throwable error) {
                                report(error);
                            }
                        }

                        mObserverRegistry.dispatchPlaybackPaused();
                    }
                }
            }
        };
    }

    @NotNull
    private Runnable _reset() {
        return new Runnable() {
            @Override
            public void run() {

                // Guarded access to internal state
                synchronized (mStateLock) {

                    // We reset everything here

                    mABEngine.reset();

                    final MediaPlayer engine = maybeGetEngine();

                    if (engine != null) {
                        try {
                            engine.reset();
                        } catch (Throwable error) {
                            report(error);
                        }
                    }

                    mCurrentItem = null;
                    mCurrentPositionInQueue = NO_POSITION_IN_QUEUE;
                    mIsPreparedFlag = false;
                    mIsPlayingFlag = false;

                    mObserverRegistry.dispatchAudioSourceChanged(null, NO_POSITION_IN_QUEUE);
                    mObserverRegistry.dispatchPlaybackPaused();
                }
            }
        };
    }

    @NotNull
    private Runnable _skipToNext(final boolean byUser) {
        return new Runnable() {
            @Override
            public void run() {

                // Guarded access to internal state
                synchronized (mStateLock) {
                    final AudioSourceQueue queue = mCurrentQueue;
                    AudioSource currentItem = mCurrentItem;
                    final @RepeatMode int repeatMode = mRepeatMode;
                    final @ShuffleMode int shuffleMode = mShuffleMode;
                    int currentPositionInQueue = mCurrentPositionInQueue;
                    boolean startPlaying = mIsPlayingFlag;

                    if (queue.isEmpty()) {
                        // How is it possible?
                        _reset().run();
                        return;
                    }

                    if (!byUser && repeatMode == Player.REPEAT_ONE) {
                        // everything remains the same
                    } else {
                        // the next position is the current one + 1
                        currentPositionInQueue++;

                        if (currentPositionInQueue >= queue.getLength()) {
                            // We've reached the end of the queue => so we get to the start of the queue
                            currentPositionInQueue = 0;
                            currentItem = queue.getItemAt(0);
                            // Since we've reached the end of the queue, we need to check ifr we need to start playing
                            startPlaying = (mIsPlayingFlag && byUser)
                                    || (mIsPlayingFlag && repeatMode == Player.REPEAT_PLAYLIST);
                        } else {
                            if (currentPositionInQueue >= 0) {
                                // The position is valid
                                currentItem = queue.getItemAt(currentPositionInQueue);
                            } else {
                                // Undefined state, in fact
                                currentItem = null;
                            }
                        }
                    }

                    mCurrentItem = currentItem;
                    mCurrentPositionInQueue = currentPositionInQueue;
                    _handleSource(currentItem, 0, startPlaying).run();
                }
            }
        };
    }

    @NotNull
    private Runnable _handleSource(@Nullable final AudioSource item, final int playbackPosition, final boolean startPlaying) {
        return new Runnable() {
            @Override
            public void run() {

                // Guarded access to internal state
                synchronized (mStateLock) {
                    // The current audio source is changed => gotta reset A-B
                    mABEngine.reset();

                    mObserverRegistry.dispatchAudioSourceChanged(item, mCurrentPositionInQueue);

                    if (item == null) {
                        // If the source is null, then we only need to reset the engine (of course, if the engine is not null)
                        final MediaPlayer engine = maybeGetEngine();

                        try {
                            if (engine != null) {
                                engine.reset();
                            }
                        } catch (Throwable error) {
                            report(error);
                        }

                        mIsPreparedFlag = false;

                        mObserverRegistry.dispatchPlaybackPaused();
                        return;
                    }

                    boolean isPreparedFlag = false;
                    boolean isPlayingFlag = startPlaying;

                    try {
                        final MediaPlayer engine = getEngine();
                        engine.reset();
                        engine.setDataSource(item.getSource());
                        engine.prepare();

                        isPreparedFlag = true;

                        mObserverRegistry.dispatchPrepared();

                        mAudioFx.apply(engine);

                        if (playbackPosition > 0) {
                            engine.seekTo(playbackPosition);
                        }

                        if (startPlaying && tryRequestAudioFocus()) {
                            isPlayingFlag = true;
                            engine.start();
                            maybeAdjustVolume();
                            mObserverRegistry.dispatchPlaybackStarted();
                        } else {
                            isPlayingFlag = false;
                            mObserverRegistry.dispatchPlaybackPaused();
                        }
                    } catch (Throwable error) {
                        report(error);
                        mObserverRegistry.dispatchPlaybackPaused();
                    }

                    mIsPreparedFlag = isPreparedFlag;
                    mIsPlayingFlag = isPlayingFlag;
                }
            }
        };
    }

    @Override
    public void registerObserver(@NotNull PlayerObserver observer) {
        if (isShutdown()) return;
        mObserverRegistry.register(observer);
    }

    @Override
    public void unregisterObserver(@NotNull PlayerObserver observer) {
        if (isShutdown()) return;
        mObserverRegistry.unregister(observer);
    }

    @Override
    public void prepare(@NotNull AudioSourceQueue queue, @NotNull AudioSource item, boolean startPlaying) {
        prepare(queue, item, 0, startPlaying);
    }

    @Override
    public void prepare(@NotNull final AudioSourceQueue queue, @NotNull final AudioSource item, final int playbackPosition, final boolean startPlaying) {
        if (isShutdown()) return;

        final Runnable task = new Runnable() {
            @Override
            public void run() {

                // Guarded access to internal state
                synchronized (mStateLock) {
                    // First, saving the original queue
                    mOriginQueue = queue;

                    // The current queue will be cloned from the original
                    final AudioSourceQueue currentQueue = queue.clone();
                    // Then, we need to configure the new queue according to the current shuffle mode
                    if (mShuffleMode == Player.SHUFFLE_ON) {
                        currentQueue.shuffleWithItemInFront(item);
                    }
                    mCurrentQueue = currentQueue;

                    mObserverRegistry.dispatchQueueChanged(currentQueue);

                    // Now we need to define the current item and its position in the queue
                    int positionInQueue = currentQueue.indexOf(item);
                    AudioSource currentItem = positionInQueue >= 0 ? item : null;
                    if (currentItem == null && !queue.isEmpty()) {
                        // Actually, this should not happen
                        positionInQueue = 0;
                        currentItem = queue.getItemAt(0);
                    }

                    mCurrentPositionInQueue = positionInQueue;
                    mCurrentItem = currentItem;

                    _handleSource(currentItem, playbackPosition, startPlaying).run();

                }
            }
        };

        processEngineTask(true, task);
    }

    @Override
    public void skipToPrevious() {
        if (isShutdown()) return;

        final Runnable task = new Runnable() {
            @Override
            public void run() {

                final boolean byUser = true;

                // Guarded access to internal state
                synchronized (mStateLock) {

                    final AudioSourceQueue queue = mCurrentQueue;
                    final @RepeatMode int repeatMode = mRepeatMode;
                    int positionInQueue = mCurrentPositionInQueue;
                    AudioSource item = mCurrentItem;

                    if (queue.isEmpty()) {
                        // How is it possible?
                        return;
                    }

                    if (!byUser && repeatMode == Player.REPEAT_ONE) {
                        // everything remains the same
                    } else {
                        // The previous position is the current one - 1
                        positionInQueue--;

                        if (positionInQueue < 0) {
                            // We've reached the start of the queue => so we get to the ned of the queue
                            positionInQueue = queue.getLength() - 1;;
                        }

                        if (positionInQueue >= 0) {
                            // The position is valid
                            item = queue.getItemAt(positionInQueue);
                        } else {
                            // Undefined state, in fact
                            item = null;
                        }
                    }

                    mCurrentPositionInQueue = positionInQueue;
                    mCurrentItem = item;

                    _handleSource(item, 0, mIsPlayingFlag).run();
                }
            }
        };

        processEngineTask(true, task);
    }

    @Override
    public void skipToNext() {
        if (isShutdown()) return;

        processEngineTask(_skipToNext(true));
    }

    @Override
    public void skipTo(final int position, final boolean forceStartPlaying) {
        if (isShutdown()) return;

        final Runnable task = new Runnable() {
            @Override
            public void run() {

                // Guarded access to internal state
                synchronized (mStateLock) {

                    final AudioSourceQueue queue = mCurrentQueue;

                    final int currentPositionInQueue = mCurrentPositionInQueue;

                    if (position == currentPositionInQueue) {
                        // Good, we're at this position already
                        return;
                    }

                    if (position < 0 || position >= queue.getLength()) {
                        // No sir, it won't work
                        return;
                    }

                    final AudioSource item = queue.getItemAt(position);

                    mCurrentPositionInQueue = position;
                    mCurrentItem = item;

                    _handleSource(item, 0, mIsPlayingFlag || forceStartPlaying).run();
                }
            }
        };

        processEngineTask(true, task);
    }

    @Override
    public void skipTo(@NotNull final AudioSource item, final boolean forceStartPlaying) {
        if (isShutdown()) return;

        final Runnable task = new Runnable() {
            @Override
            public void run() {

                // Guarded access to internal state
                synchronized (mStateLock) {
                    final AudioSourceQueue queue = mCurrentQueue;

                    final int newPositionInQueue = queue.indexOf(item);
                    // OK, the queue contains the given item
                    if (newPositionInQueue >= 0) {
                        mCurrentPositionInQueue = newPositionInQueue;
                        mCurrentItem = item;
                        _handleSource(item, 0, mIsPlayingFlag || forceStartPlaying).run();
                    }
                }
            }
        };

        processEngineTask(task);
    }

    @Override
    public boolean isPrepared() {
        return mIsPreparedFlag;
    }

    /**
     * Checks if the player is actually playing now.
     * Delegates the call to the engine.
     * @return true if the player is actually playing now, false - otherwise.
     */
    public boolean isActuallyPlaying() {
        final MediaPlayer engine = maybeGetEngine();
        try {
            return engine != null && engine.isPlaying();
        } catch (Throwable error) {
            report(error);
            return false;
        }
    }

    @Override
    public boolean isPlaying() {
        // TODO: should we return mIsPlayingFlag instead?
        return isActuallyPlaying();
    }

    @Override
    public int getAudiSessionId() {
        if (isShutdown()) return NO_AUDIO_SESSION;
        final MediaPlayer engine = maybeGetEngine();
        try {
            return engine != null ? engine.getAudioSessionId() : NO_AUDIO_SESSION;
        } catch (Throwable error) {
            report(error);
            return NO_AUDIO_SESSION;
        }
    }

    @Nullable
    @Override
    public AudioSource getCurrent() {
        return mCurrentItem;
    }

    @Override
    public int getCurrentPositionInQueue() {
        return mCurrentPositionInQueue;
    }

    @Nullable
    @Override
    public AudioSourceQueue getCurrentQueue() {
        return mCurrentQueue;
    }

    @Override
    public int getProgress() {
        final MediaPlayer engine = maybeGetEngine();
        try {
            return engine != null ? engine.getCurrentPosition() : 0;
        } catch (Throwable error) {
            report(error);
            return 0;
        }
    }

    @Override
    public void seekTo(int position) {
        final MediaPlayer engine = maybeGetEngine();
        try {
            if (engine != null) {
                engine.seekTo(position);
            }
        } catch (Throwable error) {
            report(error);
        }
    }

    @Override
    public int getDuration() {
        final MediaPlayer engine = maybeGetEngine();
        try {
            return engine != null ? engine.getDuration() : 0;
        } catch (Throwable error) {
            report(error);
            return 0;
        }
    }

    @Override
    public void start() {
        final Runnable task = new Runnable() {
            @Override
            public void run() {

                // Guarded access to internal state
                synchronized (mStateLock) {
                    final MediaPlayer engine = maybeGetEngine();
                    if (engine == null) {
                        return;
                    }

                    // The focus should be granted as well
                    if (mIsPreparedFlag && tryRequestAudioFocus()) {
                        mIsPlayingFlag = true;
                        try {
                            engine.start();
                            maybeAdjustVolume();
                            mObserverRegistry.dispatchPlaybackStarted();
                        } catch (Throwable error) {
                            report(error);
                        }
                    }
                }
            }
        };

        processEngineTask(task);
    }

    @Override
    public void pause() {
        if (isShutdown()) return;

        final Runnable task = new Runnable() {
            @Override
            public void run() {

                // Guarded access to internal state
                synchronized (mStateLock) {
                    final MediaPlayer engine = maybeGetEngine();
                    if (engine == null) {
                        return;
                    }

                    if (mIsPreparedFlag) {
                        mIsPlayingFlag = false;
                        try {
                            engine.pause();
                            mObserverRegistry.dispatchPlaybackPaused();
                        } catch (Throwable error) {
                            report(error);
                        }
                    }
                }
            }
        };

        processEngineTask(task);
    }

    @Override
    public void toggle() {
        if (isShutdown()) return;

        final Runnable task = new Runnable() {
            @Override
            public void run() {

                // Guarded access to internal state
                synchronized (mStateLock) {
                    final MediaPlayer engine = maybeGetEngine();
                    if (engine == null) {
                        return;
                    }

                    final boolean wasPlaying = mIsPlayingFlag;
                    final boolean nextPlayingFlag = !wasPlaying;

                    if (mIsPreparedFlag) {
                        mIsPlayingFlag = nextPlayingFlag;
                        try {
                            if (nextPlayingFlag) {

                                // Only if the focus is granted
                                if (tryRequestAudioFocus()) {
                                    engine.start();
                                    maybeAdjustVolume();
                                    mObserverRegistry.dispatchPlaybackStarted();
                                }

                            } else {
                                engine.pause();
                                mObserverRegistry.dispatchPlaybackPaused();
                            }

                        } catch (Throwable error) {
                            report(error);
                        }
                    }
                }
            }
        };

        processEngineTask(task);
    }

    @Override
    public void update(@NotNull final AudioSource item) {
        if (isShutdown()) return;

        final Runnable task = new Runnable() {
            @Override
            public void run() {

                // Guarded access to internal state
                synchronized (mStateLock) {
                    final AudioSourceQueue originQueue = mOriginQueue;
                    final AudioSourceQueue currentQueue = mCurrentQueue;
                    final AudioSource currentItem = mCurrentItem;

                    if (originQueue != null) {
                        originQueue.replaceAllWithSameId(item);
                    }

                    currentQueue.replaceAllWithSameId(item);

                    if (currentItem != null && AudioSources.areSourcesTheSame(currentItem, item)) {
                        mCurrentItem = item;
                        mObserverRegistry.dispatchAudioSourceChanged(item, mCurrentPositionInQueue);
                    }
                }
            }
        };

        processEngineTask(task);
    }

    private Runnable _resolveUndefinedState(boolean startPlaying) {
        return new Runnable() {
            @Override
            public void run() {

                // Guarded access to internal state
                synchronized (mStateLock) {
                    final AudioSourceQueue currentQueue = mCurrentQueue;
                    int currentPositionInQueue = mCurrentPositionInQueue;
                    AudioSource currentItem = mCurrentItem;

                    if (currentQueue.isEmpty()) {
                        mCurrentPositionInQueue = NO_POSITION_IN_QUEUE;
                        mCurrentItem = null;
                        _reset().run();
                    } else {
                        if (currentPositionInQueue < 0) {
                            currentPositionInQueue = 0;
                        } else if (currentPositionInQueue >= currentQueue.getLength()) {
                            currentPositionInQueue = currentQueue.getLength() - 1;
                        }

                        currentItem = currentQueue.getItemAt(currentPositionInQueue);

                        mCurrentItem = currentItem;
                        mCurrentPositionInQueue = currentPositionInQueue;

                        _handleSource(currentItem,0, startPlaying).run();
                    }
                }
            }
        };
    }

    @Override
    public void remove(final int position) {
        if (isShutdown()) return;

        final Runnable task = new Runnable() {
            @Override
            public void run() {

                // Guarded access to internal state
                synchronized (mStateLock) {
                    final AudioSourceQueue originQueue = mOriginQueue;
                    final AudioSourceQueue currentQueue = mCurrentQueue;
                    final AudioSource targetItem = currentQueue.getItemAt(position);
                    int currentPositionInQueue = mCurrentPositionInQueue;

                    if (originQueue != null) {
                        originQueue.remove(targetItem);
                    }
                    currentQueue.removeAt(position);

                    if (position < currentPositionInQueue) {
                        currentPositionInQueue--;
                        mCurrentPositionInQueue = currentPositionInQueue;
                    } else if (position == currentPositionInQueue) {
                        mCurrentItem = null;
                        _resolveUndefinedState(mIsPlayingFlag).run();
                    }

                }
            }
        };

        processEngineTask(task);
    }

    @Override
    public void removeAll(@NotNull final Collection<? extends AudioSource> items) {
        if (isShutdown()) return;

        final Runnable task = new Runnable() {
            @Override
            public void run() {

                // Guarded access to internal state
                synchronized (mStateLock) {
                    final AudioSourceQueue originQueue = mOriginQueue;
                    final AudioSourceQueue currentQueue = mCurrentQueue;
                    int currentPositionInQueue = mCurrentPositionInQueue;

                    for (AudioSource item : items) {

                        final int position = mCurrentQueue.indexOf(item);

                        if (position <= currentPositionInQueue) {
                            currentPositionInQueue--;
                        }

                        if (originQueue != null) {
                            originQueue.remove(item);
                        }
                        currentQueue.remove(item);

                    }

                    if (currentQueue.isEmpty() || items.contains(mCurrentItem)) {
                        mCurrentItem = null;
                        _resolveUndefinedState(mIsPlayingFlag).run();
                    }
                }
            }
        };

        processEngineTask(task);
    }

    @Override
    public void add(@NotNull AudioSource item) {
        addAll(Collections.singletonList(item));
    }

    @Override
    public void addAll(@NotNull final List<? extends AudioSource> items) {
        if (isShutdown()) return;

        final Runnable task = new Runnable() {
            @Override
            public void run() {

                // Guarded access to internal state
                synchronized (mStateLock) {
                    final AudioSourceQueue originQueue = mOriginQueue;
                    final AudioSourceQueue currentQueue = mCurrentQueue;

                    if (originQueue != null) {
                        originQueue.addAll(items);
                    }
                    currentQueue.addAll(items);

                    if (mCurrentItem == null && !currentQueue.isEmpty()) {
                        final int currentPositionInQueue = 0;
                        final AudioSource currentItem = currentQueue.getItemAt(currentPositionInQueue);

                        mCurrentItem = currentItem;
                        mCurrentPositionInQueue = currentPositionInQueue;

                        _handleSource(currentItem, 0, false).run();
                    }
                }
            }
        };

        processEngineTask(task);
    }

    @Override
    public void addNext(@NotNull AudioSource item) {
        addAllNext(Collections.singletonList(item));
    }

    @Override
    public void addAllNext(@NotNull final List<? extends AudioSource> items) {
        if (isShutdown()) return;

        final Runnable task = new Runnable() {
            @Override
            public void run() {

                // Guarded access to internal state
                synchronized (mStateLock) {
                    final AudioSourceQueue originQueue = mOriginQueue;
                    final AudioSourceQueue currentQueue = mCurrentQueue;
                    int currentPositionInQueue = mCurrentPositionInQueue;
                    AudioSource currentItem = mCurrentItem;

                    final int targetPosition = Math.max(0, currentPositionInQueue);

                    if (originQueue != null) {
                        originQueue.addAll(targetPosition + 1, items);
                    }
                    currentQueue.addAll(targetPosition + 1, items);

                    if (currentItem == null && !currentQueue.isEmpty()) {
                        currentPositionInQueue = 0;
                        currentItem = currentQueue.getItemAt(currentPositionInQueue);

                        mCurrentPositionInQueue = currentPositionInQueue;
                        mCurrentItem = currentItem;

                        _handleSource(currentItem, 0, false).run();
                    }
                }
            }
        };

        processEngineTask(task);
    }

    @Override
    public void moveItem(final int fromPosition, final int toPosition) {
        if (isShutdown()) return;

        final Runnable task = new Runnable() {
            @Override
            public void run() {

                // Guarded access to internal state
                synchronized (mStateLock) {
                    final AudioSourceQueue originQueue = mOriginQueue;
                    final AudioSourceQueue currentQueue = mCurrentQueue;
                    int currentPositionInQueue = mCurrentPositionInQueue;
                    //AudioSource currentItem = mCurrentItem;

                    if (originQueue != null) {
                        if (fromPosition >= 0 && toPosition >= 0) {
                            originQueue.moveItem(fromPosition, toPosition);
                        }
                    }

                    // Finding out what the current position will be
                    if (currentPositionInQueue == fromPosition) {
                        currentPositionInQueue = toPosition;
                    } else if (currentPositionInQueue < fromPosition) {
                        if (currentPositionInQueue < toPosition) {
                            // no changes
                        } else {
                            currentPositionInQueue++;
                        }
                    } else if (currentPositionInQueue > fromPosition) {
                        if (currentPositionInQueue > toPosition) {
                            // no changes
                        } else {
                            currentPositionInQueue--;
                        }
                    }

                    mCurrentPositionInQueue = currentPositionInQueue;

                    if (fromPosition >= 0 && toPosition >= 0) {
                        currentQueue.moveItem(fromPosition, toPosition);
                    }
                }
            }
        };

        processEngineTask(task);
    }

    @NotNull
    @Override
    public final AudioFx getAudioFx() {
        return mAudioFx;
    }

    @Override
    public boolean isAPointed() {
        return mABEngine.isAPointed();
    }

    @Override
    public boolean isBPointed() {
        return mABEngine.isBPointed();
    }

    @Override
    public void pointA(int position) {
        mABEngine.pointA(position);
    }

    @Override
    public void pointB(int position) {
        mABEngine.pointB(position);
    }

    @Override
    public void resetAB() {
        mABEngine.reset();
    }

    private void tryRewind(int intervalValue) {
        if (isShutdown()) return;

        final MediaPlayer engine = maybeGetEngine();
        if (engine == null) return;

        try {
            final int newPosition = engine.getCurrentPosition() + intervalValue;
            engine.seekTo(newPosition);
        } catch (Throwable error) {
            report(error);
        }
    }

    @Override
    public void rewindForward(int interval) {
        tryRewind(interval);
    }

    @Override
    public void rewindBackward(int interval) {
        tryRewind(-interval);
    }

    public void setVolume(float level) {
        final MediaPlayer engine = maybeGetEngine();
        try {
            if (engine != null) {
                engine.setVolume(level, level);
            }
        } catch (Throwable error) {
            report(error);
        }
    }

    private void startCrossFadeTimer() {
        if (isShutdown()) return;

        final Timer oldTimer = mCrossFadeTimer;
        if (oldTimer != null) {
            oldTimer.purge();
            oldTimer.cancel();
        }

        final TimerTask task = new TimerTask() {
            @Override
            public void run() {
                while (!isShutdown()) {

                    maybeAdjustVolume();

                    try {
                        // Sleeping a little until the next volume adjustment
                        Thread.sleep(100);
                    } catch (InterruptedException error) {
                        return;
                    }
                }
            }
        };

        final Timer newTimer = new Timer("CrossFadeTimer");

        newTimer.schedule(task, 0);

        mCrossFadeTimer = newTimer;
    }

    private void maybeAdjustVolume() {
        final CrossFadeStrategy strategy = mCrossFadeStrategy;
        if (strategy == null) {
            // No strategy - no cross fade
            return;
        }

        synchronized (mStateLock) {
            final MediaPlayer engine = maybeGetEngine();
            try {
                if (engine != null && mIsPreparedFlag) {
                    final int progress = engine.getCurrentPosition();
                    final int duration = engine.getDuration();

                    float level = strategy.calculateLevel(progress, duration);
                    // TODO: how to manipulate the level
                    level = (float) Math.pow(level, 2);

                    engine.setVolume(level, level);
                }
            } catch (Throwable error) {
                report(error);
            }
        }
    }

    @Nullable
    @Override
    public CrossFadeStrategy getCrossFadeStrategy() {
        return mCrossFadeStrategy;
    }

    @Override
    public void setCrossFadeStrategy(@Nullable CrossFadeStrategy strategy) {
        mCrossFadeStrategy = strategy;
        maybeAdjustVolume();
    }

    @Nullable
    private PlaybackParams tryGetPlaybackParams() {
        if (isShutdown()) return null;

        final MediaPlayer engine = maybeGetEngine();
        if (engine == null) return null;

        try {
            return engine.getPlaybackParams();
        } catch (Throwable error) {
            report(error);
            return null;
        }
    }

    @Override
    public float getSpeed() {
        final PlaybackParams params = tryGetPlaybackParams();
        try {
            return params != null ? params.getSpeed() : SPEED_NORMAL;
        } catch (Throwable error) {
            report(error);
            return SPEED_NORMAL;
        }
    }

    @Override
    public void setSpeed(float speed) {
        if (isShutdown()) return;

        final MediaPlayer engine = maybeGetEngine();
        if (engine == null) return;

        try {
            final PlaybackParams params = engine.getPlaybackParams();
            params.setSpeed(MathUtil.clamp(SPEED_RANGE, speed));
            engine.setPlaybackParams(params);
        } catch (Throwable error) {
            report(error);
        }
    }

    @Override
    public float getPitch() {
        final PlaybackParams params = tryGetPlaybackParams();
        try {
            return params != null ? params.getPitch() : PITCH_NORMAL;
        } catch (Throwable error) {
            report(error);
            return PITCH_NORMAL;
        }
    }

    @Override
    public void setPitch(float pitch) {
        if (isShutdown()) return;

        final MediaPlayer engine = maybeGetEngine();
        if (engine == null) return;

        try {
            final PlaybackParams params = engine.getPlaybackParams();
            params.setPitch(MathUtil.clamp(PITCH_RANGE, pitch));
            engine.setPlaybackParams(params);
        } catch (Throwable error) {
            report(error);
        }
    }

    @Override
    public int getShuffleMode() {
        return mShuffleMode;
    }

    @Override
    public void setShuffleMode(final int mode) {
        if (isShutdown()) return;

        final Runnable task = new Runnable() {
            @Override
            public void run() {

                // Guarded access to internal state
                synchronized (mStateLock) {
                    mShuffleMode = mode;

                    final AudioSourceQueue originQueue = mOriginQueue;
                    final AudioSourceQueue currentQueue = mCurrentQueue;
                    final AudioSource currentItem = mCurrentItem;

                    if (mode == Player.SHUFFLE_OFF) {
                        final AudioSourceQueue targetQueue =
                                originQueue != null ? originQueue : AudioSourceQueue.empty();

                        currentQueue.copyItemsFrom(targetQueue);
                    } else {
                        currentQueue.shuffleWithItemInFront(currentItem);
                    }

                    mCurrentPositionInQueue = currentQueue.indexOf(currentItem);

                    mObserverRegistry.dispatchShuffleModeChanged(mode);
                }
            }
        };

        processEngineTask(task);
    }

    @Override
    public int getRepeatMode() {
        return mRepeatMode;
    }

    @Override
    public void setRepeatMode(int mode) {
        if (isShutdown()) return;

        mRepeatMode = mode;
        mObserverRegistry.dispatchRepeatModeChanged(mode);
    }

    @Override
    public void shutdown() {
        if (mShutdown.getAndSet(true)) return;

        mABEngine.reset();

        final Timer oldTimer = mCrossFadeTimer;
        if (oldTimer != null) {
            oldTimer.purge();
            oldTimer.cancel();
        }
        mCrossFadeTimer = null;

        mIsPreparedFlag = false;
        mIsPlayingFlag = false;

        mOriginQueue = null;
        mCurrentItem = null;
        mCurrentPositionInQueue = NO_POSITION_IN_QUEUE;

        final MediaPlayer engine = mEngine;
        if (engine != null) {
            try {
                engine.release();
            } catch (Throwable error) {
                report(error);
            }
        }
        mEngine = null;

        mObserverRegistry.dispatchShutdown();
    }

    /**
     * Thread-safe implementation of A-B engine.
     * The player can simply delegate its AB-related methods to this engine.
     */
    private final class ABEngine {

        private final static int MIN_AB_INTERVAL = 250;

        @Nullable
        volatile Integer mAPoint;
        @Nullable
        volatile Integer mBPoint;

        @Nullable
        volatile Timer mCurrentTimer;

        synchronized boolean isAPointed() {
            return mAPoint != null;
        }

        synchronized boolean isBPointed() {
            return mBPoint != null;
        }

        synchronized void pointA(int position) {
            final int duration = getDuration();

            int validAPoint = position;
            if (validAPoint > duration - MIN_AB_INTERVAL) {
                validAPoint = duration - MIN_AB_INTERVAL;
            }

            final Integer bPoint = mBPoint;
            if (bPoint != null && position > bPoint - MIN_AB_INTERVAL) {
                validAPoint = bPoint - MIN_AB_INTERVAL;
            }

            if (validAPoint < 0) {
                validAPoint = 0;
            }

            this.mAPoint = validAPoint;
            _start();
            mObserverRegistry.dispatchABChanged(true, bPoint != null);
        }

        synchronized void pointB(int position) {
            int validBPoint = position;
            if (validBPoint < 0) {
                validBPoint = 0;
            }

            final Integer aPoint = mAPoint;
            if (aPoint != null && position < aPoint + MIN_AB_INTERVAL) {
                validBPoint = aPoint + MIN_AB_INTERVAL;
            }

            final int duration = getDuration();
            if (validBPoint > duration) {
                validBPoint = duration;
            }

            this.mBPoint = validBPoint;
            _start();
            mObserverRegistry.dispatchABChanged(aPoint != null, true);
        }

        synchronized void reset() {
            mAPoint = null;
            mBPoint = null;

            final Timer old = mCurrentTimer;
            if (old != null) {
                old.purge();
                old.cancel();
            }
            mCurrentTimer = null;

            mObserverRegistry.dispatchABChanged(false, false);
        }

        private synchronized void _start() {

            // Canceling the previous timer, if any.
            // Cause we don't want two timers doing the A-B job.
            final Timer old = mCurrentTimer;
            if (old != null) {
                old.purge();
                old.cancel();
            }

            final TimerTask task = new TimerTask() {
                @Override
                public void run() {

                    Integer a;
                    Integer b;

                    synchronized (ABEngine.this) {
                        a = mAPoint;
                        b = mBPoint;
                    }

                    // We're going to do this while the A-B is enabled.
                    // The A-B is considered enabled if and only if both A and B points are not null.
                    while (a != null && b != null) {
                        try {

                            final int pos = getProgress();
                            if (pos < a - 100) {
                                // Changing the progress
                                seekTo(a);
                                continue;
                            }

                            final int sleep = b - pos;
                            if (sleep > 0) {
                                // Sleeping to wait for the point B to be reached
                                Thread.sleep(sleep);
                            }

                            synchronized (ABEngine.this) {
                                a = mAPoint;
                                b = mBPoint;
                            }

                            // Additional check just before seeking the position.
                            // Just to make sure the A-B has not changed after sleeping the thread.
                            if (a == null || b == null) {
                                break;
                            }

                            // Seeking the position to the point A
                            seekTo(a);

                        } catch (InterruptedException error) {
                            break;
                        }
                    }
                }
            };

            final Timer newTimer = new Timer();

            newTimer.schedule(task, 0);

            // Saving the timer
            mCurrentTimer = newTimer;
        }
    }

}
