package dev.hike.dataassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HikeApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HikeApp() {
    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("徒步数据助手") }) }
        ) { padding ->
            Text(
                text = "环境验证骨架 v0.1",
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            )
        }
    }
}
