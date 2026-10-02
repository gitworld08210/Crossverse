package com.linkup.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.linkup.core.Linkup

/**
 * Minimal launcher activity for the Phase 1 scaffold. Real pairing / sync UI
 * arrives in later features. Depends on :core only for shared metadata here.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LinkupHome()
                }
            }
        }
    }
}

@Composable
fun LinkupHome() {
    Text(
        text = "Linkup ${Linkup.VERSION}",
        modifier = Modifier
            .fillMaxSize()
            .wrapContentSize(Alignment.Center)
            .padding(16.dp)
    )
}

@Preview(showBackground = true)
@Composable
private fun LinkupHomePreview() {
    MaterialTheme {
        LinkupHome()
    }
}
