package com.frolo.muse.repository

import com.frolo.muse.model.media.SongFilter
import com.frolo.muse.model.media.SongType
import io.reactivex.Completable
import io.reactivex.Flowable


interface LibraryPreferences : SongFilterProvider {
    override fun getSongFilter(): Flowable<SongFilter>

    fun getSongTypes(): Flowable<List<SongType>>
    fun setSongTypes(types: List<SongType>): Completable

    fun getMinAudioDuration(): Flowable<Long>
    fun setMinAudioDuration(duration: Long): Completable
}