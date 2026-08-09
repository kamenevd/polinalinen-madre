package com.polinalinen.madre.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.polinalinen.madre.MainActivity

/**
 * Cycle 18: кнопка «Покормила» прямо в шторке.
 *
 * Раньше напоминание только звало: чтобы записать кормление, надо было
 * открыть книгу, дойти до дневника и оттуда до формы. Теперь кнопка ведёт
 * сразу на форму кормления.
 *
 * Именно на форму, а не «отметить кормление молча». Отметить его молча нечем:
 * сколько муки и воды пошло — знает человек, а книга знала бы разве что
 * прошлые граммы. Запись в дневнике, придуманная за человека, — это не
 * экономия тапа, это неправда в дневнике (см. hard rule 8 в CLAUDE.md).
 */
object FeedingReminderAction {

    /** Женский род намеренно: книгу ведёт Полина, и говорит книга с ней. */
    const val LABEL = "Покормила"

    /**
     * Свой код запроса, не пересекающийся со строкой хода выпечки: иначе
     * PendingIntent'ы затирали бы друг друга при совпадении остальных полей.
     */
    private const val REQUEST_CODE = 1101

    /** Намерение открыть книгу на форме кормления. */
    fun openFeedingForm(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_FEEDING, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            openFeedingForm(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
