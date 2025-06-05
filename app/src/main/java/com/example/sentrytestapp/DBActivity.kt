package com.example.sentrytestapp

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import io.sentry.ITransaction
import io.sentry.Sentry
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.sentry.SpanStatus


class DbActivity : ComponentActivity() {
    private lateinit var db: AppDatabase
    private lateinit var dao: ItemDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "test.db").build()
        dao = db.itemDao()

        // UI Components
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val inputField = EditText(this).apply {
            hint = "Enter item name or search term"
        }

        val insertBtn = Button(this).apply {
            text = "Insert Item"
        }

        val fetchBtn = Button(this).apply {
            text = "Fetch Items"
        }

        val resultText = TextView(this).apply {
            text = "Results will appear here"
        }

        val resultView = TextView(this)
        val deleteBtn = Button(this).apply { text = "Delete All" }
        val searchBtn = Button(this).apply { text = "Search Items" }
        val countBtn = Button(this).apply { text = "Count Items" }


        layout.apply {
            addView(inputField)
            addView(insertBtn)
            addView(fetchBtn)
            addView(resultText)
            layout.addView(searchBtn)
            layout.addView(countBtn)
            layout.addView(deleteBtn)
            layout.addView(resultView)
        }

        setContentView(layout)

        insertBtn.setOnClickListener {
            val itemName = inputField.text.toString().ifBlank { "Item ${System.currentTimeMillis()}" }
            val transaction = Sentry.startTransaction("insert_item", "task")
            Sentry.configureScope { it.transaction = transaction }

            lifecycleScope.launch {
                insertItem(itemName, transaction)
                transaction.finish()
                inputField.text.clear()
            }
        }

        fetchBtn.setOnClickListener {
            val transaction = Sentry.startTransaction("fetch_items", "task")
            Sentry.configureScope { it.transaction = transaction }

            lifecycleScope.launch {
                val items = fetchItems(transaction)
                val display = items.joinToString("\n") { it.name }
                resultText.text = display.ifBlank { "No items in DB" }
                transaction.finish()
            }
        }

        insertBtn.setOnClickListener {
            val name = inputField.text.toString().ifEmpty { "Item ${System.currentTimeMillis()}" }
            val transaction = Sentry.startTransaction("insert_item", "task")
            lifecycleScope.launch {
                insertItem(name, transaction)
                transaction.finish()
            }
        }

        fetchBtn.setOnClickListener {
            val transaction = Sentry.startTransaction("fetch_items", "task")
            lifecycleScope.launch {
                val items = fetchItems(transaction)
                resultView.text = items.joinToString("\n") { it.name }
                transaction.finish()
            }
        }

        searchBtn.setOnClickListener {
            val query = inputField.text.toString()
            val transaction = Sentry.startTransaction("search_items", "task")
            lifecycleScope.launch {
                val results = searchItems(query, transaction)
                resultView.text = results.joinToString("\n") { it.name }
                transaction.finish()
            }
        }

        deleteBtn.setOnClickListener {
            val transaction = Sentry.startTransaction("delete_all_items", "task")
            lifecycleScope.launch {
                deleteAllItems(transaction)
                resultView.text = "All items deleted."
                transaction.finish()
            }
        }

        countBtn.setOnClickListener {
            val transaction = Sentry.startTransaction("count_items", "task")
            lifecycleScope.launch {
                val count = countItems(transaction)
                resultView.text = "Total items: $count"
                transaction.finish()
            }
        }

    }

    private suspend fun insertItem(name: String, transaction: ITransaction) = withContext(Dispatchers.IO) {
        val span = transaction.startChild("db.sql.room", "INSERT INTO Item")
        try {
            dao.insert(Item(name = name))
            span.setStatus(SpanStatus.OK)
        } catch (e: Exception) {
            span.setThrowable(e)
            span.setStatus(SpanStatus.INTERNAL_ERROR)
            Sentry.captureException(e)
        } finally {
            span.finish()
        }
    }

    private suspend fun fetchItems(transaction: ITransaction): List<Item> = withContext(Dispatchers.IO) {
        val span = transaction.startChild("db.sql.room", "SELECT * FROM Item")
        try {
            val result = dao.getAll()
            span.setStatus(SpanStatus.OK)
            return@withContext result
        } catch (e: Exception) {
            span.setThrowable(e)
            span.setStatus(SpanStatus.INTERNAL_ERROR)
            Sentry.captureException(e)
            return@withContext emptyList()
        } finally {
            span.finish()
        }
    }

    private suspend fun deleteAllItems(transaction: ITransaction) = withContext(Dispatchers.IO) {
        val span = transaction.startChild("db.sql.room", "DELETE FROM Item")
        try {
            dao.deleteAll()
            span.setStatus(SpanStatus.OK)
        } catch (e: Exception) {
            span.setThrowable(e)
            span.setStatus(SpanStatus.INTERNAL_ERROR)
            Sentry.captureException(e)
        } finally {
            span.finish()
        }
    }

    private suspend fun searchItems(query: String, transaction: ITransaction): List<Item> = withContext(Dispatchers.IO) {
        val span = transaction.startChild("db.sql.room", "SELECT * FROM Item WHERE name LIKE ?")
        try {
            val result = dao.searchByName(query)
            span.setStatus(SpanStatus.OK)
            return@withContext result
        } catch (e: Exception) {
            span.setThrowable(e)
            span.setStatus(SpanStatus.INTERNAL_ERROR)
            Sentry.captureException(e)
            return@withContext emptyList()
        } finally {
            span.finish()
        }
    }

    private suspend fun countItems(transaction: ITransaction): Int = withContext(Dispatchers.IO) {
        val span = transaction.startChild("db.sql.room", "SELECT COUNT(*) FROM Item")
        try {
            val result = dao.count()
            span.setStatus(SpanStatus.OK)
            return@withContext result
        } catch (e: Exception) {
            span.setThrowable(e)
            span.setStatus(SpanStatus.INTERNAL_ERROR)
            Sentry.captureException(e)
            return@withContext 0
        } finally {
            span.finish()
        }
    }

}