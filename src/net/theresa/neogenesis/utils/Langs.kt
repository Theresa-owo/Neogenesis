package net.theresa.neogenesis.utils

object Langs {
    fun format(s: String, vararg args: Any): String {
        var s = s
        for (i in args.indices) {
            s = s.replace(String.format("\\$\\{%d\\}", i).toRegex(), args[i].toString())
        }
        return s
    }

    fun s(en: String, zh: String?, vararg args: Any?): String {
        return if (zh != null && "zh_CN" == Registry.mc!!.gameSettings.language) {
            format(zh, *arrayOf(args))
        } else {
            format(en, *arrayOf(args))
        }
    }

    fun guiClientSettings(vararg args: Any?): String {
        return s("Neogenesis Settings...", "Neogenesis 设置...", *args)
    }

    fun guiOptions(vararg args: Any?): String {
        return s("Options...", "选项...", *args)
    }

    fun guiDebug(vararg args: Any?): String {
        return s("Debug Settings...", "调试选项...", *args)
    }

    fun guiCrashGame(vararg args: Any?): String {
        return s("Crash Game", "崩溃游戏", *args)
    }

    fun guiReloadGame(vararg args: Any?): String {
        return s("Reload Game", "重载游戏", *args)
    }

    fun guiPrevPage(vararg args: Any?): String {
        return s("Previous Page", "上一页", *args)
    }

    fun guiNextPage(vararg args: Any?): String {
        return s("Next Page", "下一页", *args)
    }

    fun guiOptionsPage(vararg args: Any?): String {
        return s("Mod \"\${0}\" Options (Page \${1}/\${2})", "Mod \"\${0}\" 的选项(\${1}/\${2}页)", *args)
    }

    fun guiClickPos(vararg args: Any?): String {
        return s("Click at the position you want", "在想要的位置单击左键", *args)
    }

    fun guiEscRightExit(vararg args: Any?): String {
        return s("Press ESC or right click to exit", "按下 ESC 或鼠标右键退出", *args)
    }

    fun guiDepend(vararg args: Any?): String {
        return s(
            "\n\nThe following options must be enabled\nfor this option to take effect:",
            "\n\n此选项生效需要以下选项开启:", *args
        )
    }

    fun guiDependence(vararg args: Any?): String {
        return s("\n    - \${0}", null, *args)
    }

    fun guiDependMod(vararg args: Any?): String {
        return s("\n\nThis Mod must be enabled\nfor this option to take effect", "\n\n此选项生效需要启用此 Mod", *args)
    }

    fun guiReset(vararg args: Any?): String {
        return s("Reset", "重置", *args)
    }

    fun guiReloadSound(vararg args: Any?): String {
        return s("Reload Sound", "重载声音", *args)
    }

    fun guiTrue(vararg args: Any?): String {
        return s("On", "开启", *args)
    }

    fun guiFalse(vararg args: Any?): String {
        return s("Off", "关闭", *args)
    }

    fun guiEnterString(vararg args: Any?): String {
        return s("Enter text", "输入文本", *args)
    }

    fun guiHUDSettings(vararg args: Any?): String {
        return s("HUD Settings...", "HUD 设置...", *args)
    }

    fun guiAlpha(vararg args: Any?): String {
        return s("Alpha", "不透明度", *args)
    }

    fun guiRed(vararg args: Any?): String {
        return s("Red", "红色", *args)
    }

    fun guiGreen(vararg args: Any?): String {
        return s("Green", "绿色", *args)
    }

    fun guiBlue(vararg args: Any?): String {
        return s("Blue", "蓝色", *args)
    }

    fun guiChroma(vararg args: Any?): String {
        return s("Chroma", "变色速度", *args)
    }

    fun guiBreath(vararg args: Any?): String {
        return s("Breath", "呼吸速度", *args)
    }

    fun guiMinAlpha(vararg args: Any?): String {
        return s("MinAlpha", "最小不透明度", *args)
    }

    fun guiReconnect(vararg args: Any?): String {
        return s("Reconnect", "重新连接", *args)
    }

    fun guiAutoReconnect(vararg args: Any?): String {
        return s("Auto Reconnect: \${0}s", "自动重连: \${0}s", *args)
    }

    fun guiAutoReconnectNone(vararg args: Any?): String {
        return s("Auto Reconnect: Disabled", "自动重连: 关闭", *args)
    }

    fun guiResetTitle(vararg args: Any?): String {
        return s("Reset Title", "重置标题", *args)
    }

    fun guiRandomSession(vararg args: Any?): String {
        return s("Random", "随机用户名", *args)
    }

    fun guiResetSession(vararg args: Any?): String {
        return s("Reset", "重置用户名", *args)
    }

    fun guiSetSession(vararg args: Any?): String {
        return s("Username", "设置用户名", *args)
    }

    fun guiCheckUpdate(vararg args: Any?): String {
        return s("Check Update", "检查更新", *args)
    }

    fun guiUpdate(vararg args: Any?): String {
        return s("Update (Restart Game)", "更新 (重启游戏)", *args)
    }

    fun guiQuerying(vararg args: Any?): String {
        return s("Checking for Updates...", "检查更新中...", *args)
    }

    fun guiQueryError(vararg args: Any?): String {
        return s("\u00a7cAn error occurred!", "\u00a7c检查更新出错!", *args)
    }

    fun guiClientLatest(vararg args: Any?): String {
        return s("\u00a7aYour client is up to date!", "\u00a7a客户端是最新版本!", *args)
    }

    fun guiClientOld(vararg args: Any?): String {
        return s("\u00a7eYour client needs an update!", "\u00a7e客户端需要更新!", *args)
    }

    fun guiActionChangePage(vararg args: Any?): String {
        return s("Change Page", "翻页", *args)
    }

    fun guiActionOpenFile(vararg args: Any?): String {
        return s("Open File", "打开文件", *args)
    }

    fun guiActionOpenURL(vararg args: Any?): String {
        return s("Open URL", "打开链接", *args)
    }

    fun guiActionRunCommand(vararg args: Any?): String {
        return s("Run Command", "运行命令", *args)
    }

    fun guiActionCopyChat(vararg args: Any?): String {
        return s("Copy Chat", "复制聊天", *args)
    }

    fun guiActionSuggestCommand(vararg args: Any?): String {
        return s("Suggest Command", "提示命令", *args)
    }

    fun guiLoadSession(vararg args: Any?): String {
        return s("Load Session", "加载账户", *args)
    }

    fun guiFolderDesc(vararg args: Any?): String {
        return s("\u00a77Click to open the folder", "\u00a77单击打开文件夹", *args)
    }

    fun commandKickSelfError(vararg args: Any?): String {
        return s("You cannot kick yourself!", "你不能踢出自己！", *args)
    }

    fun messageDeathPoint(vararg args: Any?): String {
        return s(
            "&6&lDeath Point&r: [&d\${0}&r] (&c\${1}&r, &a\${2}&r, &b\${3}&r)",
            "&6&l死亡点&r: [&d\${0}&r] (&c\${1}&r, &a\${2}&r, &b\${3}&r)", *args
        )
    }

    fun messageAPIKeySet(vararg args: Any?): String {
        return s("&aAutomatically set API key to &b\${0}", "&a已自动将 API Key 设置为 &b\${0}", *args)
    }

    fun titleTrapAlarm(vararg args: Any?): String {
        return s("\u00a7eYOU TRIGGERED A TRAP!", "\u00a7e你触发了陷阱!", *args)
    }

    fun titleTrapAlarmMinerFatigue(vararg args: Any?): String {
        return s("\u00a7eMining Fatigue: 10s", "\u00a7e挖掘疲劳: 10s", *args)
    }

    fun potionMagicMilk(vararg args: Any?): String {
        return s("Magic Milk", "魔法牛奶", *args)
    }

    fun guiMinesweeper(vararg args: Any?): String {
        return s("Minesweeper", "扫雷", *args)
    }

    fun guiAutoSweeperOn(vararg args: Any?): String {
        return s("\u00a7aAuto Sweeper: On", "\u00a7a自动扫雷: 开", *args)
    }

    fun guiAutoSweeperOff(vararg args: Any?): String {
        return s("\u00a7cAuto Sweeper: Off", "\u00a7c自动扫雷: 关", *args)
    }

    fun guiAutoRandomOn(vararg args: Any?): String {
        return s("\u00a7aAuto Random: On", "\u00a7a自动随机: 开", *args)
    }

    fun guiAutoRandomOff(vararg args: Any?): String {
        return s("\u00a7cAuto Random: Off", "\u00a7c自动随机: 关", *args)
    }

    fun guiMiniGames(vararg args: Any?): String {
        return s("Mini Games", "小游戏", *args)
    } /*
	public static String (Object ...args) { return s("", "", args); }
	 */
}