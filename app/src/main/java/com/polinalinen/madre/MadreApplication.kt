package com.polinalinen.madre

import android.app.Application
import com.polinalinen.madre.data.db.MadreDatabase
import com.polinalinen.madre.data.repository.ActiveBakeRepository
import com.polinalinen.madre.data.repository.BakeHistoryRepository
import com.polinalinen.madre.data.repository.FamilySettingsRepository
import com.polinalinen.madre.data.repository.RecipeRepository
import com.polinalinen.madre.data.repository.SourdoughRepository
import com.polinalinen.madre.account.FamilyAccountRepository
import com.polinalinen.madre.account.KeystoreTokenCipher
import com.polinalinen.madre.account.SecureTokenStore
import com.polinalinen.madre.data.remote.FamilyBookApiFactory
import com.polinalinen.madre.data.remote.MadreApi
import com.polinalinen.madre.data.remote.MadreApiFactory
import com.polinalinen.madre.notifications.ActiveBakes
import com.polinalinen.madre.sync.SyncRepository
import com.polinalinen.madre.utils.LegacyPrefs

/**
 * Единая точка создания Room/repository — синглтоны живут в Application,
 * НЕ в ViewModel. Закрывает баг v3 #1 (db.close() в onCleared() → crash
 * при повторном входе на экран).
 */
class MadreApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Cycle 12: «слипшихся страниц» в книге больше нет — их ключи в
        // madre_prefs убираются один раз, тихо и без миграции Room.
        LegacyPrefs.purge(getSharedPreferences("madre_prefs", MODE_PRIVATE))
    }

    /**
     * Cycle 12: что сейчас в печи. Живёт в Application, потому что читателей
     * двое и друг друга они не видят — BakingViewModel пишет сюда на каждом
     * тике таймера, BakingProgressService читает и рисует шторку.
     */
    val activeBakes: ActiveBakes by lazy { ActiveBakes() }

    val database: MadreDatabase by lazy { MadreDatabase.build(this) }
    val recipeRepository: RecipeRepository by lazy { RecipeRepository(this) }
    val sourdoughRepository: SourdoughRepository by lazy { SourdoughRepository(database) }
    val bakeHistoryRepository: BakeHistoryRepository by lazy { BakeHistoryRepository(database) }
    // Cycle 15: незавершённые выпечки — их читает BakeRestoreWorker после
    // перезагрузки телефона, поэтому репозиторий нужен и без единого экрана.
    val activeBakeRepository: ActiveBakeRepository by lazy { ActiveBakeRepository(database) }
    val familySettingsRepository: FamilySettingsRepository by lazy { FamilySettingsRepository(database) }
    // Cycle 5: общая книга на PocketBase — клиент и очередь фоновой отправки.
    val madreApi: MadreApi by lazy { MadreApiFactory.create() }
    /** Optional account wiring; local Room data never depends on this client. */
    val familyAccountRepository: FamilyAccountRepository by lazy {
        FamilyAccountRepository(
            api = FamilyBookApiFactory.create(),
            tokens = SecureTokenStore(this, KeystoreTokenCipher()),
        )
    }
    val syncRepository: SyncRepository by lazy { SyncRepository(this) }
}
