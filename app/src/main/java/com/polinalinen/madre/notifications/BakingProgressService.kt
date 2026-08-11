package com.polinalinen.madre.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.MainActivity
import com.polinalinen.madre.R
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Cycle 12: строка хода выпечки в шторке — по одной на каждую активную выпечку.
 *
 * ПОЧЕМУ ИМЕННО FOREGROUND SERVICE. Требование — живой прогресс, а не
 * нарисованная один раз полоска. Значит, кто-то должен обновлять уведомление
 * каждую секунду, пока человек смотрит на другое приложение. Обычное ongoing-
 * уведомление, выставленное из ViewModel, это умеет ровно до тех пор, пока
 * система не убьёт процесс — а после убийства оно останется висеть навсегда с
 * последним значением, и снять его будет уже некому. Именно такой застывший
 * прогресс и был бы обманом. Foreground service решает обе половины: пока он
 * жив, процесс жив и цифры настоящие; когда его убивают — система снимает и
 * его уведомления вместе с ним.
 *
 * Тип — specialUse: у Android нет типа «кухонный таймер», а подменять его
 * dataSync или location было бы неправдой в манифесте. Запускается сервис
 * только из явного действия человека («Начать выпечку»), поэтому ограничения
 * Android 12+ на старт из фона его не касаются.
 *
 * Своих часов сервис не заводит: единственный источник времени — таймер в
 * BakingViewModel, а сюда приходит уже готовый слепок ([ActiveBakes]). Два
 * независимых отсчёта неминуемо разошлись бы между экраном и шторкой.
 */
class BakingProgressService : LifecycleService() {

    private lateinit var notifier: MadreNotifier
    private lateinit var manager: NotificationManagerCompat

    /** Что сейчас показано — чтобы снимать ровно то, что уже не идёт. */
    private var shownSessionIds: Set<Long> = emptySet()

    /** Чьё уведомление сейчас держит сервис на переднем плане. */
    private var foregroundSessionId: Long? = null

    override fun onCreate() {
        super.onCreate()
        notifier = MadreNotifier(this)
        manager = NotificationManagerCompat.from(this)
        ensureChannel()

        lifecycleScope.launch {
            (application as MadreApplication).activeBakes.progress.collectLatest { bakes ->
                if (bakes.isEmpty()) {
                    // Последняя выпечка закрылась — снимаем всё своё и уходим.
                    // Ждать, пока система вспомнит про пустой сервис, нельзя:
                    // в шторке осталась бы строка про то, чего уже нет.
                    //
                    // Сначала всё же выходим на передний план, если ещё не
                    // успели: lifecycleScope запускается на Main.immediate и
                    // может добраться сюда раньше onStartCommand, а система
                    // ждёт startForeground от каждого поднятого сервиса.
                    if (foregroundSessionId == null) {
                        startForegroundFor(PLACEHOLDER_SESSION_ID, buildStartingNotification())
                    }
                    clearAll()
                    stopForegroundCompat()
                    stopSelf()
                } else {
                    render(bakes)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Пока сборка слепков не дошла, сервис обязан выставить уведомление
        // сразу — иначе система убьёт его за молчание в первые секунды.
        if (foregroundSessionId == null) {
            val waiting = (application as MadreApplication).activeBakes.progress.value.firstOrNull()
            startForegroundFor(
                sessionId = waiting?.sessionId ?: PLACEHOLDER_SESSION_ID,
                notification = waiting?.let(::buildNotification) ?: buildStartingNotification(),
            )
        }
        // Восстанавливать себя после убийства процесса нечего: сессии выпечки
        // живут в памяти и умирают вместе с ним. Врать про «продолжаем» нельзя.
        return START_NOT_STICKY
    }

    private fun render(bakes: List<BakingProgress>) {
        // Переднеплановое уведомление — у самой ранней выпечки: она стабильна,
        // пока идёт, и не прыгает от того, что где-то тикнул счётчик.
        val leader = bakes.minBy { it.sessionId }
        if (foregroundSessionId != leader.sessionId) {
            startForegroundFor(leader.sessionId, buildNotification(leader))
        }
        // Переднеплановое уведомление обновляется тем же способом, что и
        // остальные: startForeground закрепил за ним id, дальше это обычный
        // notify по тому же id.
        bakes.forEach { bake ->
            notifier.notifySafely(BakingProgress.notificationId(bake.sessionId), buildNotification(bake))
        }
        // Выпечки, которых больше нет в списке, — закрыты или брошены.
        (shownSessionIds - bakes.map { it.sessionId }.toSet()).forEach { gone ->
            manager.cancel(BakingProgress.notificationId(gone))
        }
        shownSessionIds = bakes.map { it.sessionId }.toSet()
    }

    /**
     * Cycle 14: что писать — решает [BakingNotificationContent], здесь только
     * раскладка по билдеру.
     *
     * Обратный отсчёт отдан системному хронометру: setWhen на момент конца шага
     * плюс setUsesChronometer/setChronometerCountDown. Так цифры идут сами,
     * между обновлениями и в свёрнутой карточке, и не могут разойтись с
     * экраном — источник времени один и тот же слепок.
     *
     * Раскрыта карточка или свёрнута, решает система: программного способа
     * держать её раскрытой у Android нет, и книга такого не обещает. Поэтому
     * свёрнутая строка осмысленна сама по себе, а полный текст ждёт в BigText.
     *
     * Cycle 19: у развёрнутой карточки появился свой вид — [bigCard]. Свёрнутая
     * осталась системной намеренно: собственный вид в свёрнутом виде отнял бы у
     * неё системный хронометр, единственные цифры, которые человек видит, не
     * разворачивая шторку. BigTextStyle при этом остаётся на месте — на
     * прошивке, которая своей карточки не покажет, читается он.
     */
    private fun buildNotification(bake: BakingProgress): Notification {
        val content = BakingNotificationContent.from(bake, System.currentTimeMillis())
        return baseBuilder()
            .setContentTitle(content.title)
            .setContentText(content.compact)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.bigText))
            .setCustomBigContentView(bigCard(content, bake.remainingSeconds))
            .setProgress(BakingProgress.PROGRESS_MAX, content.progressPermille, false)
            .setShowWhen(content.usesChronometer)
            .setWhen(content.chronometerFinishAtMillis)
            .setUsesChronometer(content.usesChronometer)
            .setChronometerCountDown(content.usesChronometer)
            .setContentIntent(openBakingIntent(bake.sessionId))
            .build()
    }

    /**
     * Cycle 19: развёрнутая карточка — страница книги, а не служебная строка.
     *
     * Ничего здесь не считается: все строки приходят готовыми из
     * [BakingNotificationContent], где их проверяет юнит-тест. Раскладку
     * RemoteViews проверить нечем, поэтому логике в ней делать нечего.
     *
     * Отсчёт — Chronometer, а не текст: текст замер бы между обновлениями, а
     * обновления идут раз в секунду только пока жив процесс. База считается от
     * [android.os.SystemClock.elapsedRealtime] — тех же монотонных часов, по
     * которым идёт таймер на странице (стенные прыгают при синхронизации).
     * На паузе и на нуле хронометр уступает место обычному тексту: остановленный
     * Chronometer показал бы последнее значение, и отличить стоящие цифры от
     * идущих было бы нечем.
     */
    private fun bigCard(content: BakingNotificationContent, remainingSeconds: Long): RemoteViews =
        RemoteViews(packageName, R.layout.notification_baking_progress).apply {
            setTextViewText(R.id.notif_header, content.headerLine)
            setTextViewText(R.id.notif_step, content.stepLine)
            setTextViewText(R.id.notif_next, content.nextLine)
            setProgressBar(R.id.notif_progress, BakingProgress.PROGRESS_MAX, content.progressPermille, false)

            if (content.badge == null) {
                setViewVisibility(R.id.notif_badge, View.GONE)
            } else {
                setViewVisibility(R.id.notif_badge, View.VISIBLE)
                setTextViewText(R.id.notif_badge, content.badge)
                // «Пауза» — не тревога: краснеет только «скоро».
                setTextColor(
                    R.id.notif_badge,
                    color(if (content.isUrgent) R.color.madre_notif_urgent else R.color.madre_notif_cocoa),
                )
            }

            val ink = color(if (content.isUrgent) R.color.madre_notif_urgent else R.color.madre_notif_ink)
            if (content.usesChronometer) {
                setViewVisibility(R.id.notif_timer, View.VISIBLE)
                setViewVisibility(R.id.notif_timer_static, View.GONE)
                setChronometer(
                    R.id.notif_timer,
                    SystemClock.elapsedRealtime() + remainingSeconds * 1000L,
                    null,
                    true,
                )
                setChronometerCountDown(R.id.notif_timer, true)
                setTextColor(R.id.notif_timer, ink)
                setContentDescription(R.id.notif_timer, content.spokenTimer)
            } else {
                setViewVisibility(R.id.notif_timer, View.GONE)
                setViewVisibility(R.id.notif_timer_static, View.VISIBLE)
                setTextViewText(R.id.notif_timer_static, content.timerText)
                setTextColor(R.id.notif_timer_static, ink)
                setContentDescription(R.id.notif_timer_static, content.spokenTimer)
            }
        }

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)

    /** Первые доли секунды, пока не пришёл первый слепок. */
    private fun buildStartingNotification(): Notification =
        baseBuilder()
            .setContentTitle("Выпечка началась")
            .setContentText("книга ведёт таймер")
            .setProgress(BakingProgress.PROGRESS_MAX, 0, true)
            .setContentIntent(openBakingIntent(null))
            .build()

    private fun baseBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_bread)
            // Не смахивается и не уходит по тапу: пока печётся — висит.
            .setOngoing(true)
            .setAutoCancel(false)
            // Ни звука, ни вибрации, ни всплытия: строка обновляется каждую
            // секунду, и дёргать человека на каждое обновление недопустимо.
            // Про важное — конец шага и масло — говорят отдельные уведомления
            // на своём канале, у них на это есть право.
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setShowWhen(false)

    /**
     * Тап открывает ИМЕННО эту выпечку. Свой requestCode на каждую — иначе
     * PendingIntent'ы разных выпечек считались бы одним и тем же, и вторая
     * строка в шторке вела бы на первую.
     */
    private fun openBakingIntent(sessionId: Long?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (sessionId != null) intent.putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
        return PendingIntent.getActivity(
            this,
            BakingNotificationContent.intentRequestCode(sessionId ?: PLACEHOLDER_SESSION_ID),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun startForegroundFor(sessionId: Long, notification: Notification) {
        val id = BakingProgress.notificationId(sessionId)
        val previous = foregroundSessionId
        if (!enterForeground(id, notification)) return
        foregroundSessionId = sessionId
        shownSessionIds = shownSessionIds + sessionId
        // Прежнее переднеплановое уведомление после смены остаётся обычным —
        // если его выпечки уже нет, снимаем сами.
        if (previous != null && previous != sessionId && previous !in shownSessionIds) {
            manager.cancel(BakingProgress.notificationId(previous))
        }
    }

    /**
     * Выйти на передний план подходящим для этой версии способом. Тип
     * specialUse существует только с Android 14 (API 34) — на 29..33 его
     * передача не проходит проверку «тип входит в объявленный в манифесте» и
     * оставляет сервис без переднего плана, а система убивает его за молчание.
     * Поэтому тип идёт только с 34, ниже — обычный двухаргументный overload.
     *
     * Возвращает false лишь в одном честном случае — когда система вовсе не даёт
     * стартовать из фона (Android 12+). Любой другой отказ означает, что сервис
     * остался без переднего плана по нашей ошибке, и глотать это нельзя.
     */
    private fun enterForeground(id: Int, notification: Notification): Boolean =
        try {
            // Проверка версии здесь встроена намеренно: и трёхаргументный
            // startForeground (с API 29), и сам тип требуют явного SDK_INT рядом,
            // иначе lint не увидит защиты. Граница «с какой версии тип» одна и та
            // же, что и в [specialUseForegroundType], — там она под тестом.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(id, notification)
            }
            true
        } catch (error: Exception) {
            if (isBackgroundStartRestriction(error)) false else throw error
        }

    private fun isBackgroundStartRestriction(error: Throwable): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            error is android.app.ForegroundServiceStartNotAllowedException

    private fun clearAll() {
        shownSessionIds.forEach { manager.cancel(BakingProgress.notificationId(it)) }
        foregroundSessionId?.let { manager.cancel(BakingProgress.notificationId(it)) }
        shownSessionIds = emptySet()
        foregroundSessionId = null
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        clearAll()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val service = getSystemService(NotificationManager::class.java) ?: return
        if (service.getNotificationChannel(CHANNEL_ID) != null) return
        // IMPORTANCE_LOW: строка видна в шторке, но не всплывает и не звучит.
        service.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ход выпечки", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Название, этап, остаток и полоска хода — пока идёт выпечка."
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    companion object {
        const val CHANNEL_ID = "madre_baking_progress"

        /** id для мгновения между стартом сервиса и первым слепком. */
        private const val PLACEHOLDER_SESSION_ID = 0L

        /**
         * Тип переднепланового сервиса для этой версии, либо null, если тип
         * передавать нельзя. specialUse валиден только с Android 14 (API 34);
         * ниже — обычный startForeground без типа. Вынесено отдельно, чтобы
         * границу «с какой версии тип» можно было проверить без самого сервиса.
         */
        internal fun specialUseForegroundType(sdkInt: Int): Int? =
            if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                null
            }

        /**
         * Поднять сервис. Зовётся из BakingViewModel в момент, когда человек
         * нажал «Начать выпечку», — то есть всегда с переднего плана.
         */
        fun start(context: Context) {
            val intent = Intent(context, BakingProgressService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, BakingProgressService::class.java)) }
        }
    }
}
