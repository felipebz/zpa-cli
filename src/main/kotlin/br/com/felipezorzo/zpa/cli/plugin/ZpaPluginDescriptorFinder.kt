package br.com.felipezorzo.zpa.cli.plugin

import org.pf4j.PluginDescriptor
import org.pf4j.PluginDescriptorFinder
import org.pf4j.PluginRuntimeException
import org.pf4j.util.FileUtils
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.jar.Manifest

class ZpaPluginDescriptorFinder : PluginDescriptorFinder {

    override fun isApplicable(pluginPath: Path): Boolean {
        return Files.exists(pluginPath) && FileUtils.isJarFile(pluginPath)
    }

    override fun find(pluginPath: Path): PluginDescriptor {
        val manifest = readManifest(pluginPath)
        return createPluginDescriptor(manifest)
    }

    private fun readManifest(pluginPath: Path): Manifest {
        try {
            return JarFile(pluginPath.toFile()).use { it.manifest }
        } catch (e: IOException) {
            throw PluginRuntimeException(e, "Cannot read manifest from {}", pluginPath)
        }
    }

    private fun createPluginDescriptor(manifest: Manifest): PluginDescriptor {
        val pluginDescriptor = ZpaPluginDescriptor()
        val attributes = manifest.mainAttributes

        val id = attributes.getValue(PLUGIN_ID)
        pluginDescriptor.pluginId = id

        val version = attributes.getValue(PLUGIN_VERSION)
        if (version.isNotEmpty()) {
            pluginDescriptor.version = version
        }

        return pluginDescriptor
    }

    companion object {
        const val PLUGIN_ID = "Plugin-Key"
        const val PLUGIN_VERSION = "Plugin-Version"
    }

}
