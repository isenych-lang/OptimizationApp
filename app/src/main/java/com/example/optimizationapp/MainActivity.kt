package com.example.optimizationapp

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// ================= 1. DOMAIN & DATA LAYER (SSOT Pattern) =================
data class Product(val id: Int, val name: String, val category: String, val price: Double)

@Entity(tableName = "ssot_products")
data class ProductEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val category: String,
    val price: Double
)

@Dao
interface ProductDao {
    @Query("SELECT * FROM ssot_products")
    fun getAllProductsFlow(): Flow<List<ProductEntity>> // UI подписывается на этот поток

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(products: List<ProductEntity>)
}

@Database(entities = [ProductEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    companion object {
        private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "ssot_db").build().also { INSTANCE = it }
            }
        }
    }
}

// Фейковые сетевые сервисы для имитации параллельных запросов
class ApiServicePart1 {
    suspend fun fetchProducts(): List<Product> = listOf(
        Product(1, "Смартфон", "Электроника", 800.0),
        Product(2, "Наушники", "Аксессуары", 150.0)
    )
}

class ApiServicePart2 {
    suspend fun fetchProducts(): List<Product> = listOf(
        Product(3, "Чехол", "Аксессуары", 20.0),
        Product(4, "Зарядное устройство", "Электроника", 40.0)
    )
}

class ProductRepository(private val dao: ProductDao) {
    private val api1 = ApiServicePart1()
    private val api2 = ApiServicePart2()

    // UI получает данные ТОЛЬКО из базы данных (Single Source of Truth)
    fun getProductsFromDb(): Flow<List<Product>> {
        return dao.getAllProductsFlow().map { list ->
            list.map { Product(it.id, it.name, it.category, it.price) }
        }
    }

    // Параллельное выполнение запросов через async/await в фоновом потоке Dispatchers.IO
    suspend fun refreshData() {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val deferred1 = async { api1.fetchProducts() }
                val deferred2 = async { api2.fetchProducts() }

                // Объединяем результаты параллельных запросов
                val combinedList = deferred1.await() + deferred2.await()

                // Обновляем кэш — UI обновится автоматически
                dao.insertAll(combinedList.map { ProductEntity(it.id, it.name, it.category, it.price) })
            } catch (e: Exception) {
                // Логирование ошибок
            }
        }
    }
}

// ================= 2. PRESENTATION & UI LAYER =================
class ProductViewModel(context: Context) : ViewModel() {
    private val db = AppDatabase.getInstance(context)
    private val repository = ProductRepository(db.productDao())

    // UI-State оптимизирован через stateIn для предотвращения лишних перерисовок
    val productsState: StateFlow<List<Product>> = repository.getProductsFromDb()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch { repository.refreshData() }
    }
}

@Composable
fun ProductListScreen(products: List<Product>) {
    LazyColumn(Modifier.padding(16.dp)) {
        // Использование стабильного ключевого поля (key) оптимизирует работу Layout Inspector (снижает Recomposition Count)
        items(products, key = { it.id }) { product ->
            Card(Modifier.fillMaxWidth().padding(8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(product.name, style = MaterialTheme.typography.titleMedium)
                    Text("${product.category} • ${product.price}₴")
                }
            }
        }
    }
}

// ================= 3. ENTRY POINT =================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val context = androidx.compose.ui.platform.LocalContext.current
                val viewModel = remember { ProductViewModel(context) }
                val products by viewModel.productsState.collectAsState()
                ProductListScreen(products)
            }
        }
    }
}
