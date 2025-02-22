package com.frolo.muse.ui;

import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.frolo.muse.BuildConfig;
import com.frolo.player.Player;
import com.frolo.muse.ui.base.BaseActivity;


abstract public class PlayerHostActivity
        extends BaseActivity
        implements PlayerHostFragment.PlayerConnectionHandler {

    private static final boolean DEBUG = BuildConfig.DEBUG;
    private static final String LOG_TAG = "PlayerHostActivity";

    private static final String FRAG_TAG_PLAYER_HOLDER = "com.frolo.muse.ui.PlayerHolder";

    private Bundle mLastSavedInstanceState;

    private Player mPlayer;

    private final Runnable mCheckPlayerHolderTask =
            new Runnable() {
                @Override
                public void run() {
                    checkPlayerHolder();
                }
            };

    private Handler mHandler;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        mLastSavedInstanceState = savedInstanceState;

        super.onCreate(savedInstanceState);

        mHandler = new Handler(getMainLooper());
        checkPlayerHolder();
    }

    @Override
    protected void onDestroy() {
        mLastSavedInstanceState = null;
        mHandler.removeCallbacks(mCheckPlayerHolderTask);
        mHandler = null;
        super.onDestroy();
    }

    /**
     * The check for player holder cannot be proceeded if the activity state is saved already.
     * See  {@link PlayerHostActivity#checkPlayerHolder()} method.
     * Because of that, we need to post the checker every time the activity restarts,
     * so we make sure that the check is passed and the player holder is added.
     */
    @Override
    protected void onRestart() {
        super.onRestart();
        mHandler.removeCallbacks(mCheckPlayerHolderTask);
        mHandler.post(mCheckPlayerHolderTask);
    }

    @Nullable
    protected Bundle getLastSavedInstanceState() {
        return mLastSavedInstanceState;
    }

    @Nullable
    protected Player getPlayer() {
        return mPlayer;
    }

    @NonNull
    protected Player requirePlayer() {
        Player player = mPlayer;
        if (player == null) {
            throw new IllegalStateException("Player is not connected yet");
        }
        return player;
    }

    @Override
    public final void onPlayerConnected(@NonNull Player player) {
        mPlayer = player;
        playerDidConnect(player);
        if (DEBUG) Log.d(LOG_TAG, "Player did connect");
    }

    @Override
    public final void onPlayerDisconnected() {
        final Player player = mPlayer;
        if (player != null) {
            playerDidDisconnect(player);
        }
        mPlayer = null;
        if (DEBUG) Log.d(LOG_TAG, "Player did disconnect");
    }

    protected abstract void playerDidConnect(@NonNull Player player);

    protected abstract void playerDidDisconnect(@NonNull Player player);

    /**
     * Checks if there is a valid instance of {@link PlayerHostFragment} in the fragment manager.
     * If not, then a new one will be added.
     * That PlayerHolder fragment is supposed to connect to a player service and dispatch about its lifecycle to this activity.
     * When a player gets connected, then {@link PlayerHostActivity#onPlayerConnected(Player)} method is called.
     * And when that player gets disconnected, then {@link PlayerHostActivity#onPlayerDisconnected()} method is called.
     */
    private void checkPlayerHolder() {
        final FragmentManager fm = getSupportFragmentManager();
        if (fm.isStateSaved()) {
            if (DEBUG) Log.d(LOG_TAG, "Could not check PlayerHolder: the state is saved");
            return;
        }

        // Finding PlayerHolder fragment
        final Fragment frag = fm.findFragmentByTag(FRAG_TAG_PLAYER_HOLDER);

        if (frag != null && frag instanceof PlayerHostFragment) {
            // OK, the PlayerHost fragment is there
            mPlayer = ((PlayerHostFragment) frag).getPlayer();
            // TODO: find out if we need to call onPlayerDidConnect here
            if (DEBUG) Log.d(LOG_TAG, "Checked PlayerHolder: it was added already");
            return;
        }

        // The PlayerHolder fragment is NULL.
        // We need to recreate everything from the very beginning.
        // The last saved instance state is not counted as valid in such a case.

        mLastSavedInstanceState = null;

        // Popping all entries from the back stack.
        for (int i = 0; i < fm.getBackStackEntryCount(); i++) {
            fm.popBackStackImmediate();
        }

        // First removing all fragments from the fragment manager.
        final FragmentTransaction removeAllFragmentsTransaction = fm.beginTransaction();
        for (final Fragment f : fm.getFragments()) {
            if (f != null) removeAllFragmentsTransaction.remove(f);
        }
        removeAllFragmentsTransaction.commitNow();

        // Finally, adding a new instance of PlayerHost fragment.
        // NOTE: This transaction MUST be added to the back stack,
        // so that the fragment will not be destroyed by any other transaction.
        fm.beginTransaction()
            .add(new PlayerHostFragment(), FRAG_TAG_PLAYER_HOLDER)
            .addToBackStack(null)
            .commit();

        if (DEBUG) Log.d(LOG_TAG, "PlayerHolder has been added successfully");
    }

}
