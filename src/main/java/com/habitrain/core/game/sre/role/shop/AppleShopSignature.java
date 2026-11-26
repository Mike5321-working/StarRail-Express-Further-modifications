package com.habitrain.core.game.sre.role.shop;

import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/** Slot identity includes stack components, not just the underlying vanilla item ID. */
public record AppleShopSignature(ItemStack stack, int basePrice, String type, String currency, int weight) {
    public static AppleShopSignature of(ShopEntry entry) {
        return of(entry, entry.price());
    }

    public static AppleShopSignature of(ShopEntry entry, int basePrice) {
        return new AppleShopSignature(entry.stack().copy(), basePrice, entry.type().name(),
                entry.currency().name(), entry.weight());
    }

    /** Zero-allocation match against a live ShopEntry without copying its ItemStack. */
    public boolean matches(ShopEntry entry) {
        return matches(entry, entry.price());
    }

    public boolean matches(ShopEntry entry, int entryBasePrice) {
        if (entry == null || basePrice != entryBasePrice || weight != entry.weight()) {
            return false;
        }
        ItemStack entryStack = entry.stack();
        return stack.getCount() == entryStack.getCount()
                && type.equals(entry.type().name())
                && currency.equals(entry.currency().name())
                && ItemStack.isSameItemSameComponents(stack, entryStack);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AppleShopSignature that && basePrice == that.basePrice
                && weight == that.weight && type.equals(that.type) && currency.equals(that.currency)
                && stack.getCount() == that.stack.getCount()
                && ItemStack.isSameItemSameComponents(stack, that.stack);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(basePrice, type, currency, weight, stack.getCount(),
                ItemStack.hashItemAndComponents(stack));
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.put("stack", stack.save(registries));
        tag.putInt("base", basePrice);
        tag.putString("type", type);
        tag.putString("currency", currency);
        tag.putInt("weight", weight);
        return tag;
    }

    public static AppleShopSignature load(CompoundTag tag, HolderLookup.Provider registries) {
        return new AppleShopSignature(ItemStack.parseOptional(registries, tag.getCompound("stack")),
                tag.getInt("base"), tag.getString("type"), tag.getString("currency"), tag.getInt("weight"));
    }
}
