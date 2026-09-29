package dev.hike.dataassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import dev.hike.dataassistant.ui.HikeApp
import dev.hike.dataassistant.ui.HikeViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewModel by viewModels<HikeViewModel>()
        setContent {
            MaterialTheme {
                HikeApp(viewModel)
            }
        }
    }
}
