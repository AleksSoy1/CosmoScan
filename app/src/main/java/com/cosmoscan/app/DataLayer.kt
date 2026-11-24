package com.cosmoscan.app

import android.content.Context
import androidx.room.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

data class ResearchArticle(
    val title: String,
    val authority: String,
    val year: String,
    val summary: String,
    val url: String,
    val isUserAdded: Boolean = false
)

class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromArticleList(list: List<ResearchArticle>): String = gson.toJson(list)

    @TypeConverter
    fun toArticleList(json: String): List<ResearchArticle> {
        if (json.isBlank()) return emptyList()
        val type = object : TypeToken<List<ResearchArticle>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }
}

@Entity(tableName = "ingredients")
data class IngredientEntity(
    @PrimaryKey val inciName: String,
    val ruName: String,
    val category: String,
    val safety: String, // "SAFE", "CAUTION", "HARMFUL", "UNKNOWN"
    val isActive: Boolean,
    val isThresholdMarker: Boolean,
    val properties: String,
    val source: String = "LOCAL_DB",
    val articles: List<ResearchArticle> = emptyList()
)

@Dao
interface IngredientDao {
    @Query("SELECT * FROM ingredients WHERE inciName = :name LIMIT 1")
    suspend fun findByInci(name: String): IngredientEntity?

    @Query("SELECT COUNT(*) FROM ingredients")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<IngredientEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: IngredientEntity)
}

@Database(entities = [IngredientEntity::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ingredientDao(): IngredientDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cosmoscan_ingredients.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

data class ObfIngredientResponse(
    val id: String?,
    val name: Map<String, String>?
)

data class WikiSummaryResponse(
    val title: String?,
    val extract: String?
)

data class EuropePmcResponse(
    val resultList: EuropePmcResultList?
)

data class EuropePmcResultList(
    val result: List<EuropePmcArticle>?
)

data class EuropePmcArticle(
    val pmid: String?,
    val doi: String?,
    val title: String?,
    val journalTitle: String?,
    val pubYear: String?,
    val authorString: String?
)

interface CosmeticCatalogApi {
    @GET("https://world.openbeautyfacts.org/api/v2/taxonomy/ingredients/{slug}.json")
    suspend fun getIngredientInfo(@Path("slug") slug: String): ObfIngredientResponse

    @GET("https://ru.wikipedia.org/api/rest_v1/page/summary/{title}")
    suspend fun getRuWikiDescription(@Path("title") title: String): WikiSummaryResponse

    @GET("https://www.ebi.ac.uk/europepmc/webservices/rest/search")
    suspend fun searchScientificArticles(
        @Query("query") query: String,
        @Query("format") format: String = "json",
        @Query("pageSize") pageSize: Int = 2,
        @Query("sort") sort: String = "CITED desc"
    ): EuropePmcResponse
}

class IngredientRepository(private val context: Context) {
    private val dao = AppDatabase.getDatabase(context).ingredientDao()

    private val api: CosmeticCatalogApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://world.openbeautyfacts.org/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CosmeticCatalogApi::class.java)
    }

    suspend fun ensureDatabasePrepopulated() = withContext(Dispatchers.IO) {
        if (dao.count() == 0) {
            val json = context.assets.open("ingredients_db.json")
                .bufferedReader().use { it.readText() }
            val type = object : TypeToken<List<IngredientEntity>>() {}.type
            val list: List<IngredientEntity> = Gson().fromJson(json, type)
            dao.insertAll(list.map { it.copy(inciName = it.inciName.lowercase().trim()) })
        }
    }

    suspend fun getOrFetchIngredient(rawName: String): IngredientEntity = withContext(Dispatchers.IO) {
        val normalized = rawName.lowercase().trim()
        dao.findByInci(normalized)?.let { return@withContext it }

        val fetched = fetchFromExternalApis(normalized, rawName)
        dao.insert(fetched)
        return@withContext fetched
    }

    suspend fun attachStudyToIngredient(
        inciName: String,
        article: ResearchArticle
    ): IngredientEntity? = withContext(Dispatchers.IO) {
        val existing = dao.findByInci(inciName.lowercase().trim()) ?: return@withContext null
        val updated = existing.copy(articles = existing.articles + article)
        dao.insert(updated)
        return@withContext updated
    }

    private suspend fun fetchScientificEvidence(inciName: String): List<ResearchArticle> {
        return try {
            val query = "TITLE_ABS:\"$inciName\" AND (dermatology OR skin OR cosmetic OR safety OR toxicity)"
            val response = api.searchScientificArticles(query = query)
            response.resultList?.result?.mapNotNull { item ->
                val title = item.title?.trim()?.removeSuffix(".") ?: return@mapNotNull null
                val link = when {
                    !item.pmid.isNullOrBlank() -> "https://europepmc.org/article/MED/${item.pmid}"
                    !item.doi.isNullOrBlank() -> "https://doi.org/${item.doi}"
                    else -> return@mapNotNull null
                }
                val journal = item.journalTitle ?: "PubMed / Europe PMC"
                val author = item.authorString?.split(",")?.firstOrNull()?.trim() ?: "Группа исследователей"
                ResearchArticle(
                    title = title,
                    authority = "$journal ($author)",
                    year = item.pubYear ?: "н/д",
                    summary = "Рецензируемая публикация по дерматологическому профилю и безопасности компонента.",
                    url = link,
                    isUserAdded = false
                )
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchFromExternalApis(normalized: String, originalName: String): IngredientEntity {
        val studies = fetchScientificEvidence(normalized)
        val slug = "en:" + normalized.replace(" ", "-")

        try {
            val obf = api.getIngredientInfo(slug)
            val ruTitle = obf.name?.get("ru") ?: originalName
            val description = try {
                api.getRuWikiDescription(ruTitle).extract
            } catch (_: Exception) {
                null
            }

            if (!description.isNullOrBlank()) {
                return IngredientEntity(
                    inciName = normalized,
                    ruName = ruTitle,
                    category = "Загружено из API-справочника",
                    safety = classifySafetyHeuristic(normalized, description),
                    isActive = description.contains("витамин", true) ||
                            description.contains("экстракт", true) ||
                            description.contains("масло", true) ||
                            description.contains("кислота", true),
                    isThresholdMarker = normalized.contains("paraben") || normalized.contains("parfum"),
                    properties = description.take(220) + if (description.length > 220) "..." else "",
                    source = "API_CATALOG",
                    articles = studies
                )
            }
        } catch (_: Exception) { }

        try {
            val wiki = api.getRuWikiDescription(originalName.trim())
            if (!wiki.extract.isNullOrBlank()) {
                return IngredientEntity(
                    inciName = normalized,
                    ruName = wiki.title ?: originalName,
                    category = "Справочник (API)",
                    safety = classifySafetyHeuristic(normalized, wiki.extract),
                    isActive = wiki.extract.contains("экстракт", true) || wiki.extract.contains("масло", true),
                    isThresholdMarker = false,
                    properties = wiki.extract.take(220) + "...",
                    source = "API_WIKI",
                    articles = studies
                )
            }
        } catch (_: Exception) { }

        return IngredientEntity(
            inciName = normalized,
            ruName = originalName,
            category = if (studies.isNotEmpty()) "Найдены научные статьи (API)" else "Не найдено в каталогах",
            safety = "UNKNOWN",
            isActive = false,
            isThresholdMarker = false,
            properties = "Описание отсутствует в базовом каталоге. Вы можете изучить прикреплённые статьи или добавить свои.",
            source = if (studies.isNotEmpty()) "EUROPE_PMC_API" else "NOT_FOUND",
            articles = studies
        )
    }

    private fun classifySafetyHeuristic(inci: String, desc: String): String {
        val harmfulKeywords = listOf("formaldehyde", "triclosan", "paraben", "формальдегид", "токсич")
        val cautionKeywords = listOf("sulfate", "silicone", "peg-", "аллерген", "раздражен")
        return when {
            harmfulKeywords.any { inci.contains(it) || desc.contains(it, true) } -> "HARMFUL"
            cautionKeywords.any { inci.contains(it) || desc.contains(it, true) } -> "CAUTION"
            else -> "SAFE"
        }
    }
}
