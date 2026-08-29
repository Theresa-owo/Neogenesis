package net.theresa.neogenesis.utils.moduleutils

import net.theresa.neogenesis.utils.Registry
import net.theresa.neogenesis.modules.healthdisplay.ClearHealth
import net.theresa.neogenesis.modules.healthdisplay.HeartHealthChar
import net.theresa.neogenesis.modules.healthdisplay.ShowHealth
import net.theresa.neogenesis.modules.healthdisplay.ShowHealthAsHearts
import net.theresa.neogenesis.modules.nametag.ClearNameTag
import net.minecraft.client.network.NetworkPlayerInfo
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import net.minecraft.entity.Entity
import net.minecraft.entity.player.EntityPlayer
import org.lwjgl.opengl.GL11
import kotlin.math.ceil
import kotlin.math.floor

object NameTagUtil {
    private var itemList: MutableList<NameTagItem> = ArrayList()

    private fun getHearts(health: Float, colorFull: Char, colorHalf: Char, colorEmpty: Char): String {
        val healthDouble = ceil(health.toDouble()).toInt()
        val healthInt = healthDouble / 2
        val healthEmpty = floor((20.0f - health).toDouble()).toInt() / 2
        var name = ""
        for (i in 0..<healthInt) {
            name += colorFull
        }
        if (healthDouble % 2 == 1) {
            name += colorHalf
        }
        if (colorEmpty.code != 0) {
            for (i in 0..<healthEmpty) {
                name += colorEmpty
            }
        }
        return name
    }

    private fun isPlayer(var0: Entity): Boolean {
        if (var0 !is EntityPlayer) {
            return false
        } else {
            return true
        }
    }

    fun prepare(entity: Entity) {
        itemList.clear()
        if (entity.loadedExtra && entity.extraName) {
            itemList.add(NameTagItem(entity.extraNameOverride, !ClearNameTag.Instance.toggled))
        }
        if (entity is EntityPlayer) {
            val player = entity
            if (ShowHealth().toggled) {
                var string: String
                val chars: String = HeartHealthChar().value
                var dropShadow = true
                val health = player.health
                val absorption = player.absorptionAmount
                if (ShowHealthAsHearts().toggled && chars.length == 5 && 0.0f <= health && health <= 20.0f && 0.0f <= absorption && absorption <= 4.0f) {
                    string = getHearts(health, chars[1], chars[2], chars[0])
                    if (absorption > 0.0f) {
                        string += getHearts(absorption, chars[3], chars[4], 0.toChar())
                    }
                    dropShadow = false
                } else {
                    val total = health + absorption
                    string = String.format(
                        "%s%.1f \u00a7c\u2764",
                        if (total <= 5.0f) "\u00a76" else if (total >= 15.0f) "\u00a7f" else "\u00a7e", health
                    )
                    if (absorption > 0.0f) {
                        string += String.format(" \u00a7r%.1f \u00a76\u2764", absorption)
                    }
                }
                itemList.add(NameTagItem(string, !ClearHealth().toggled, dropShadow))
            }
            /*
            if (HypixelLevelGet.shouldShowHypixelLevel() && ModManager.modtog.getSettingOn("hypixel_level_tag")) {
                val stats: HypixelStats = player.getHypixelLevel()
                if (stats != null) {
                    var addDefault = true
                    if (stats.hypixelLevelInt !== -2 && ModManager.modtog.getSettingOn("hypixel_level_tag_g")) {
                        HypixelLevelGet.loadGameMode()
                        if (HypixelLevelGet.gameMode === 0) {
                            itemList.add(
                                NameTagItem(
                                    GameChat.parseString(
                                        FormatUtil.format(
                                            ModManager.modtog.getSetting("hypixel_level_tag_bw"), "level",
                                            stats.placeholders.get(8), "fkdr", stats.placeholders.get(21)
                                        )
                                    ),
                                    !ModManager.modtog.getSettingOn("clear_level")
                                )
                            )
                            addDefault = false
                        } else if (HypixelLevelGet.gameMode === 2) {
                            itemList.add(
                                NameTagItem(
                                    GameChat.parseString(
                                        FormatUtil.format(
                                            ModManager.modtog.getSetting("hypixel_level_tag_sw"), "level",
                                            stats.placeholders.get(22), "kd", stats.placeholders.get(25)
                                        )
                                    ),
                                    !ModManager.modtog.getSettingOn("clear_level")
                                )
                            )
                            addDefault = false
                        }
                    }
                    if (addDefault) {
                        itemList.add(
                            NameTagItem(
                                GameChat.parseString(
                                    FormatUtil.format(
                                        ModManager.modtog.getSetting("hypixel_level_tags"), "color",
                                        stats.placeholders.get(7), "level", stats.placeholders.get(33)
                                    )
                                ),
                                !ModManager.modtog.getSettingOn("clear_level")
                            )
                        )
                    }
                }
            }*/
        }
    }

    fun prepareName(var0: String) {
        itemList.clear()
    }

    fun renderBackgound(offset: Int) {
        var current = 0
        for (item in itemList) {
            item.renderBackground(offset - current)
            current += 9
        }
    }

    fun render(color: Int, offset: Int) {
        var current = 0
        for (item in itemList) {
            item.render(color, offset - current)
            current += 9
        }
    }

    open class NameTagItem @JvmOverloads constructor(
        var string: String,
        var doRenderBackground: Boolean,
        var doRenderShadow: Boolean = true
    ) {
        var width: Int = Registry.mc!!.fontRendererObj.getStringWidth(string)

        open fun renderBackground(offset: Int) {
            if (this.doRenderBackground) {
                val left = -this.width / 2 - 1
                val right = this.width / 2 + 1
                val top = offset - 1
                val bottom = offset + 8
                Registry.render!!.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR)
                Registry.render!!.pos(left.toDouble(), top.toDouble(), 0.0).color(0.0f, 0.0f, 0.0f, 0.25f).endVertex()
                Registry.render!!.pos(left.toDouble(), bottom.toDouble(), 0.0).color(0.0f, 0.0f, 0.0f, 0.25f).endVertex()
                Registry.render!!.pos(right.toDouble(), bottom.toDouble(), 0.0).color(0.0f, 0.0f, 0.0f, 0.25f).endVertex()
                Registry.render!!.pos(right.toDouble(), top.toDouble(), 0.0).color(0.0f, 0.0f, 0.0f, 0.25f).endVertex()
                Registry.tess!!.draw()
            }
        }

        fun render(color: Int, offset: Int) {
            if ("" != this.string) {
                val x = -this.width / 2
                if (this.doRenderShadow) {
                    Registry.mc!!.fontRendererObj.drawString(this.string, x, offset, color)
                } else {
                    Registry.mc!!.fontRendererObj.drawStringNormal(this.string, x.toFloat(), offset.toFloat(), color)
                }
            }
        }
    }
}
