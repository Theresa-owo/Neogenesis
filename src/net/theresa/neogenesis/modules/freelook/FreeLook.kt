package net.theresa.neogenesis.modules.freelook

import net.theresa.neogenesis.events.KeyboardEvent
import net.theresa.neogenesis.events.eventhandlers.KeyEventHandler
import net.theresa.neogenesis.events.eventhandlers.registries.KeyEventHandlerRegistry
import net.theresa.neogenesis.interactions.KeyBindingLang
import net.theresa.neogenesis.interactions.KeySettings
import net.theresa.neogenesis.interfaces.modules.IExtendable
import net.theresa.neogenesis.interfaces.modules.IToggleable
import net.theresa.neogenesis.utils.moduleutils.FreeLookUtil


class FreeLook : IToggleable, IExtendable, KeyEventHandler
{
    override var name: String = "[Hypixel非法] 自由视角"
    override var toggled: Boolean = true
    override var key: KeyBindingLang = KeySettings.keyFreeLook
    override var childList: MutableList<String> = mutableListOf<String>()

    private var isFreeLooking: Boolean = false

    companion object
    {
        @JvmField
        val Instance: FreeLook = FreeLook()
        @JvmStatic
        fun init() {
            FreeLookUtil.Instance = FreeLookUtil()
            KeyEventHandlerRegistry.register(Instance)
        }
        fun regSubClass(subclass: Any) {
            val subclassName = subclass::class.simpleName
            if (subclassName != null && subclassName !in Instance.childList) {
                Instance.childList.add(subclassName)
            }
        }
    }

    override fun handleKeys(keyEvent: KeyboardEvent) {
        if (!toggled) return
        isFreeLooking = if (!IsHoldFreeLook.Instance.toggled) !isFreeLooking
        else true
        //if (keyEvent.keyCode != key.keyCode) return
        if (isFreeLooking != FreeLookUtil.Instance?.prevState)
        {
            FreeLookUtil.Instance?.onPressed(isFreeLooking)
            FreeLookUtil.Instance?.prevState = isFreeLooking
        }
    }
}