package com.frolo.muse.ui.main.library.base

import com.frolo.muse.navigator.Navigator
import com.frolo.muse.interactor.media.*
import com.frolo.muse.interactor.media.favourite.ChangeFavouriteUseCase
import com.frolo.muse.interactor.media.favourite.GetIsFavouriteUseCase
import com.frolo.muse.interactor.media.get.GetMediaUseCase
import com.frolo.muse.logger.EventLogger
import com.frolo.muse.model.media.Media
import com.frolo.muse.rx.SchedulerProvider


class TestMediaCollectionViewModel<T> constructor(
        getMediaUseCase: GetMediaUseCase<T>,
        getMediaMenuUseCase: GetMediaMenuUseCase<T>,
        clickMediaUseCase: ClickMediaUseCase<T>,
        playMediaUseCase: PlayMediaUseCase<T>,
        shareMediaUseCase: ShareMediaUseCase<T>,
        deleteMediaUseCase: DeleteMediaUseCase<T>,
        getIsFavouriteUseCase: GetIsFavouriteUseCase<T>,
        changeFavouriteUseCase: ChangeFavouriteUseCase<T>,
        schedulerProvider: SchedulerProvider,
        navigator: Navigator,
        eventLogger: EventLogger
): AbsMediaCollectionViewModel<T>(
        getMediaUseCase,
        getMediaMenuUseCase,
        clickMediaUseCase,
        playMediaUseCase,
        shareMediaUseCase,
        deleteMediaUseCase,
        getIsFavouriteUseCase,
        changeFavouriteUseCase,
        schedulerProvider,
        navigator,
        eventLogger
) where T: Media