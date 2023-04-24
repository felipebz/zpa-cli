package br.com.felipezorzo.zpa.cli.plugin

import org.pf4j.DefaultPluginManager
import org.pf4j.PluginDescriptorFinder

class PluginManager: DefaultPluginManager() {

    override fun createPluginDescriptorFinder(): PluginDescriptorFinder {
        return ZpaPluginDescriptorFinder()
    }

}
