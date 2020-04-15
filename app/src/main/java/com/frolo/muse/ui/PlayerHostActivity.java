package com.frolo.muse.ui;

import android.os.Bundle;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.frolo.muse.engine.Player;
import com.frolo.muse.ui.base.BaseActivity;


abstract public class PlayerHostActivity
        extends BaseActivity
        implements PlayerHostFragment.PlayerConnectionHandler {

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
        mHandler.post(mCheckPlayerHolderTask);
    }

    @Override
    protected void onDestroy() {
        mLastSavedInstanceState = null;
        mHandler.removeCallbacks(mCheckPlayerHolderTask);
        mHandler = null;
        super.onDestroy();
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
    }

    @Override
    public final void onPlayerDisconnected() {
        final Player player = mPlayer;
        if (player != null) {
            playerDidDisconnect(player);
        }
        mPlayer = null;
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

        // Finding PlayerHolder fragment
        final Fragment frag = fm.findFragmentByTag(FRAG_TAG_PLAYER_HOLDER);

        final Player player;

        if (frag != null && frag instanceof PlayerHostFragment) {
            player = ((PlayerHostFragment) frag).getPlayer();
        } else {
            player = null;
        }

        if (frag == null || player == null) {
            // The PlayerHolder fragment or its player is NULL.
            // We need to recreate everything from the very beginning.
            // The last saved instance state is not counted as valid in such a case.

            mLastSavedInstanceState = null;

            // Popping all entries from the back stack.
            for (int i = 0; i < fm.getBackStackEntryCount(); i++) {
                fm.popBackStackImmediate();
            }

            // Removing all fragments from the fragment manager.
            FragmentTransaction removeAllFragmentsTransaction = fm.beginTransaction();
            for (final Fragment f : fm.getFragments()) {
                if (f != null) removeAllFragmentsTransaction.remove(f);
            }
            // TODO: check if the fragment manager state is saved before calling this method
            removeAllFragmentsTransaction.commitNow();

            // Finally,adding a new instance of PlayerHolderFragment.
            // To persist activity' configuration changes, this must be added to the back stack as well.
            fm.beginTransaction()
                .add(new PlayerHostFragment(), FRAG_TAG_PLAYER_HOLDER)
                .addToBackStack(null)
                .commit();
        } else {
            // OK, there is an instance of PlayerHolderFragment with a non-null player.
            // We can assume that it has been connected.
            onPlayerConnected(player);
        }
    }

}
