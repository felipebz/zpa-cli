package br.com.felipezorzo.zpa.cli.plugin

import org.pf4j.DefaultPluginManager
import org.pf4j.PluginDescriptorFinder
import java.nio.file.Path

class PluginManager(pluginRoot: Path): DefaultPluginManager(pluginRoot) {

    override fun createPluginDescriptorFinder(): PluginDescriptorFinder {
        return ZpaPluginDescriptorFinder()
    }

}
