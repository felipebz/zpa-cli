package br.com.felipezorzo.zpa.cli.plugin

import org.pf4j.DefaultPluginDescriptor

class ZpaPluginDescriptor : DefaultPluginDescriptor() {

    public override fun setPluginId(pluginId: String): DefaultPluginDescriptor {
        return super.setPluginId(pluginId)
    }

    fun setVersion(version: String): DefaultPluginDescriptor {
        return super.setPluginVersion(version)
    }

}
