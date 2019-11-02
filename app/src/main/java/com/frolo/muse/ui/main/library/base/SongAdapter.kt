package com.frolo.muse.ui.main.library.base

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.bumptech.glide.RequestManager
import com.frolo.muse.GlideManager
import com.frolo.muse.R
import com.frolo.muse.model.media.Song
import com.frolo.muse.ui.getArtistString
import com.frolo.muse.ui.getDurationString
import com.frolo.muse.ui.getNameString
import com.frolo.muse.util.CharSequences
import com.l4digital.fastscroll.FastScroller
import kotlinx.android.synthetic.main.include_check.view.*
import kotlinx.android.synthetic.main.item_song.view.*


open class SongAdapter constructor(
        private val requestManager: RequestManager
): BaseAdapter<Song, SongAdapter.SongViewHolder>(), FastScroller.SectionIndexer {

    var playingPosition = -1
        private set
    var isPlaying = false
        private set

    override fun getItemId(position: Int) = getItemAt(position).id

    fun submit(list: List<Song>, position: Int, isPlaying: Boolean) {
        this.playingPosition = position
        this.isPlaying = isPlaying
        submit(list)
    }

    fun setPlayingPositionAndState(position: Int, isPlaying: Boolean) {
        if (this.playingPosition == position
                && this.isPlaying == isPlaying) {
            return
        }

        if (playingPosition >= 0) {
            this.isPlaying = false
            notifyItemChanged(playingPosition)
        }
        this.playingPosition = position
        this.isPlaying = isPlaying
        notifyItemChanged(position)
    }

    fun setPlayingState(isPlaying: Boolean) {
        if (this.isPlaying == isPlaying) {
            return
        }

        this.isPlaying = isPlaying
        if (playingPosition >= 0)
            notifyItemChanged(playingPosition)
    }

    override fun onPreRemove(position: Int) {
        if (playingPosition == position) {
            playingPosition = -1
        } else if (playingPosition > position) {
            playingPosition--
        }
    }

    override fun onPreSwap(fromPosition: Int, toPosition: Int) {
        when (playingPosition) {
            fromPosition -> playingPosition = toPosition
            in (fromPosition + 1)..toPosition -> playingPosition--
            in toPosition until fromPosition -> playingPosition++
        }
    }

    override fun onCreateBaseViewHolder(
            parent: ViewGroup,
            viewType: Int): SongViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return SongViewHolder(inflater.inflate(R.layout.item_song, parent, false))
    }

    override fun onBindViewHolder(
            holder: SongViewHolder,
            position: Int,
            item: Song,
            selected: Boolean,
            selectionChanged: Boolean) {

        with(holder.itemView) {
            val res = resources
            tv_song_name.text = item.getNameString(res)
            tv_artist_name.text = item.getArtistString(res)
            tv_duration.text = item.getDurationString()

            val options = GlideManager.get()
                    .requestOptions(item.albumId)
                    .placeholder(R.drawable.ic_note_rounded_placeholder)
            requestManager
                    .load(GlideManager.albumArtUri(item.albumId))
                    .apply(options)
                    .circleCrop()
                    .into(imv_album_art)

            if (position != playingPosition) {
                mini_visualizer.visibility = View.GONE
                mini_visualizer.setAnimating(false)
            } else {
                mini_visualizer.visibility = View.VISIBLE
                mini_visualizer.setAnimating(isPlaying)
            }

            imv_check.setChecked(selected, selectionChanged)

            isSelected = selected
        }
    }

    override fun getSectionText(position: Int): CharSequence {
        if (position >= itemCount) return CharSequences.empty()
        return getItemAt(position).title.let { title ->
            CharSequences.firstCharOrEmpty(title)
        }
    }

    open class SongViewHolder(itemView: View): BaseViewHolder(itemView) {
        override val viewOptionsMenu: View? = itemView.view_options_menu
    }

}