# Cycle 15: release собирается с R8 (isMinifyEnabled). Всё, до чего код
# дотягивается рефлексией, а не вызовом, R8 не видит и имеет право выбросить
# или переименовать — причём молча, на этапе сборки ничего не падает.
# Ломается это уже на телефоне, поэтому правила ниже — по одному на каждый
# такой путь.

# --- Room -------------------------------------------------------------------
# Room генерирует реализацию по именам, а не ищет её рефлексией, так что это
# страховка: имена таблиц и колонок берутся из сущностей.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep class com.polinalinen.madre.data.db.entities.** { *; }

# --- Gson -------------------------------------------------------------------
# Здесь рефлексия настоящая: имена полей моделей — это ключи JSON.
# recipes.json разбирается в model.RecipeDatabase, обмен с PocketBase —
# в data.remote.*, токены семьи — в account.*.
-keep class com.polinalinen.madre.model.** { *; }
-keep class com.polinalinen.madre.data.remote.** { *; }
-keep class com.polinalinen.madre.account.** { *; }
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

# --- Атрибуты ---------------------------------------------------------------
# Signature нужен Gson и Retrofit: без него дженерики стираются и
# List<Recipe> разбирается в List<LinkedTreeMap>.
-keepattributes Signature, *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
