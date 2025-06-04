package com.example.sentrytestapp

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.example.sentrytestapp.BuildConfig
import io.sentry.ISpan
import io.sentry.ITransaction
import io.sentry.Sentry
import io.sentry.SpanStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

import io.sentry.okhttp.SentryOkHttpEventListener
import io.sentry.okhttp.SentryOkHttpInterceptor
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request

class WeatherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_weather)

        val weatherBtn = findViewById<Button>(R.id.btnFetchWeather)
        val weatherBtn2 = findViewById<Button>(R.id.btnFetchWeather2)
        val resultView = findViewById<TextView>(R.id.txtWeatherResult)

        weatherBtn2.setOnClickListener {
            val transaction = Sentry.startTransaction("weather_bundle", "task")

            lifecycleScope.launch {
                try {
                    val result = fetchWeatherBundle(transaction)
                    resultView.text = result
                    println(result)
                } catch (e: Exception) {
                    Sentry.captureException(e)
                } finally {
                    transaction.finish()
                }
            }

        }

        weatherBtn.setOnClickListener {
            val transaction = Sentry.startTransaction("fetch_weather", "http.request")
            Sentry.configureScope { scope ->
                scope.transaction = transaction
            }

            lifecycleScope.launch {
                try {
                    val weatherInfo = fetchWeatherData(transaction)
                    resultView.text = weatherInfo
                    transaction.setStatus(SpanStatus.OK)
                } catch (e: Exception) {
                    transaction.setThrowable(e)
                    transaction.setStatus(SpanStatus.INTERNAL_ERROR)
                    Sentry.captureException(e)
                    resultView.text = "Failed to fetch weather"
                } finally {
                    transaction.finish()
                }
            }
        }
    }

    private suspend fun fetchWeatherData(transaction: ITransaction): String = withContext(Dispatchers.IO) {
        val span: ISpan = transaction.startChild("http.client", "GET /current.json")

        val client = OkHttpClient.Builder()
            .addInterceptor(SentryOkHttpInterceptor()) // Enables Sentry instrumentation
            .build()

        val apiKey = BuildConfig.WEATHER_API_KEY
        val request = Request.Builder()
            .url("https://api.weatherapi.com/v1/current.json?key=$apiKey&q=London")
            .get()
            .build()

        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            span.setStatus(if (response.isSuccessful) SpanStatus.OK else SpanStatus.INTERNAL_ERROR)
            return@withContext body
        } catch (e: Exception) {
            span.setThrowable(e)
            span.setStatus(SpanStatus.INTERNAL_ERROR)
            throw e
        } finally {
            span.finish()
        }
    }

    private suspend fun fetchWeatherBundle(transaction: ITransaction): String = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .addInterceptor(SentryOkHttpInterceptor())
            .build()

        val apiKey = BuildConfig.WEATHER_API_KEY

        fun makeRequest(path: String, spanOp: String): String {
            val span = transaction.startChild("http.client", spanOp)
            val request = Request.Builder()
                .url("https://api.weatherapi.com/v1/$path&key=$apiKey")
                .build()

            return try {
                val response = client.newCall(request).execute()
                val body = response.body?.string().orEmpty()
                span.setStatus(if (response.isSuccessful) SpanStatus.OK else SpanStatus.INTERNAL_ERROR)
                body
            } catch (e: Exception) {
                span.setThrowable(e)
                span.setStatus(SpanStatus.INTERNAL_ERROR)
                throw e
            } finally {
                span.finish()
            }
        }

        val currentWeather = makeRequest("current.json?q=London", "GET /current.json")
        val forecast = makeRequest("forecast.json?q=London&days=3", "GET /forecast.json")
        val search = makeRequest("search.json?q=Lon", "GET /search.json")
        val fake = makeRequest("fake.json?q=London", "GET /fake.json")

        Log.d("WeatherActivity", "Current Weather: $currentWeather")
        Log.d("WeatherActivity", "Forecast: $forecast")
        Log.d("WeatherActivity", "Search: $search")
        Log.d("WeatherActivity", "Fake: $fake")

        return@withContext """
        Current Weather: $currentWeather
        
        Forecast: $forecast
        
        Search: $search
        
        Fake: $fake
    """.trimIndent()
    }

}
