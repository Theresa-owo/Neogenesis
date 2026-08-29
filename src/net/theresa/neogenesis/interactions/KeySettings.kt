package net.theresa.neogenesis.interactions

import net.theresa.neogenesis.utils.VersionManager
import net.minecraft.client.settings.KeyBinding

object KeySettings
{
    private var NAMESPACE: String = VersionManager.NAME
    private var NAMESPACE_AUTOTEXT: String = "Auto Text"

    private var NAMESPACE_DR: String = "Dungeon Rooms Mod"
    private var NAMESPACE_SKB: String = "Neogenesis - SkyBlock"

    private var keyHUDConfig: KeyBindingLang = KeyBindingLang("HUD Config Screen", "HUD Config Screen", "HUD 设置界面", 0, NAMESPACE)
    private var keySettings: KeyBindingLang = KeyBindingLang("Setting Screen", "Setting Screen", "设置界面", 0, NAMESPACE)
    var keyToggleSprint: KeyBindingLang = KeyBindingLang("Auto Sprint", "Toggle Sprint", "自动疾跑", 0, NAMESPACE)
    var keyFreeLook: KeyBindingLang = KeyBindingLang("Free Look", "Free Look", "自由视角", 56, NAMESPACE)
    private var keyClientCommand: KeyBindingLang = KeyBindingLang("Client Command", "Client Command", "客户端命令", 0, NAMESPACE)
    private var keyAutoText1: KeyBindingLang =
        KeyBindingLang("Auto Text 1", "Auto Text 1", "快捷消息 1", 0, NAMESPACE_AUTOTEXT)
    private var keyAutoText2: KeyBindingLang =
        KeyBindingLang("Auto Text 1", "Auto Text 2", "快捷消息 2", 0, NAMESPACE_AUTOTEXT)
    private var keyAutoText3: KeyBindingLang =
        KeyBindingLang("Auto Text 3", "Auto Text 3", "快捷消息 3", 0, NAMESPACE_AUTOTEXT)
    private var keyAutoText4: KeyBindingLang =
        KeyBindingLang("Auto Text 4", "Auto Text 4", "快捷消息 4", 0, NAMESPACE_AUTOTEXT)
    private var keyAutoText5: KeyBindingLang =
        KeyBindingLang("Auto Text 5", "Auto Text 5", "快捷消息 5", 0, NAMESPACE_AUTOTEXT)
    private var keyAutoText6: KeyBindingLang =
        KeyBindingLang("Auto Text 6", "Auto Text 6", "快捷消息 6", 0, NAMESPACE_AUTOTEXT)
    private var keyAutoText7: KeyBindingLang =
        KeyBindingLang("Auto Text 7", "Auto Text 7", "快捷消息 7", 0, NAMESPACE_AUTOTEXT)
    private var keyAutoText8: KeyBindingLang =
        KeyBindingLang("Auto Text 8", "Auto Text 8", "快捷消息 8", 0, NAMESPACE_AUTOTEXT)
    private var keyAutoText9: KeyBindingLang =
        KeyBindingLang("Auto Text 9", "Auto Text 9", "快捷消息 9", 0, NAMESPACE_AUTOTEXT)

    @JvmStatic
    var keyBindings: Array<KeyBinding> = arrayOf(
        keyHUDConfig, keySettings, keyToggleSprint, keyFreeLook,
        keyClientCommand, keyAutoText1, keyAutoText2, keyAutoText3, keyAutoText4, keyAutoText5,
        keyAutoText6, keyAutoText7, keyAutoText8, keyAutoText9,
    )

    var autoTextKeyBindings: Array<KeyBinding> = arrayOf(
        keyAutoText1, keyAutoText2, keyAutoText3, keyAutoText4, keyAutoText5, keyAutoText6,
        keyAutoText7, keyAutoText8
    )
}