package net.theresa.neogenesis.interactions

import net.minecraft.client.settings.KeyBinding
import net.theresa.neogenesis.utils.Langs;

class KeyBindingLang(desc: String?, var descen: String, var desczh: String, keyCode: Int, category: String?) :
    KeyBinding(desc, keyCode, category) {
    val keyName: String
        get() = Langs.s(descen, desczh)
}