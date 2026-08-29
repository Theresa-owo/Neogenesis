package net.theresa.neogenesis.modules

import net.theresa.neogenesis.ClientMain
import net.theresa.neogenesis.interfaces.modules.TypedModule
import net.theresa.neogenesis.modules.autosprint.AutoSprint
import net.theresa.neogenesis.modules.blockoverlay.*
import net.theresa.neogenesis.modules.chat.*
import net.theresa.neogenesis.modules.chat.autotext.*
import net.theresa.neogenesis.modules.culling.entityculling.EntityCulling
import net.theresa.neogenesis.modules.culling.particleculling.DisableLightUpdates
import net.theresa.neogenesis.modules.culling.particleculling.ParticleCulling
import net.theresa.neogenesis.modules.font.CharacterFix
import net.theresa.neogenesis.modules.font.FontShadow
import net.theresa.neogenesis.modules.font.NoRomanNumerals
import net.theresa.neogenesis.modules.font.SpaceWidthFix
import net.theresa.neogenesis.modules.freelook.FreeLook
import net.theresa.neogenesis.modules.freelook.IsHoldFreeLook
import net.theresa.neogenesis.modules.healthdisplay.ClearHealth
import net.theresa.neogenesis.modules.healthdisplay.HeartHealthChar
import net.theresa.neogenesis.modules.healthdisplay.ShowHealth
import net.theresa.neogenesis.modules.healthdisplay.ShowHealthAsHearts
import net.theresa.neogenesis.modules.itemanimations.*
import net.theresa.neogenesis.modules.misc.FpsLimiter
import net.theresa.neogenesis.modules.misc.FpsLimiter_Value
import net.theresa.neogenesis.modules.multiplayer.AutoReconnect
import net.theresa.neogenesis.modules.multiplayer.AutoReconnectTime
import net.theresa.neogenesis.modules.nametag.ClearNameTag
import net.theresa.neogenesis.modules.nametag.SelfNameTag
import net.theresa.neogenesis.modules.particles.AlwaysShowSharpness
import net.theresa.neogenesis.modules.particles.DisableSelfCritParticle
import net.theresa.neogenesis.modules.particles.ParticleMultiplier
import net.theresa.neogenesis.modules.particles.ParticleMultiplierValue
import net.theresa.neogenesis.modules.rawinput.RawInput
import net.theresa.neogenesis.modules.render.*
import net.theresa.neogenesis.modules.scoreboard.HideScoreboard
import net.theresa.neogenesis.modules.scoreboard.HideScoreboardTitle
import net.theresa.neogenesis.modules.scoreboard.NoScoreboardNumbers
import net.theresa.neogenesis.modules.sounds.NoRecordSounds
import net.theresa.neogenesis.modules.tab.*
import net.theresa.neogenesis.modules.unlegit.NoHitDelay
import net.theresa.neogenesis.modules.zoom.Magnification
import net.theresa.neogenesis.modules.zoom.ScrollZoom
import net.theresa.neogenesis.modules.zoom.SmoothZoom
import net.theresa.neogenesis.modules.zoom.SmoothZoomSpeed
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.isAccessible

class ModuleLoader {
    companion object {
        @JvmStatic
        fun load() = initCoreModules()

        @JvmStatic
        fun lateLoad() {
            initExtendedModules()
            initEventHandlers()
            postInitialize()
        }

        // 核心模块初始化
        private fun initCoreModules() {
            listOf(
                ::BlockOverlay,
                ::AutoText,
                ::SmoothChat,
                ::ShowHealth,
                ::ItemAnimations,
                ::FpsLimiter,
                ::AutoReconnect,
                ::ParticleMultiplier
            ).forEach { initModule(it) }
        }

        // 扩展模块初始化
        private fun initExtendedModules() {
            initAutoSprint()
            initBlockOverlayComponents()
            initChatComponents()
            initCullingComponents()
            initFontComponents()
            initFreeLook()
            initHealthDisplay()
            initItemAnimations()
            initMisc()
            initMultiplayer()
            initNameTag()
            initParticles()
            initRawInput()
            initRender()
            initScoreboard()
            initSounds()
            initTab()
            initUnlegit()
            initZoom()
        }

        // 各分类初始化方法
        private fun initAutoSprint() {
            AutoSprint.Instance = AutoSprint("key.sprint", 29, "key.categories.movement") { AutoSprint.Instance.toggled }
        }

        private fun initBlockOverlayComponents() {
            listOf(
                ::BlockOutlineColor,
                ::BlockOutlineThickness,
                ::BlockOverlayColor,
                ::FullSelectionBox
            ).forEach { initModule(it) }
        }

        private fun initChatComponents() {
            listOf(
                ::AutoText_1,
                ::AutoText_2,
                ::AutoText_3,
                ::AutoText_4,
                ::AutoText_5,
                ::AutoText_6,
                ::AutoText_7,
                ::AutoText_8,
                ::AutoText_9,
                ::ChatBackgroundColor,
                ::DisableAchievementNotifications,
                ::DisableChatHistoryLimit,
                ::DisableChatLimit,
                ::HeightFix,
                ::SmoothChatVerticalSpeed,
                ::SmoothChatHorizontalSpeed
            ).forEach { initModule(it) }
        }

        private fun initCullingComponents() {
            listOf(
                ::DisableLightUpdates,
                ::EntityCulling,
                ::ParticleCulling
            ).forEach { initModule(it) }
        }

        private fun initFontComponents() {
            listOf(
                ::CharacterFix,
                ::FontShadow,
                ::NoRomanNumerals,
                ::SpaceWidthFix
            ).forEach { initModule(it) }
        }

        private fun initFreeLook() {
            FreeLook.init()
            initModule(::IsHoldFreeLook)
        }

        private fun initHealthDisplay() {
            listOf(
                ::ClearHealth,
                ::HeartHealthChar,
                ::ShowHealthAsHearts
            ).forEach { initModule(it) }
        }

        private fun initItemAnimations() {
            listOf(
                ::BlockSwing,
                ::BreakUse,
                ::CdFix,
                ::InvLight,
                ::ItemPositionX,
                ::ItemPositionY,
                ::ItemPositionZ,
                ::ItemScale,
                ::Model,
                ::ModelFix,
                ::NoBlockhitting,
                ::NoSwingingItem,
                ::Sneak,
                ::SwingSpeed,
                ::ThirdPersonViewBlockhitting
            ).forEach { initModule(it) }
        }

        private fun initMisc() {
            initModule(::FpsLimiter_Value)
        }

        private fun initMultiplayer() {
            initModule(::AutoReconnectTime)
        }

        private fun initNameTag() {
            listOf(
                ::ClearNameTag,
                ::SelfNameTag
            ).forEach { initModule(it) }
        }

        private fun initParticles() {
            listOf(
                ::AlwaysShowSharpness,
                ::DisableSelfCritParticle,
                ::ParticleMultiplierValue
            ).forEach { initModule(it) }
        }

        private fun initRawInput() {
            initModule(::RawInput)
        }

        private fun initRender() {
            listOf(
                ::BetterSky,
                ::FullBright,
                ::HideFire,
                ::NoArrows,
                ::NoCameraBobbing,
                ::NoDeadAnimations,
                ::NoFallingBlocks,
                ::NoHandBobbing,
                ::NoHurtEffect,
                ::NoLightning,
                ::ShowBarrierAsGlass
            ).forEach { initModule(it) }
        }

        private fun initScoreboard() {
            listOf(
                ::HideScoreboard,
                ::HideScoreboardTitle,
                ::NoScoreboardNumbers
            ).forEach { initModule(it) }
        }

        private fun initSounds() {
            initModule(::NoRecordSounds)
        }

        private fun initTab() {
            listOf(
                ::BetterTAB,
                ::HideTabFooter,
                ::HideTabHeader,
                ::HideTabListPing,
                ::TabListTransparency
            ).forEach { initModule(it) }
        }

        private fun initUnlegit() {
            initModule(::NoHitDelay)
        }

        private fun initZoom() {
            listOf(
                ::Magnification,
                ::ScrollZoom,
                ::SmoothZoom,
                ::SmoothZoomSpeed
            ).forEach { initModule(it) }
        }

        private fun initModule(vararg moduleConstructors: KFunction<TypedModule>) {
            moduleConstructors.forEach { constructor ->

                constructor.isAccessible = true
                val moduleInstance = constructor.call()
                ModuleFactory.modules.add(moduleInstance)
            }
        }

        private fun initEventHandlers() {
            //RawInput.init()
            with(ClientMain.eventManager) {
                register(EntityCulling.Instance)
                register(ParticleCulling.Instance)
            }
        }

        private fun postInitialize() {
            EntityCulling.Instance.onInitialize()
            ParticleCulling.Instance.onLoadComplete()
        }
    }
}