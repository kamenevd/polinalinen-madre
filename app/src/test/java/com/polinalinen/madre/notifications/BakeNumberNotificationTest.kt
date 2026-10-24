package com.polinalinen.madre.notifications

import org.junit.Assert.*
import org.junit.Test

class BakeNumberNotificationTest {
    @Test fun actualNumberNotSessionIdInTitleAndPrivatePublicText() {
        val progress=BakingProgress(872,"Чиабатта",stepTitle="Отдых",stepIndex=4,stepCount=11,remainingSeconds=1100,stepTotalSeconds=1800,isPaused=false,bakeNumber=2)
        assertEquals("№2 Чиабатта · 18:20",BakingNotificationContent.from(progress).title)
        assertEquals("Выпечка №1 и №2 идут",publicBakeNumbers(listOf(2,1)))
        assertEquals("Выпечка №3 идёт",publicBakeNumbers(listOf(3)))
        assertFalse(publicBakeNumbers(listOf(2,1)).contains("Чиабатта"))
        assertFalse(BakingNotificationContent.from(progress).title.contains("872"))
    }
}
