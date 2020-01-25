package com.frolo.muse.ui.main.library.mostplayed

import android.os.Bundle
import androidx.lifecycle.LifecycleOwner
import com.bumptech.glide.Glide
import com.frolo.muse.arch.observeNonNull
import com.frolo.muse.model.media.SongWithPlayCount
import com.frolo.muse.ui.main.AlbumArtUpdateHandler
import com.frolo.muse.ui.main.library.base.SimpleMediaCollectionFragment
import com.frolo.muse.ui.main.library.base.SongAdapter


class MostPlayedFragment : SimpleMediaCollectionFragment<SongWithPlayCount>() {

    override val viewModel: MostPlayedViewModel by viewModel()

    override val adapter: SongAdapter<SongWithPlayCount> by lazy {
        SongWithPlayCountAdapter(Glide.with(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlbumArtUpdateHandler.attach(this) { _, _ ->
            adapter.forceResubmit()
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        observeViewModel(viewLifecycleOwner)
    }

    private fun observeViewModel(owner: LifecycleOwner) {
        viewModel.apply {
            isPlaying.observeNonNull(owner) { isPlaying ->
                adapter.setPlayingState(isPlaying)
            }

            playingPosition.observeNonNull(owner) { playingPosition ->
                val isPlaying = isPlaying.value ?: false
                adapter.setPlayingPositionAndState(playingPosition, isPlaying)
            }

        }
    }

}