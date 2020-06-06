package com.frolo.muse.interactor.media.get

import com.frolo.muse.model.media.Media
import com.frolo.muse.model.menu.SortOrderMenu
import com.frolo.muse.model.sort.SortOrder
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Single


interface GetMediaUseCase<E: Media> {

    /**
     * Returns the sort order menu according the current preferences.
     */
    fun getSortOrderMenu(): Single<SortOrderMenu>

    /**
     * Saves the given [sortOrder] as preferred
     * and returns a new flowable that emits media list according the given sort order.
     */
    fun applySortOrder(sortOrder: SortOrder): Completable

    /**
     * Saved the given [isReversed] flag as preferred
     * and returns a new flowable that emits media list according the given [isReversed] flag.
     */
    fun applySortOrderReversed(isReversed: Boolean): Completable

    /**
     * Returns a flowable that emits media list according the current sort order preferences.
     */
    fun getMediaList(): Flowable<List<E>>

}