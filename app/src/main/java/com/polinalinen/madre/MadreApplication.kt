package com.polinalinen.madre

import android.app.Application
import com.polinalinen.madre.data.db.MadreDatabase
import com.polinalinen.madre.data.repository.BakeHistoryRepository
import com.polinalinen.madre.data.repository.RecipeRepository
import com.polinalinen.madre.data.repository.SourdoughRepository

/**
 * Единая точка создания Room/repository — синглтоны живут в Application,
 * НЕ в ViewModel. Закрывает баг v3 #1 (db.close() в onCleared() → crash
 * при повторном входе на экран).
 */
class MadreApplication : Application() {

    val database: MadreDatabase by lazy { MadreDatabase.build(this) }
    val recipeRepository: RecipeRepository by lazy { RecipeRepository(this) }
    val sourdoughRepository: SourdoughRepository by lazy { SourdoughRepository(database) }
    val bakeHistoryRepository: BakeHistoryRepository by lazy { BakeHistoryRepository(database) }
}
