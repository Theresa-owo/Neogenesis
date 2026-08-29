package net.theresa.neogenesis.modules.culling.particleculling

import net.theresa.neogenesis.events.Listener
import net.theresa.neogenesis.interfaces.modules.IToggleable

class ParticleCulling : Listener, IToggleable {

    override var name: String = "削弱粒子渲染"
    override var toggled: Boolean = true

    fun onLoadComplete() {
        cullThread = CullThread()
        cullThread!!.start()
    }

    companion object {
        var cullThread: CullThread? = null
        lateinit var Instance: ParticleCulling
    }

    init {
        Instance = this
    }
}