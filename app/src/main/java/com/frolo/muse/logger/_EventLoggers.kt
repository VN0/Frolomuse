package com.frolo.muse.logger

import androidx.annotation.StringDef
import com.frolo.muse.repository.Preferences

/**
 * Convenient methods for logging main events in the App.
 */

fun EventLogger.logAppLaunched(launchCount: Int) {
    val params = mapOf("launch_count" to launchCount.toString())
    log("app_launched", params)
}

fun EventLogger.logThemeChanged(@Preferences.Theme theme: Int) {
    val paramValue = when (theme) {
        Preferences.THEME_DARK_BLUE ->          "dark_blue"
        Preferences.THEME_DARK_BLUE_ESPECIAL -> "dark_blue_especial"
        Preferences.THEME_DARK_PURPLE ->        "dark_purple"
        Preferences.THEME_DARK_YELLOW ->        "dark_yellow"
        Preferences.THEME_LIGHT_BLUE ->         "light_blue"
        else ->                                 "unknown"
    }

    val params = mapOf("theme_value" to paramValue)

    log("theme_changed", params)
}

fun EventLogger.logCustomPresetSaved() {
    log("custom_preset_saved")
}

fun EventLogger.logCustomPresetDeleted() {
    log("custom_preset_deleted")
}

fun EventLogger.logMinAudioFileDurationSet(durationInSeconds: Int) {
    val params = mapOf("duration_in_seconds" to durationInSeconds.toString())
    log("min_audio_file_duration_set", params)
}

fun EventLogger.logSleepTimerSet(hours: Int, minutes: Int, seconds: Int) {
    val durationInSeconds = hours * 60 * 60 + minutes * 60 + seconds
    val params = mapOf("duration_in_seconds" to durationInSeconds.toString())
    log("sleep_timer_set", params)
}

//region Rate App Dialog

@Retention(AnnotationRetention.SOURCE)
@StringDef(RATE_DIALOG_ANSWER_YES, RATE_DIALOG_ANSWER_NO, RATE_DIALOG_ANSWER_REMIND_LATER, RATE_DIALOG_ANSWER_NULL)
annotation class RateDialogAnswer

@RateDialogAnswer const val RATE_DIALOG_ANSWER_YES = "yes"
@RateDialogAnswer const val RATE_DIALOG_ANSWER_NO = "no"
@RateDialogAnswer const val RATE_DIALOG_ANSWER_REMIND_LATER = "remind_later"
@RateDialogAnswer const val RATE_DIALOG_ANSWER_NULL = "NULL"

fun EventLogger.logRateDialogAnswered(@RateDialogAnswer answer: String) {
    val params = mapOf(
        "answer" to answer,
        "cancelled" to false.toString()
    )
    log("rate_dialog", params)
}

fun EventLogger.logRateDialogCancelled() {
    val params = mapOf(
        "answer" to RATE_DIALOG_ANSWER_NULL,
        "cancelled" to true.toString()
    )
    log("rate_dialog", params)
}

//endregion

fun EventLogger.logAppRatedFromSettings() {
    log("app_rated_from_settings")
}

fun EventLogger.logAppSharedFromSettings() {
    log("app_shared_from_settings")
}

// TODO: add other main events