/*
 *  Copyright © 2021 Nikomaru
 *
 *  This program is free software: you can redistribute it and/or modify
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
package dev.nikomaru.raceassist.race.commands

import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.*
import dev.nikomaru.raceassist.RaceAssist.Companion.plugin
import dev.nikomaru.raceassist.database.Database
import dev.nikomaru.raceassist.race.commands.AudienceCommand.Companion.audience
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.NamedTextColor.*
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.title.Title.title
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.ScoreboardManager
import java.awt.Polygon
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.*
import kotlin.math.atan2
import kotlin.math.ceil

@CommandAlias("ra|RaceAssist")
@Subcommand("race")
class RaceCommand : BaseCommand() {

    @CommandPermission("RaceAssist.commands.race")
    @Subcommand("start")
    @CommandCompletion("@RaceID")
    fun start(sender: CommandSender, raceID: String) {
        if (starting) {
            sender.sendMessage(text("他のレース開始中です", TextColor.color(RED)))
            return
        }
        if (getRaceCreator(raceID) != (sender as Player).uniqueId) {
            sender.sendMessage(text("レース作成者しか開始することはできません", TextColor.color(RED)))
            return
        }
        if (!getCircuitExist(raceID, true) || !getCircuitExist(raceID, false)) {
            sender.sendMessage(text("レースが存在しません", TextColor.color(YELLOW)))
            return
        }
        val jockeys: ArrayList<Player> = ArrayList()
        getAllJockeys(raceID)?.forEach { jockey ->
            if (jockey.isOnline) {
                jockeys.add(jockey as Player)
                sender.sendMessage(text("${jockey.name}が参加しました", TextColor.color(GREEN)))
            } else {
                sender.sendMessage(text("${jockey.name}はオフラインです", TextColor.color(YELLOW)))
            }
        }
        if (jockeys.size < 2) {
            sender.sendMessage(text("開催には2人以上のユーザーが必要です", TextColor.color(YELLOW)))
            return
        }

        val centralXPoint: Int =
            getCentralPoint(raceID, true) ?: return sender.sendMessage(text("中心点が存在しません", TextColor.color(YELLOW)))
        val centralYPoint: Int =
            getCentralPoint(raceID, false) ?: return sender.sendMessage(text("中心点が存在しません", TextColor.color(YELLOW)))
        val goalDegree: Int =
            getGoalDegree(raceID) ?: return sender.sendMessage(text("ゴール角度が存在しません", TextColor.color(YELLOW)))

        if (goalDegree % 90 != 0) {
            sender.sendMessage(text("ゴール角度は90の倍数である必要があります", TextColor.color(YELLOW)))
            return
        }
        starting = true
        val jockeyCount = jockeys.size
        val finishJockey: ArrayList<UUID> = ArrayList<UUID>()
        val totalDegree: HashMap<UUID, Int> = HashMap()
        val insidePolygon = getPolygon(raceID, true)
        val outsidePolygon = getPolygon(raceID, false)
        val reverse = getReverse(raceID) ?: false
        val lap: Int = getLapCount(raceID)
        val beforePoint: HashMap<UUID, Int> = HashMap()
        val currentLap: HashMap<UUID, Int> = HashMap()
        val threshold = 40
        val raceAudience: ArrayList<UUID> = ArrayList()
        audience[raceID]?.forEach {
            if (Bukkit.getPlayer(it) != null && Bukkit.getPlayer(it)?.isOnline == true) {
                raceAudience.add(it)
            }
        }
        var timer = 0
        object : BukkitRunnable() {
            override fun run() {
                if (timer >= 4) {
                    cancel()
                }
                jockeys.forEach {
                    it.showTitle(title(text("${5 - timer}", TextColor.color(GREEN)), text(" ")))
                }
                raceAudience.forEach {
                    Bukkit.getPlayer(it)?.showTitle(title(text("${5 - timer}", TextColor.color(GREEN)), text(" ")))
                }
                sender.showTitle(title(text("${5 - timer}", TextColor.color(GREEN)), text(" ")))
                timer++
            }
        }.runTaskTimer(plugin!!, 0, 20)
        object : BukkitRunnable() {
            override fun run() {
                jockeys.forEach {
                    it.showTitle(title(text("レースが開始しました", TextColor.color(GREEN)), text(" ")))
                    beforePoint[it.uniqueId] = 0
                    currentLap[it.uniqueId] = 0
                }
                raceAudience.forEach {
                    Bukkit.getPlayer(it)?.showTitle(title(text("レースが開始しました", TextColor.color(GREEN)), text(" ")))
                }
                object : BukkitRunnable() {
                    override fun run() {
                        val iterator = jockeys.iterator()
                        while (iterator.hasNext()) {
                            val player: Player = iterator.next()
                            if (!player.isOnline) {
                                iterator.remove()
                                continue
                            }
                            val nowX: Int = player.location.blockX
                            val nowY: Int = player.location.blockZ
                            var relativeNowX = nowX - centralXPoint
                            val relativeNowY = nowY - centralYPoint
                            if (reverse) {
                                relativeNowX = -relativeNowX
                            }
                            val currentDegree =
                                if (Math.toDegrees(atan2(relativeNowY.toDouble(), relativeNowX.toDouble()))
                                        .toInt() < 0
                                ) {
                                    360 + Math.toDegrees(atan2(relativeNowY.toDouble(), relativeNowX.toDouble()))
                                        .toInt()
                                } else {
                                    Math.toDegrees(atan2(relativeNowY.toDouble(), relativeNowX.toDouble())).toInt()
                                }

                            val beforeLap = currentLap[player.uniqueId]
                            when (goalDegree) {
                                0 -> {
                                    if (!reverse) {
                                        if ((beforePoint[player.uniqueId] in 360 - threshold until 360) && (currentDegree in 0 until threshold)) {
                                            currentLap[player.uniqueId] = currentLap[player.uniqueId]!! + 1
                                        }
                                        if ((beforePoint[player.uniqueId] in 0 until threshold) && (currentDegree in 360 - threshold until 360)) {
                                            currentLap[player.uniqueId] = currentLap[player.uniqueId]!! - 1
                                        }
                                    } else {
                                        if ((beforePoint[player.uniqueId] in 0 until threshold) && (currentDegree in 360 - threshold until 360)) {
                                            currentLap[player.uniqueId] = currentLap[player.uniqueId]!! + 1
                                        }
                                        if ((beforePoint[player.uniqueId] in 360 - threshold until 360) && (currentDegree in 0 until threshold)) {
                                            currentLap[player.uniqueId] = currentLap[player.uniqueId]!! - 1
                                        }
                                    }
                                }
                                90 -> {
                                    if (!reverse) {
                                        if ((beforePoint[player.uniqueId] in goalDegree - threshold until goalDegree) && currentDegree in goalDegree until goalDegree + threshold) {
                                            currentLap[player.uniqueId] = currentLap[player.uniqueId]!! + 1
                                        }
                                        if ((beforePoint[player.uniqueId] in goalDegree until goalDegree + threshold) && (currentDegree in goalDegree - threshold until goalDegree)) {
                                            currentLap[player.uniqueId] = currentLap[player.uniqueId]!! - 1
                                        }
                                    } else {
                                        if ((beforePoint[player.uniqueId] in goalDegree until goalDegree + threshold) && (currentDegree in goalDegree - threshold until goalDegree)) {
                                            currentLap[player.uniqueId] = currentLap[player.uniqueId]!! + 1
                                        }
                                        if ((beforePoint[player.uniqueId] in goalDegree - threshold until goalDegree) && (currentDegree in goalDegree until goalDegree + threshold)) {
                                            currentLap[player.uniqueId] = currentLap[player.uniqueId]!! - 1
                                        }
                                    }
                                }
                                180 -> {
                                    if (!reverse) {
                                        if ((beforePoint[player.uniqueId] in goalDegree - threshold until goalDegree) && (currentDegree in goalDegree until goalDegree + threshold)) {
                                            currentLap[player.uniqueId] = currentLap[player.uniqueId]!! + 1
                                        }
                                        if ((beforePoint[player.uniqueId] in goalDegree until goalDegree + threshold) && (currentDegree in goalDegree - threshold until goalDegree)) {
                                            currentLap[player.uniqueId] = currentLap[player.uniqueId]!! - 1
                                        }
                                    } else {
                                        if ((beforePoint[player.uniqueId] in goalDegree until goalDegree + threshold) && (currentDegree in goalDegree - threshold until goalDegree)) {
                                            currentLap[player.uniqueId] = currentLap[player.uniqueId]!! + 1
                                        }
                                        if ((beforePoint[player.uniqueId] in goalDegree - threshold until goalDegree) && (currentDegree in goalDegree until goalDegree + threshold)) {
                                            currentLap[player.uniqueId] = currentLap[player.uniqueId]!! - 1
                                        }
                                    }
                                }
                                270 -> {
                                    if (!reverse) {
                                        if ((beforePoint[player.uniqueId] in goalDegree - threshold until goalDegree) && (currentDegree in goalDegree until goalDegree + threshold)) {
                                            currentLap[player.uniqueId] = currentLap[player.uniqueId]!! + 1
                                        }
                                        if ((beforePoint[player.uniqueId] in goalDegree until goalDegree + threshold) && (currentDegree in goalDegree - threshold until goalDegree)) {
                                            currentLap[player.uniqueId] = currentLap[player.uniqueId]!! - 1
                                        }
                                    } else {
                                        if ((beforePoint[player.uniqueId] in goalDegree until goalDegree + threshold) && (currentDegree in goalDegree - threshold until goalDegree)) {
                                            currentLap[player.uniqueId] = currentLap[player.uniqueId]!! + 1
                                        }
                                        if ((beforePoint[player.uniqueId] in goalDegree - threshold until goalDegree) && (currentDegree in goalDegree until goalDegree + threshold)) {
                                            currentLap[player.uniqueId] = currentLap[player.uniqueId]!! - 1
                                        }
                                    }
                                }
                            }

                            displayLap(currentLap[player.uniqueId], beforeLap, player, lap)

                            if (insidePolygon.contains(nowX, nowY) || !outsidePolygon.contains(nowX, nowY)) {
                                player.sendActionBar(text("レース場外に出てる可能性があります", TextColor.color(RED)))
                            }
                            beforePoint[player.uniqueId] = currentDegree
                            if (currentLap[player.uniqueId]!! >= lap) {
                                iterator.remove()
                                finishJockey.add(player.uniqueId)
                                totalDegree.remove(player.uniqueId)
                                player.sendMessage(
                                    text(
                                        "${jockeyCount - jockeys.size}/$jockeyCount 位です", TextColor.color(GREEN)
                                    )
                                )
                                continue
                            }
                            totalDegree[player.uniqueId] = currentDegree + currentLap[player.uniqueId]!! * 360
                        }
                        if (jockeys.size < 1 || stop[raceID] == true) {
                            object : BukkitRunnable() {
                                override fun run() {
                                    Bukkit.getOnlinePlayers().forEach {
                                        it.scoreboard.clearSlot(DisplaySlot.SIDEBAR)
                                    }
                                }
                            }.runTaskLater(plugin!!, 40)
                            starting = false
                            cancel()
                        }
                        val raceAudienceIterator = raceAudience.iterator()
                        while (raceAudienceIterator.hasNext()) {
                            val it = raceAudienceIterator.next()
                            if (!Bukkit.getOfflinePlayer(it).isOnline) {
                                raceAudienceIterator.remove()
                            }
                        }

                        displayScoreboard(finishJockey.plus(decideRanking(totalDegree)), totalDegree, raceAudience)
                    }
                }.runTaskTimer(plugin!!, 0, (20 * 0.2).toLong())
            }
        }.runTaskLater(plugin!!, 20 * 5)
    }

    @CommandPermission("RaceAssist.commands.race")
    @Subcommand("debug")
    @CommandCompletion("@RaceID")
    fun debug(sender: CommandSender, @Single raceID: String) {
        if (starting) {
            sender.sendMessage(text("他のレース開始中です", TextColor.color(RED)))
            return
        }
        starting = true
        if (getRaceCreator(raceID) != (sender as Player).uniqueId) {
            sender.sendMessage(text("レース作成者しか開始することはできません", TextColor.color(RED)))
        }
        if (!getCircuitExist(raceID, true) || !getCircuitExist(raceID, false)) {
            sender.sendMessage(text("レースが存在しません", TextColor.color(YELLOW)))
            return
        }

        val insidePolygon = getPolygon(raceID, true)
        val outsidePolygon = getPolygon(raceID, false)
        if (insidePolygon.npoints < 3 || outsidePolygon.npoints < 3) {
            sender.sendMessage(text("レースが存在しません", TextColor.color(YELLOW)))
            return
        }
        val reverse = getReverse(raceID) ?: false
        val lap: Int = getLapCount(raceID)
        val threshold = 40
        val centralXPoint: Int =
            getCentralPoint(raceID, true) ?: return sender.sendMessage(text("中心点が存在しません", TextColor.color(YELLOW)))
        val centralYPoint: Int =
            getCentralPoint(raceID, false) ?: return sender.sendMessage(text("中心点が存在しません", TextColor.color(YELLOW)))
        val goalDegree: Int =
            getGoalDegree(raceID) ?: return sender.sendMessage(text("ゴール角度が存在しません", TextColor.color(YELLOW)))
        var beforeDegree = 0
        var currentLap = 0
        var counter = 0
        var timer = 0
        object : BukkitRunnable() {
            override fun run() {
                if (timer >= 4) {
                    this.cancel()
                }
                sender.showTitle(title(text("${5 - timer}", TextColor.color(GREEN)), text(" ")))
                timer++
            }
        }.runTaskTimer(plugin!!, 0, 20)
        object : BukkitRunnable() {
            override fun run() {
                sender.showTitle(title(text("スタートです", TextColor.color(GREEN)), text(" ")))
                object : BukkitRunnable() {
                    override fun run() {
                        val it: Player = sender
                        val nowX: Int = it.location.blockX
                        val nowY: Int = it.location.blockZ
                        var relativeNowX = nowX - centralXPoint
                        val relativeNowY = nowY - centralYPoint
                        if (reverse) {
                            relativeNowX = -relativeNowX
                        }
                        val currentDegree =
                            if (Math.toDegrees(atan2(relativeNowY.toDouble(), relativeNowX.toDouble())).toInt() < 0) {
                                360 + Math.toDegrees(atan2(relativeNowY.toDouble(), relativeNowX.toDouble())).toInt()
                            } else {
                                Math.toDegrees(atan2(relativeNowY.toDouble(), relativeNowX.toDouble())).toInt()
                            }
                        val beforeLap = currentLap
                        when (goalDegree) {
                            0 -> {
                                if (!reverse) {
                                    if (beforeDegree in 360 - threshold until 360) {
                                        if (currentDegree in 0 until threshold) {
                                            currentLap += 1
                                        }
                                    }
                                    if (beforeDegree in 0 until threshold) {
                                        if (currentDegree in 360 - threshold until 360) {
                                            currentLap -= 1
                                        }
                                    }
                                } else {
                                    if (beforeDegree in 360 - threshold until 360) {
                                        if (currentDegree in 0 until threshold) {
                                            currentLap -= 1
                                        }
                                    }
                                    if (beforeDegree in 0 until threshold) {
                                        if (currentDegree in 360 - threshold until 360) {
                                            currentLap += 1
                                        }
                                    }
                                }
                            }
                            90 -> {
                                if (!reverse) {
                                    if (beforeDegree in goalDegree - threshold until goalDegree) {
                                        if (currentDegree in goalDegree until goalDegree + threshold) {
                                            currentLap += 1
                                        }
                                    }
                                    if (beforeDegree in goalDegree until goalDegree + threshold) {
                                        if (currentDegree in goalDegree - threshold until goalDegree) {
                                            currentLap -= 1
                                        }
                                    }
                                } else {
                                    if (beforeDegree in goalDegree - threshold until goalDegree) {
                                        if (currentDegree in goalDegree until goalDegree + threshold) {
                                            currentLap += 1
                                        }
                                    }
                                    if (beforeDegree in goalDegree until goalDegree + threshold) {
                                        if (currentDegree in goalDegree - threshold until goalDegree) {
                                            currentLap -= 1
                                        }
                                    }
                                }
                            }
                            180 -> {
                                if (!reverse) {
                                    if (beforeDegree in goalDegree - threshold until goalDegree) {
                                        if (currentDegree in goalDegree until goalDegree + threshold) {
                                            currentLap += 1
                                        }
                                    }
                                    if (beforeDegree in goalDegree until goalDegree + threshold) {
                                        if (currentDegree in goalDegree - threshold until goalDegree) {
                                            currentLap -= 1
                                        }
                                    }
                                } else {

                                    if (beforeDegree in goalDegree - threshold until goalDegree) {
                                        if (currentDegree in goalDegree until goalDegree + threshold) {
                                            currentLap -= 1
                                        }
                                    }
                                    if (beforeDegree in goalDegree until goalDegree + threshold) {
                                        if (currentDegree in goalDegree - threshold until goalDegree) {
                                            currentLap += 1
                                        }
                                    }
                                }
                            }
                            270 -> {
                                if (!reverse) {
                                    if (beforeDegree in goalDegree - threshold until goalDegree) {
                                        if (currentDegree in goalDegree until goalDegree + threshold) {
                                            currentLap += 1
                                        }
                                    }
                                    if (beforeDegree in goalDegree until goalDegree + threshold) {
                                        if (currentDegree in goalDegree - threshold until goalDegree) {
                                            currentLap -= 1
                                        }
                                    }
                                } else {
                                    if (beforeDegree in goalDegree - threshold until goalDegree) {
                                        if (currentDegree in goalDegree until goalDegree + threshold) {
                                            currentLap -= 1
                                        }
                                    }
                                    if (beforeDegree in goalDegree until goalDegree + threshold) {
                                        if (currentDegree in goalDegree - threshold until goalDegree) {
                                            currentLap += 1
                                        }
                                    }
                                }
                            }
                        }

                        displayLap(currentLap, beforeLap, it, lap)

                        if (insidePolygon.contains(nowX, nowY) || !outsidePolygon.contains(nowX, nowY)) {
                            it.sendActionBar(text("レース場外に出てる可能性があります", TextColor.color(RED)))
                        }
                        beforeDegree = currentDegree

                        val manager: ScoreboardManager = Bukkit.getScoreboardManager()
                        val scoreboard = manager.newScoreboard
                        val objective: Objective = scoreboard.registerNewObjective(
                            "ranking", "dummy", text("デバッグモード", TextColor.color(YELLOW))
                        )
                        objective.displaySlot = DisplaySlot.SIDEBAR

                        val score = objective.getScore("§61位" + "   " + "§b${sender.name}")
                        score.score = 4
                        val degree = "現在のラップ$currentLap Lap 現在の角度$currentDegree°"

                        val displayDegree = objective.getScore(degree)
                        displayDegree.score = 2
                        val residue = objective.getScore("残り時間     ${180 - counter} 秒")
                        residue.score = 1
                        sender.scoreboard = scoreboard
                        counter++
                        if (counter > 180 || stop[raceID] == true) {
                            object : BukkitRunnable() {
                                override fun run() {
                                    sender.scoreboard.clearSlot(DisplaySlot.SIDEBAR)
                                }
                            }.runTaskLater(plugin!!, 40)
                            starting = false
                            cancel()
                        }
                    }
                }.runTaskTimer(plugin!!, 0, 20)
            }
        }.runTaskLater(plugin!!, 100)
    }

    @CommandPermission("RaceAssist.commands.race")
    @Subcommand("stop")
    @CommandCompletion("@RaceID")
    fun stop(sender: CommandSender, raceID: String) {
        if (getRaceCreator(raceID) != (sender as Player).uniqueId) {
            sender.sendMessage(text("レース作成者しか停止することはできません", TextColor.color(RED)))
        }
        stop[raceID] = true
        object : BukkitRunnable() {
            override fun run() {
                stop[raceID] = false
            }
        }.runTaskLater(plugin!!, 20)
    }

    @CommandPermission("RaceAssist.commands.race")
    @Subcommand("create")
    @CommandCompletion("@RaceID")
    fun create(sender: CommandSender, raceID: String) {
        if (getRaceCreator(raceID) != null) {
            sender.sendMessage("その名前のレース場は既に設定されています")
            return
        }
        val connection: Connection = Database.connection ?: return
        try {
            val statement =
                connection.prepareStatement("INSERT INTO RaceList(RaceID,Creator,Reverse,Lap,CentralXPoint,CentralYPoint,GoalDegree) VALUES (?,?,false,1,null,null,null)")
            statement.setString(1, raceID)
            statement.setString(2, (sender as Player).uniqueId.toString())
            statement.execute()
            statement.close()
        } catch (e: SQLException) {
            e.printStackTrace()
        }
        sender.sendMessage("レース場を作成しました")
    }
    @CommandPermission("RaceAssist.commands.race")
    @Subcommand("delete")
    @CommandCompletion("@RaceID")
    fun delete(sender: CommandSender, raceID: String) {
        val connection: Connection = Database.connection ?: return

        if (getRaceCreator(raceID) == null) {
            sender.sendMessage("レース場が設定されていません")
            return
        }
        if (getRaceCreator(raceID) != (sender as Player).uniqueId) {
            sender.sendMessage("レース場作成者が設定してください")
            return
        }
        try {
            val statement = connection.prepareStatement("DELETE FROM RaceList WHERE RaceID = ?")
            statement.setString(1, raceID)
            statement.execute()
            statement.close()
            val statement2 = connection.prepareStatement("DELETE FROM CircuitPoint WHERE RaceID = ?")
            statement2.setString(1, raceID)
            statement2.execute()
            statement2.close()
        } catch (e: SQLException) {
            e.printStackTrace()
        }
    }

    private fun displayLap(currentLap: Int?, beforeLap: Int?, player: Player, lap: Int) {
        if (currentLap == null || beforeLap == null) {
            return
        }
        if (currentLap > beforeLap) {
            player.showTitle(
                title(
                    (text(
                        "現在${currentLap} / ${lap}ラップです ", TextColor.color(GREEN)
                    )), text("ラップが一つ進みました", TextColor.color(BLUE))
                )
            )
            Bukkit.getScheduler().runTaskLater(plugin!!, Runnable {
                player.clearTitle()
            }, 40)
        } else if (currentLap < beforeLap) {
            player.showTitle(
                title(
                    text("現在${currentLap} / ${lap}ラップです ", TextColor.color(GREEN)),
                    text("ラップが一つ戻りました", TextColor.color(RED))
                )
            )
            Bukkit.getScheduler().runTaskLater(plugin!!, Runnable {
                player.clearTitle()
            }, 40)
        }
    }

    fun displayScoreboard(nowRankings: List<UUID>, totalDegree: HashMap<UUID, Int>, raceAudience: ArrayList<UUID>) {
        val manager: ScoreboardManager = Bukkit.getScoreboardManager()
        val scoreboard = manager.newScoreboard
        val objective: Objective =
            scoreboard.registerNewObjective("ranking", "dummy", text("現在のランキング", TextColor.color(YELLOW)))
        objective.displaySlot = DisplaySlot.SIDEBAR

        for (i in nowRankings.indices) {
            val playerName = Bukkit.getPlayer(nowRankings[i])?.name
            val score = objective.getScore("§6${i + 1}位" + "   " + "§b$playerName")
            score.score = nowRankings.size * 2 - 2 * i - 1
            val degree: String = if (totalDegree[Bukkit.getPlayer(nowRankings[i])?.uniqueId] == null) {
                "完走しました"
            } else {
                "現在のラップ${
                    totalDegree[Bukkit.getPlayer(nowRankings[i])?.uniqueId]?.div(360)?.let { ceil(it.toDouble()) }
                }Lap 現在の角度${
                    totalDegree[Bukkit.getPlayer(nowRankings[i])?.uniqueId]?.rem(
                        360
                    )
                }度"
            }
            val displayDegree = objective.getScore(degree)
            displayDegree.score = nowRankings.size * 2 - 2 * i - 2
        }
        raceAudience.forEach {
            Bukkit.getPlayer(it)?.scoreboard = scoreboard
        }
    }

    private fun decideRanking(totalDegree: HashMap<UUID, Int>): ArrayList<UUID> {
        val ranking = ArrayList<UUID>()
        val sorted = totalDegree.toList().sortedBy { (_, value) -> value }
        sorted.forEach {
            ranking.add(it.first)
        }
        ranking.reverse()
        return ranking
    }

    private fun getRaceCreator(raceID: String): UUID? {
        val connection: Connection = Database.connection ?: return null
        var creatorUUID: UUID? = null
        try {
            val statement = connection.prepareStatement("SELECT * FROM RaceList WHERE RaceID = ?")
            statement.setString(1, raceID)
            val rs: ResultSet = statement.executeQuery()
            while (rs.next()) {
                creatorUUID = UUID.fromString(rs.getString(2))
            }
        } catch (e: SQLException) {
            e.printStackTrace()
        }
        return creatorUUID
    }

    private fun getLapCount(raceID: String): Int {
        val connection: Connection = Database.connection ?: return 1
        var lapCount = 1
        try {
            val statement = connection.prepareStatement("SELECT * FROM RaceList WHERE RaceID = ?")
            statement.setString(1, raceID)
            val rs: ResultSet = statement.executeQuery()
            while (rs.next()) {
                lapCount = rs.getInt(4)
            }
        } catch (e: SQLException) {
            e.printStackTrace()
        }
        return lapCount
    }

    private fun getAllJockeys(raceID: String): ArrayList<OfflinePlayer>? {
        val jockeys: ArrayList<OfflinePlayer> = ArrayList()
        try {
            val connection: Connection = Database.connection ?: return null
            val statement = connection.prepareStatement(
                "SELECT * FROM PlayerList WHERE RaceID = ?"
            )
            statement.setString(1, raceID)
            val rs = statement.executeQuery()
            while (rs.next()) {
                jockeys.add(Bukkit.getOfflinePlayer(UUID.fromString(rs.getString(2))))
            }
            rs.close()
            statement.close()
        } catch (e: SQLException) {
            e.printStackTrace()
        }
        return jockeys
    }

    private fun getGoalDegree(raceID: String): Int? {
        try {
            val connection: Connection = Database.connection ?: return 0
            val statement = connection.prepareStatement("SELECT * FROM RaceList WHERE RaceID = ?")
            statement.setString(1, raceID)
            val rs: ResultSet = statement.executeQuery()
            if (rs.next()) {
                return rs.getInt(7)
            }
        } catch (e: SQLException) {
            e.printStackTrace()
        }
        return null
    }

    private fun getCircuitExist(raceID: String, inside: Boolean): Boolean {
        val connection: Connection = Database.connection ?: return false
        var raceExist = false
        try {
            val statement = connection.prepareStatement("SELECT * FROM CircuitPoint WHERE RaceID = ? AND Inside = ?")
            statement.setString(1, raceID)
            statement.setBoolean(2, inside)
            val rs: ResultSet = statement.executeQuery()
            if (rs.next()) {
                raceExist = true
            }
            rs.close()
            statement.close()
        } catch (e: SQLException) {
            e.printStackTrace()
        }
        return raceExist
    }

    private fun getPolygon(raceID: String, inside: Boolean): Polygon {
        val polygon = Polygon()
        val connection: Connection = Database.connection ?: return polygon
        try {
            val statement = connection.prepareStatement(
                "SELECT * FROM CircuitPoint WHERE RaceID = ? AND Inside = ?"
            )
            statement.setString(1, raceID)
            statement.setBoolean(2, inside)
            val rs = statement.executeQuery()
            while (rs.next()) {
                polygon.addPoint(rs.getInt(3), rs.getInt(4))
            }
        } catch (ex: SQLException) {
            ex.printStackTrace()
        }

        return polygon
    }

    companion object {

        var starting = false
        var stop = HashMap<String, Boolean>()

        fun getRaceCreator(raceID: String): UUID? {
            var uuid: UUID? = null
            try {
                val connection: Connection = Database.connection ?: return null
                val statement = connection.prepareStatement("SELECT * FROM RaceList WHERE RaceID = ?")
                statement.setString(1, raceID)
                val rs = statement.executeQuery()
                if (rs.next()) {
                    uuid = UUID.fromString(rs.getString(2))
                }
                rs.close()
                statement.close()
            } catch (ex: SQLException) {
                ex.printStackTrace()
            }
            return uuid
        }

        fun getCentralPoint(raceID: String, xPoint: Boolean): Int? {
            try {
                val connection: Connection = Database.connection ?: return 0
                val statement = connection.prepareStatement("SELECT * FROM RaceList WHERE RaceID = ?")
                statement.setString(1, raceID)
                val rs: ResultSet = statement.executeQuery()
                if (rs.next()) {
                    return if (xPoint) {
                        rs.getInt(5)
                    } else {
                        rs.getInt(6)
                    }
                }
            } catch (e: SQLException) {
                e.printStackTrace()
            }
            return null
        }

        fun getReverse(raceID: String): Boolean? {
            val connection: Connection = Database.connection ?: return null
            var reverse = false
            try {
                val statement = connection.prepareStatement("SELECT * FROM RaceList WHERE RaceID = ?")
                statement.setString(1, raceID)
                val rs: ResultSet = statement.executeQuery()
                while (rs.next()) {
                    reverse = rs.getBoolean(3)
                }
            } catch (e: SQLException) {
                e.printStackTrace()
            }
            return reverse
        }
    }
}
