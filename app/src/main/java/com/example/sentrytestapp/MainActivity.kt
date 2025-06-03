package com.example.sentrytestapp

import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.lifecycleScope
import com.example.sentrytestapp.ui.theme.SentryTestAppTheme
import io.sentry.ISpan
import io.sentry.Sentry
import io.sentry.SpanStatus
import io.sentry.protocol.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // waiting for view to draw to better represent a captured error with a screenshot
//        findViewById<android.view.View>(android.R.id.content).viewTreeObserver.addOnGlobalLayoutListener {
//          try {
//            throw Exception("This app uses Sentry! :)")
//          } catch (e: Exception) {
//            Sentry.captureException(e)
//          }
//        }
//
//        enableEdgeToEdge()
//        setContent {
//            SentryTestAppTheme {
//                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
//                    Greeting(
//                        name = "Android",
//                        modifier = Modifier.padding(innerPadding)
//                    )
//                }
//            }
//        }



        val crashBtn = findViewById<Button>(R.id.btnCrash)
        val errorBtn = findViewById<Button>(R.id.btnHandledError)
        val slowBtn = findViewById<Button>(R.id.btnSlowFunction)
        val profilingBtn = findViewById<Button>(R.id.btnTransaction)

        // Crash Button
        crashBtn.setOnClickListener {
            throw RuntimeException("Test crash from button click ")
        }

        // Handled Error Button
        errorBtn.setOnClickListener {
//            val transaction = Sentry.startTransaction("handled_error_test", "task")
//
//            Sentry.configureScope { scope ->
//                scope.transaction = transaction
//            }

            try {
                val a = 5 / 0
            } catch (e: Exception) {
                Sentry.captureException(e)
            }
//            transaction.finish()
        }

        profilingBtn.setOnClickListener {
            val transaction = Sentry.startTransaction("profiling_button_click", "task")

            try {
                simulateWork(transaction)
            } catch (e: Exception) {
                transaction.throwable = e
                transaction.status = SpanStatus.INTERNAL_ERROR
                Sentry.captureException(e)
            } finally {
                transaction.finish()
            }
        }


        // Slow Function Button
        slowBtn.setOnClickListener {
            Thread.sleep(8000)
//            val transaction = Sentry.startTransaction("UI Interaction", "slow-operation")
//            try {
//                Sentry.setTag("operation", "slowTask")
//                // Block the main thread
//                Thread.sleep(4000)
//                Log.d("SentryTestApp", "Slow function completed")
//                transaction.finish(Sentry.PerformanceLevel.INFO)
//            } catch (e: Exception) {
//                transaction.throwable = e
//                transaction.status = io.sentry.SpanStatus.INTERNAL_ERROR
//                transaction.finish(Sentry.PerformanceLevel.ERROR)
//                throw e
//            }
        }

        // Set user info
        val user = User().apply {
            username = "test_user"
            email = "test@example.com"
            id = "12345"
        }
        Sentry.setUser(user)

        // Add custom breadcrumb
        Sentry.addBreadcrumb("MainActivity initialized")
    }

    private fun simulateWork(span: ISpan?) {
        val innerSpan = span?.startChild("task", "division_operation")

        try {
            // This will cause ArithmeticException
            val a = 5 / 0
        } catch (e: ArithmeticException) {
            innerSpan?.throwable = e
            innerSpan?.status = SpanStatus.INTERNAL_ERROR
            throw e
        } finally {
            innerSpan?.finish()
        }
    }
}




@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    SentryTestAppTheme {
        Greeting("Android")
    }
}