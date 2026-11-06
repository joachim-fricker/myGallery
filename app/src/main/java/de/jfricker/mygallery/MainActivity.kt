package de.jfricker.mygallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import de.jfricker.mygallery.ui.GalleryApp
import de.jfricker.mygallery.ui.Permissions
import de.jfricker.mygallery.ui.theme.MyGalleryTheme
import org.osmdroid.config.Configuration
import java.io.File

class MainActivity : ComponentActivity() {

    private val vm: GalleryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // osmdroid: User-Agent (OSM-Nutzungsrichtlinie) und Cache im App-Speicher
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        enableEdgeToEdge()
        setContent {
            MyGalleryTheme {
                GalleryApp(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Neue Fotos übernehmen, wenn man zur App zurückkehrt
        if (Permissions.hasMedia(this)) vm.refresh()
    }
}
