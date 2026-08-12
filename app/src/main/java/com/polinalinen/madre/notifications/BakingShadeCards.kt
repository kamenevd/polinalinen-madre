package com.polinalinen.madre.notifications

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.polinalinen.madre.R

/**
 * Cycle 21: две карточки книги в шторке — свёрнутая и развёрнутая.
 *
 * Вынесены из [BakingProgressService] не ради порядка. Внутри сервиса
 * RemoteViews нечем проверить: чтобы дотянуться до них, пришлось бы поднимать
 * сам сервис. Здесь же карточку можно собрать и разложить на настоящих View
 * (Robolectric) — и убедиться, что id разметки и id кода не разошлись, а
 * setInt зовёт метод, который у View действительно есть. И то и другое
 * разъезжается молча: RemoteViews не компилируются вместе с разметкой и падают
 * (или тихо не делают ничего) уже на телефоне.
 *
 * Ничего здесь не считается: все строки приходят готовыми из
 * [BakingNotificationContent], где их проверяет юнит-тест.
 *
 * ПОЧЕМУ СВОЯ БУМАГА, А НЕ СИСТЕМНЫЙ ФОН. Панель шторки в тёмной теме телефона
 * чёрная, и книга на ней была не читаема. values-night для этого не годится:
 * он перекрасил бы книгу целиком, а тёмной темы у неё нет и не будет без
 * решения Димы или Полины (hard rule №3). Своя бумага и свои чернила
 * выглядят одинаково при любой теме телефона.
 */
internal object BakingShadeCards {

    /**
     * Свёрнутая строка — тот же клочок бумаги, только в одну строку.
     *
     * Цифры здесь готовые. Раньше свёрнутая карточка была системной ради
     * системного же хронометра — единственных цифр, которые человек видел, не
     * разворачивая шторку; он же уводил их в минус, когда обновлять уведомление
     * становилось некому.
     */
    fun compact(context: Context, content: BakingNotificationContent): RemoteViews =
        RemoteViews(context.packageName, R.layout.notification_baking_compact).apply {
            paper(R.id.notif_compact_paper)
            setTextViewText(R.id.notif_compact_step, content.compactStepLine)
            ink(context, R.id.notif_compact_step, R.color.madre_notif_ink)
            setTextViewText(R.id.notif_compact_timer, content.timerText)
            ink(context, R.id.notif_compact_timer, content.timerColor())
            setContentDescription(R.id.notif_compact_timer, content.spokenTimer)
            setProgressBar(
                R.id.notif_compact_progress,
                BakingProgress.PROGRESS_MAX,
                content.progressPermille,
                false,
            )
        }

    /**
     * Cycle 19: развёрнутая карточка — страница книги, а не служебная строка.
     *
     * Cycle 21: крупный остаток — обычный текст. Chronometer, стоявший тут
     * прежде, тикал сам и потому не останавливался на нуле: досчитав до базы,
     * он продолжал считать в минус.
     */
    fun big(context: Context, content: BakingNotificationContent): RemoteViews =
        RemoteViews(context.packageName, R.layout.notification_baking_progress).apply {
            paper(R.id.notif_paper)
            setTextViewText(R.id.notif_header, content.headerLine)
            ink(context, R.id.notif_header, R.color.madre_notif_cocoa)
            setTextViewText(R.id.notif_step, content.stepLine)
            ink(context, R.id.notif_step, R.color.madre_notif_ink)
            setTextViewText(R.id.notif_next, content.nextLine)
            ink(context, R.id.notif_next, R.color.madre_notif_cocoa)
            setProgressBar(
                R.id.notif_progress,
                BakingProgress.PROGRESS_MAX,
                content.progressPermille,
                false,
            )

            if (content.badge == null) {
                setViewVisibility(R.id.notif_badge, View.GONE)
            } else {
                setViewVisibility(R.id.notif_badge, View.VISIBLE)
                setTextViewText(R.id.notif_badge, content.badge)
                // «Пауза» — не тревога: краснеет только «скоро».
                ink(
                    context,
                    R.id.notif_badge,
                    if (content.isUrgent) R.color.madre_notif_urgent else R.color.madre_notif_cocoa,
                )
            }

            setTextViewText(R.id.notif_timer_static, content.timerText)
            ink(context, R.id.notif_timer_static, content.timerColor())
            setContentDescription(R.id.notif_timer_static, content.spokenTimer)
        }

    /** Последние пять минут шага книга пишет терракотой — на обеих карточках. */
    private fun BakingNotificationContent.timerColor(): Int =
        if (isUrgent) R.color.madre_notif_urgent else R.color.madre_notif_ink

    /**
     * Бумага проставляется ИЗ КОДА, а не только в разметке.
     *
     * В XML она и так стоит, и на чистом Android этого хватало бы. Но прошивки
     * (MIUI, One UI и родня) в тёмной теме перекрашивают уведомления под себя,
     * проходя по уже инфлированному дереву, — и подложка, объявленная в
     * разметке, теряется там первой: чернила книги остаются на чёрной панели
     * шторки, где их не видно. Действие RemoteViews прикладывается после
     * инфляции, поверх такой перекраски, и стоит оно нам одного вызова.
     */
    private fun RemoteViews.paper(viewId: Int) {
        setInt(viewId, "setBackgroundResource", R.drawable.notification_paper)
    }

    /** Чернила — по той же причине и тем же порядком, что и бумага. */
    private fun RemoteViews.ink(context: Context, viewId: Int, colorRes: Int) {
        setTextColor(viewId, ContextCompat.getColor(context, colorRes))
    }
}
