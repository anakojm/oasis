package com.lambda.modules

import com.lambda.Oasis
import kotlinx.coroutines.launch
import net.minecraft.util.EnumFacing
import net.minecraft.block.BlockSign
import net.minecraft.util.math.BlockPos
import com.lambda.client.util.TickTimer
import com.lambda.client.module.Category
import net.minecraft.block.BlockHorizontal
import net.minecraft.util.math.AxisAlignedBB
import net.minecraft.tileentity.TileEntitySign
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.util.color.ColorHolder
import com.lambda.client.plugin.api.PluginModule
import com.lambda.client.util.graphics.ESPRenderer
import com.lambda.client.util.threads.defaultScope
import com.lambda.client.util.threads.safeListener
import com.lambda.client.util.graphics.GeometryMasks
import com.lambda.client.event.events.RenderWorldEvent


/**
 * @author 0xTas <root@0xTas.dev>
 */
internal object OldSigns : PluginModule(
    name = "OldSigns",
    description = "Highlight signs placed prior to Minecraft version 1.8",
    category = Category.RENDER,
    pluginMain = Oasis
) {
    private val mode by setting("Render Mode", value = RenderMode.SIGN)
    private val range by setting("Chunk Range", value = 7, range = 1..16, step = 1)
    private val filled by setting("Filled", true, description = "Draw fill")
    private val outline by setting("Outline", true, description = "Draw outline")
    private val tracer by setting("Tracer", true, description = "Draw tracer")
    private val color by setting("Color", ColorHolder(43, 207, 155), false)
    private val aFilled by setting("Fill Opacity", 42, 0..255, 1, { filled })
    private val aOutline by setting("Outline Opacity", 69, 0..255, 1, { outline })
    private val aTracer by setting("Tracer Opacity", 69, 0..255, 1, { tracer })

    private val timer = TickTimer()
    private var renderer: ESPRenderer? = null

    private enum class RenderMode {
        BLOCK, BASE, SIGN
    }


    private fun getRenderer(): ESPRenderer? {
        val renderer: ESPRenderer?
        try {
            renderer = ESPRenderer()
        } catch (e: ExceptionInInitializerError) {
            return null
        }
        return renderer
    }

    private fun isOld(metadata: String): Boolean {
        return !metadata.contains("""{\"extra\":[{\"text\":""")
    }

    private fun SafeClientEvent.updateRenderer() {
        renderer?.aFilled = if (filled) aFilled else 0
        renderer?.aOutline = if (outline) aOutline else 0
        renderer?.aTracer = if (tracer) aTracer else 0

        defaultScope.launch {
            val cache = ArrayList<Triple<AxisAlignedBB, ColorHolder, Int>>()

            val playerPos = player.position

            val chunks = range * 16
            val startX = playerPos.x - chunks
            val endX = playerPos.x + chunks
            val startY = playerPos.y - chunks
            val endY = playerPos.y + chunks
            val startZ = playerPos.z - chunks
            val endZ = playerPos.z + chunks

            for (x in startX..endX) {
                for (y in startY..endY) {
                    for (z in startZ..endZ) {
                        val blockPos = BlockPos(x,y,z)
                        val state = world.getBlockState(blockPos)
                        val block = state.block

                        if (block !is BlockSign) continue

                        val facing: EnumFacing? = try {
                            Companion.mc.player.world.getBlockState(blockPos).getValue(BlockHorizontal.FACING)
                        }catch (err: IllegalArgumentException) {
                            null
                        }

                        val quad = when (mode) {
                            RenderMode.BASE -> {
                                val side = when (facing) {
                                    EnumFacing.EAST -> GeometryMasks.Quad.WEST
                                    EnumFacing.WEST -> GeometryMasks.Quad.EAST
                                    EnumFacing.NORTH -> GeometryMasks.Quad.SOUTH
                                    EnumFacing.SOUTH -> GeometryMasks.Quad.NORTH
                                    else -> GeometryMasks.Quad.DOWN
                                }
                                side
                            }
                            else -> GeometryMasks.Quad.ALL
                        }

                        val entity = world.getTileEntity(blockPos)
                        if (entity !is TileEntitySign) continue
                        if (entity.signText.none {
                                it.unformattedText.trim().isNotEmpty()
                            }) continue

                        val rot = entity.blockMetadata
                        val nbt = entity.updatePacket?.nbtCompound ?: continue

                        val offsetBB = getSignBB(blockPos, facing, rot)

                        if (isOld("$nbt")) {
                            cache.add(Triple(offsetBB, color, quad))
                        }
                    }
                }
            }
            renderer?.replaceAll(cache)
        }
    }

    private fun getSignBB(pos: BlockPos, facing: EnumFacing?, rot: Int): AxisAlignedBB {
        val signWidth = 0.5
        var signHeight = 0.75
        val signDepth = 0.07

        var standing = false

        var posX = pos.x.toDouble()
        var posY = pos.y.toDouble()
        var posZ = pos.z.toDouble()

        val minX: Double
        val minY: Double
        val minZ: Double
        val maxX: Double
        val maxY: Double
        val maxZ: Double

        when (mode) {
            RenderMode.SIGN -> {
                when (facing) {
                    EnumFacing.NORTH -> {
                        posZ += 0.42
                        signHeight /= 2.7
                    }
                    EnumFacing.SOUTH -> {
                        posZ -= 0.42
                        signHeight /= 2.7
                    }
                    EnumFacing.EAST -> {
                        posX -= 0.42
                        signHeight /= 2.7
                    }
                    EnumFacing.WEST -> {
                        posX += 0.42
                        signHeight /= 2.7
                    }
                    else -> {
                        posY += 0.32
                        signHeight /= 3
                        standing = true
                    }
                }
            }
            RenderMode.BLOCK, RenderMode.BASE -> return AxisAlignedBB(pos)
        }

        if (standing) {
            when (rot) {
                0, 8 -> { // North & South
                    minX = posX + 0.5 - signWidth
                    minY = posY + 0.5 - signHeight
                    minZ = posZ + 0.5 - signDepth
                    maxX = posX + 0.5 + signWidth
                    maxY = posY + 0.5 + signHeight
                    maxZ = posZ + 0.5 + signDepth
                }
                4, 12 -> { // East & West
                    minX = posX + 0.5 - signDepth
                    minY = posY + 0.5 - signHeight
                    minZ = posZ + 0.5 - signWidth
                    maxX = posX + 0.5 + signDepth
                    maxY = posY + 0.5 + signHeight
                    maxZ = posZ + 0.5 + signWidth
                }
                else -> {
                    return AxisAlignedBB(pos)
                }
            }
        } else {
            if (facing == EnumFacing.NORTH || facing == EnumFacing.SOUTH) {
                minX = posX + 0.5 - signWidth
                minY = posY + 0.5 - signHeight
                minZ = posZ + 0.5 - signDepth
                maxX = posX + 0.5 + signWidth
                maxY = posY + 0.5 + signHeight
                maxZ = posZ + 0.5 + signDepth
            } else {
                minX = posX + 0.5 - signDepth
                minY = posY + 0.5 - signHeight
                minZ = posZ + 0.5 - signWidth
                maxX = posX + 0.5 + signDepth
                maxY = posY + 0.5 + signHeight
                maxZ = posZ + 0.5 + signWidth
            }
        }

        return AxisAlignedBB(minX, minY, minZ, maxX, maxY, maxZ)
    }


    init {
        safeListener<RenderWorldEvent> {
            if (renderer == null) {
                renderer = getRenderer()
            } else {
                if (timer.tick(133L)) { // Avoid running this on a tick
                    updateRenderer()
                }
                renderer?.render(false)
            }
        }
    }
}