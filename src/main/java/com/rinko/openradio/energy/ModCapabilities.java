package com.rinko.openradio.energy;

import com.rinko.openradio.item.BatteryItem;
import com.rinko.openradio.registry.ModItems;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * 把电池和电台接到 NeoForge Energy 上。
 *
 * <p>为什么值得费这一步：接了 {@code Capabilities.EnergyStorage.ITEM} 之后，
 * 社区里所有通用发电机、充电座、储能线缆都能直接给电池充电，
 * <b>我们一行对接代码都不用写</b>。自己造一套能量系统则等于把这些全部拒之门外。
 *
 * <p>电台也暴露了一份代理能力（转发到里面的电池），所以拿电台去充电座上是能充的。
 * 但 {@code canExtract} 为 false —— 不允许把电台当电池往外抽电。
 */
public final class ModCapabilities {

    public static void register(RegisterCapabilitiesEvent event) {
        event.registerItem(Capabilities.EnergyStorage.ITEM,
                (stack, context) -> new BatteryStorage(stack),
                ModItems.BATTERY.get());

        event.registerItem(Capabilities.EnergyStorage.ITEM,
                (stack, context) -> new RadioStorage(stack),
                ModItems.HANDHELD_RADIO.get());
    }

    /** 电池自身：可充可放。 */
    private static final class BatteryStorage implements IEnergyStorage {
        private final ItemStack stack;

        private BatteryStorage(ItemStack stack) {
            this.stack = stack;
        }

        @Override
        public int receiveEnergy(int amount, boolean simulate) {
            int capacity = BatteryItem.capacity();
            int have = BatteryItem.getEnergy(stack);
            int accepted = Math.min(capacity - have, Math.max(0, amount));
            if (accepted > 0 && !simulate) {
                BatteryItem.setEnergy(stack, have + accepted);
            }
            return Math.max(0, accepted);
        }

        @Override
        public int extractEnergy(int amount, boolean simulate) {
            int have = BatteryItem.getEnergy(stack);
            int taken = Math.min(have, Math.max(0, amount));
            if (taken > 0 && !simulate) {
                BatteryItem.setEnergy(stack, have - taken);
            }
            return Math.max(0, taken);
        }

        @Override
        public int getEnergyStored() {
            return BatteryItem.getEnergy(stack);
        }

        @Override
        public int getMaxEnergyStored() {
            return BatteryItem.capacity();
        }

        @Override
        public boolean canExtract() {
            return true;
        }

        @Override
        public boolean canReceive() {
            return true;
        }
    }

    /** 电台：代理到里面的电池，只进不出。 */
    private static final class RadioStorage implements IEnergyStorage {
        private final ItemStack stack;

        private RadioStorage(ItemStack stack) {
            this.stack = stack;
        }

        @Override
        public int receiveEnergy(int amount, boolean simulate) {
            return simulate
                    ? Math.min(RadioEnergy.capacity(stack) - RadioEnergy.stored(stack), Math.max(0, amount))
                    : RadioEnergy.charge(stack, amount);
        }

        @Override
        public int extractEnergy(int amount, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return RadioEnergy.stored(stack);
        }

        @Override
        public int getMaxEnergyStored() {
            return RadioEnergy.capacity(stack);
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return RadioEnergy.hasBattery(stack);
        }
    }

    private ModCapabilities() {
    }
}
