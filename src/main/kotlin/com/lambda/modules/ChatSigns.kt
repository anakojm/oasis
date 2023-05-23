package com.lambda.modules

import com.lambda.Oasis
import kotlinx.coroutines.launch
import net.minecraft.block.BlockSign
import net.minecraft.client.Minecraft
import net.minecraft.util.math.BlockPos
import com.lambda.client.module.Category
import com.lambda.client.event.LambdaEventBus
import net.minecraft.tileentity.TileEntitySign
import com.lambda.client.plugin.api.PluginModule
import com.lambda.client.util.threads.defaultScope
import com.lambda.client.util.threads.safeListener
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.event.events.ConnectionEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent


/**
 * @author 0xTas <root@0xTas.dev>
 */
internal object ChatSigns : PluginModule(
    name = "ChatSigns",
    description = "Read nearby signs in your chat",
    category = Category.CHAT,
    pluginMain = Oasis
){
    private val mc = Minecraft.getMinecraft()
    private val posSet = hashSetOf<BlockPos>()
    private val showCoords by setting("Show Coordinates", value = true, description = "Include sign coordinates")
    private val tickRate by setting(
        "Tick Rate",
        value = 2,
        range = 2..10,
        step = 2,
        description = "Increase if needed"
    )

    private var ticksEnabled = 0


    private fun getSurroundingSigns(playerPos: BlockPos): HashSet<BlockPos> {
        // Get every sign in a 7x7 chunk square centered on the player.
        val startX = playerPos.x - 112
        val startY = 0
        val startZ = playerPos.z - 112
        val endX = playerPos.x + 112
        val endY = mc.player.world.height
        val endZ = playerPos.z + 112

        val signList = HashSet<BlockPos>()
        for (x in startX..endX) {
            for (y in startY..endY) {
                for (z in startZ..endZ) {
                    val pos = BlockPos(x, y, z)

                    if (mc.player.world.getBlockState(pos).block is BlockSign) {
                        signList.add(pos)
                    }
                }
            }
        }

        return signList
    }

    private fun chatSign(sign: BlockPos) {
        val signEntity = mc.world.getTileEntity(sign)

        if (signEntity is TileEntitySign) {
            val textObjects = signEntity.signText
            val signX = sign.x
            val signY = sign.y
            val signZ = sign.z

            var shouldRet = true
            for (text in textObjects) {
                if (text.unformattedText.trim().isNotEmpty())
                    shouldRet = false
            }
            if (shouldRet) return

            var textOnSignForChat = ""
            for (text in textObjects) {
                textOnSignForChat += text.formattedText.replace("§r", " ")
            }
            val chatData = if (showCoords) {
                "§8[§f${signX}, ${signY}, ${signZ}§8]§f: §o\"${textOnSignForChat}\""
            } else {
                "§o\"${textOnSignForChat}\""
            }

            posSet.add(sign)
            MessageSendHelper.sendChatMessage("§8[${Oasis.rCC()}☯§8] §f$chatData")
        }
    }

    private fun signAlreadyLogged(signPos: BlockPos): Boolean {
        return posSet.contains(signPos)
    }

    init {
        onDisable {
            posSet.clear()
        }

        LambdaEventBus.subscribe(this)
        safeListener<ConnectionEvent.Disconnect> {
            posSet.clear()
        }

        safeListener<ClientTickEvent> {
            if (it.phase != TickEvent.Phase.START) return@safeListener
            ticksEnabled++

            if (ticksEnabled % tickRate == 0) {
                defaultScope.launch {
                    if (isEnabled && mc.player != null && mc.player.world != null) {
                        val surroundings = getSurroundingSigns(ChatSigns.mc.player.position)

                        if (surroundings.isNotEmpty()) {
                            for (sign in surroundings) {
                                if (signAlreadyLogged(sign))
                                    continue
                                if (!isDisabled) chatSign(sign)
                            }
                        }
                    }
                }
            }
        }
    }
}