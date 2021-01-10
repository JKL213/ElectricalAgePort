package mods.eln.simplenode.energyconverter

import mods.eln.Eln
import mods.eln.misc.Direction
import mods.eln.misc.LRDU
import mods.eln.node.simple.SimpleNode
import mods.eln.sim.ElectricalLoad
import mods.eln.sim.IProcess
import mods.eln.sim.ThermalLoad
import mods.eln.sim.mna.misc.MnaConst
import mods.eln.sim.nbt.NbtElectricalLoad
import mods.eln.sim.nbt.NbtResistor
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.nbt.NBTTagCompound
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

class EnergyConverterElnToOtherNode : SimpleNode() {
    var descriptor: EnergyConverterElnToOtherDescriptor? = null
    var load = NbtElectricalLoad("load")
    var powerInResistor = NbtResistor("powerInResistor", load, null)
    var electricalProcess = ElectricalProcess()
    var energyBuffer = 0.0
    var energyBufferMax = 0.0
    var inStdVoltage = 0.0
    var inPowerMax = 0.0
    var selectedPower = 0.0
    var ic2tier = 1

    init {
        powerInResistor.r = MnaConst.highImpedance
    }

    override fun setDescriptorKey(key: String) {
        super.setDescriptorKey(key)
        descriptor = getDescriptor() as EnergyConverterElnToOtherDescriptor
    }

    override fun getSideConnectionMask(directionA: Direction, lrduA: LRDU): Int {
        return maskElectricalPower
    }

    override fun getThermalLoad(directionA: Direction, lrduA: LRDU, mask: Int): ThermalLoad? {
        return null
    }

    override fun getElectricalLoad(directionB: Direction, lrduB: LRDU, mask: Int): ElectricalLoad {
        return load
    }

    override fun initialize() {
        electricalLoadList.add(load)
        electricalComponentList.add(powerInResistor)
        electricalProcessList.add(electricalProcess)
        Eln.applySmallRs(load)
        load.setAsPrivate()
        descriptor!!.applyTo(this)
        connect()
    }

    inner class ElectricalProcess : IProcess {
        var timeout = 0.0
        override fun process(time: Double) {
            var power = powerInResistor.p
            if (!power.isFinite()) power = 0.0
            energyBuffer += power * time
            timeout -= time
            if (timeout < 0) {
                timeout = 0.05
                if (energyBufferMax - energyBuffer <= 0) {
                    powerInResistor.highImpedance()
                } else {
                    if (selectedPower <= 0.0) {
                        powerInResistor.r = MnaConst.highImpedance
                    } else {
                        powerInResistor.r = max(Eln.getSmallRs(), load.u * load.u / selectedPower)
                    }
                }
            }
        }
    }

    /**
     * @param conversionRatio Conversion Ratio (Input mod conversion ratio here)
     * @return energy buffer in that mod unit (Units are RF/EU/OC, etc.)
     */
    fun availableEnergyInModUnits(conversionRatio: Double): Double {
        return energyBuffer * conversionRatio
    }

    /**
     * @param otherModEnergy Energy from other mod (RF/EU/OC, etc.) to draw
     * @param conversionRatio Conversion Ratio (Input mod conversion ratio here)
     * @return Watts drawn from the energy buffer
     */
    fun drawEnergy(otherModEnergy: Double, conversionRatio: Double): Double {
        val drawEnergy = otherModEnergy / conversionRatio
        energyBuffer -= drawEnergy
        return drawEnergy
    }

    /**
     * @param maximumModUnits The maximum number of mod units (EU/RF/OC/etc.) that can be drawn
     * @param conversionRatio Conversion Ratio (Input mod conversion ratio here)
     * @return maximum Mod Units that can be drawn
     */
    fun availableEnergyInModUnitsWithLimit(maximumModUnits: Double, conversionRatio: Double): Double {
        return min(availableEnergyInModUnits(conversionRatio), maximumModUnits * conversionRatio) / conversionRatio
    }

    override fun writeToNBT(nbt: NBTTagCompound) {
        super.writeToNBT(nbt)
        nbt.setDouble("energyBuffer", energyBuffer)
        nbt.setDouble("selectedPower", selectedPower)
        nbt.setInteger("ic2tier", ic2tier)
    }

    override fun readFromNBT(nbt: NBTTagCompound) {
        super.readFromNBT(nbt)
        energyBuffer = nbt.getDouble("energyBuffer")
        selectedPower = nbt.getDouble("selectedPower")
        ic2tier = nbt.getInteger("ic2tier")
    }

    override fun hasGui(side: Direction): Boolean {
        return true
    }

    override fun publishSerialize(stream: DataOutputStream) {
        super.publishSerialize(stream)
        try {
            stream.writeDouble(selectedPower)
            stream.writeInt(ic2tier)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    override fun networkUnserialize(stream: DataInputStream, player: EntityPlayerMP) {
        try {
            when (stream.readByte()) {
                NetworkType.SET_POWER.id -> {
                    val power = stream.readDouble()
                    if (power in 0.0 .. 120_000.0) {
                        selectedPower = power
                    }
                    needPublish()
                }
                NetworkType.SET_IC2_TIER.id -> {
                    val tier = stream.readInt()
                    if (tier in 1..4) {
                        ic2tier = tier
                        needPublish()
                    }
                }
                else -> {}
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    override fun getNodeUuid(): String {
        return nodeUuidStatic
    }

    companion object {
        @JvmStatic
        val nodeUuidStatic = "ElnToOther"
    }
}

enum class NetworkType(val id: Byte) {
    SET_POWER(1),
    SET_IC2_TIER(2)
}

enum class IC2Tiers(val tier: Int, val euPerTick: Int) {
    TIER_1(1, 32),
    TIER_2(2, 128),
    TIER_3(3, 512),
    TIER_4(4, 2048)
    // Yes, there is a Tier 5, no it does not work.
    // It caps at around 2kEU/t with an infinite energy sink, after that it voids energy
}