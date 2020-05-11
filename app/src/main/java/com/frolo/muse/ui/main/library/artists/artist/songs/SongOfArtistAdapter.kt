package com.frolo.muse.ui.main.library.artists.artist.songs

import android.view.ViewGroup
import com.bumptech.glide.RequestManager
import com.frolo.muse.R
import com.frolo.muse.glide.makeRequest
import com.frolo.muse.inflateChild
import com.frolo.muse.model.media.Song
import com.frolo.muse.ui.getAlbumString
import com.frolo.muse.ui.getDurationString
import com.frolo.muse.ui.getNameString
import com.frolo.muse.ui.main.library.base.SongAdapter
import com.frolo.muse.views.media.MediaConstraintLayout
import kotlinx.android.synthetic.main.include_check.view.*
import kotlinx.android.synthetic.main.include_song_art_container.view.*
import kotlinx.android.synthetic.main.item_song_of_artist.view.*


class SongOfArtistAdapter constructor(
    private val requestManager: RequestManager
): SongAdapter<Song>(requestManager) {

    override fun onCreateBaseViewHolder(
        parent: ViewGroup,
        viewType: Int
    ) = SongViewHolder(parent.inflateChild(R.layout.item_song_of_artist))

    override fun onBindViewHolder(
        holder: SongViewHolder,
        position: Int,
        item: Song,
        selected: Boolean, selectionChanged: Boolean
    ) {

        val isPlayPosition = position == playingPosition

        with(holder.itemView as MediaConstraintLayout) {
            val res = resources
            tv_song_name.text = item.getNameString(res)
            tv_album_name.text = item.getAlbumString(res)
            tv_duration.text = item.getDurationString()

            requestManager.makeRequest(item.albumId)
                .placeholder(R.drawable.ic_framed_music_note)
                .error(R.drawable.ic_framed_music_note)
                .circleCrop()
                .into(imv_album_art)

            imv_check.setChecked(selected, selectionChanged)

            setChecked(selected)
            setPlaying(isPlayPosition)
        }

        holder.resolvePlayingPosition(
            isPlayPosition = isPlayPosition,
            isPlaying = isPlaying
        )
    }

}