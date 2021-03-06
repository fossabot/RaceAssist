/*
 * Copyright © 2022 Nikomaru <nikomaru@nikomaru.dev>
 * This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.nikomaru.raceassist.bet.gui

import com.google.common.collect.ImmutableList
import dev.nikomaru.raceassist.bet.GuiComponent
import dev.nikomaru.raceassist.database.BetList
import dev.nikomaru.raceassist.database.BetSetting
import dev.nikomaru.raceassist.database.PlayerList
import dev.nikomaru.raceassist.files.Config.betUnit
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*
import kotlin.math.floor

class BetChestGui {

    fun getGUI(player: Player, raceID: String): Inventory {

        val gui = Bukkit.createInventory(player, 45, GuiComponent.guiComponent())
        val playerWools = ImmutableList.of(
            Material.RED_WOOL,
            Material.BLUE_WOOL,
            Material.YELLOW_WOOL,
            Material.GREEN_WOOL,
            Material.BROWN_WOOL,
            Material.PINK_WOOL,
            Material.WHITE_WOOL
        )
        val rate: Int = transaction {
            BetSetting.select { BetSetting.raceID eq raceID }.first()[BetSetting.returnPercent]
        }
        val players: ArrayList<UUID> = ArrayList()
        val odds: HashMap<UUID, Double> = HashMap()
        AllPlayers[raceID] = ArrayList<UUID>()
        transaction {
            PlayerList.select { PlayerList.raceID eq raceID }.forEach {
                players.add(UUID.fromString(it[PlayerList.playerUUID]))
                AllPlayers[raceID]!!.add(UUID.fromString(it[PlayerList.playerUUID]))
            }
        }
        var sum = 0
        transaction {
            BetList.select { BetList.raceID eq raceID }.forEach {
                sum += it[BetList.betting]
            }
        }
        players.forEach { jockey ->
            transaction {
                var jockeySum = 0
                BetList.select { (BetList.raceID eq raceID) and (BetList.jockey eq Bukkit.getOfflinePlayer(jockey).name!!) }.forEach {
                    jockeySum += it[BetList.betting]
                }
                odds[jockey] = floor(((sum * (rate.toDouble() / 100)) / jockeySum) * 100) / 100
            }
        }


        for (i in 0 until 45) {
            gui.setItem(i, ItemStack(Material.GRAY_STAINED_GLASS_PANE))
        }
        for (i in 0 until players.size) {
            val item = ItemStack(playerWools[i])
            val prevMeta = item.itemMeta
            prevMeta.displayName(text("${betUnit}円単位 : 0円かけています", TextColor.fromHexString("#00ff7f")))
            val lore: ArrayList<Component> = ArrayList<Component>()
            lore.add(text("騎手 : ${Bukkit.getOfflinePlayer(players[i]).name} ", TextColor.fromHexString("#00a497")))
            lore.add(text("オッズ : ${odds[players[i]]} ", TextColor.fromHexString("#e6b422")))
            prevMeta.lore(lore)
            item.itemMeta = prevMeta

            gui.setItem(i, GuiComponent.tenTimesUp())
            gui.setItem(i + 9, GuiComponent.onceUp())
            gui.setItem(i + 18, item)
            gui.setItem(i + 27, GuiComponent.onceDown())
            gui.setItem(i + 36, GuiComponent.tenTimesDown())
        }
        val raceIDItem = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        val raceIDMeta = raceIDItem.itemMeta
        raceIDMeta.displayName(text(raceID, TextColor.fromHexString("#00ff7f")))
        raceIDItem.itemMeta = raceIDMeta

        gui.setItem(8, raceIDItem)
        gui.setItem(17, GuiComponent.reset())
        gui.setItem(35, GuiComponent.deny())
        gui.setItem(44, GuiComponent.accept())

        return gui
    }

    companion object {
        val AllPlayers: HashMap<String, ArrayList<UUID>> = HashMap()
    }
}