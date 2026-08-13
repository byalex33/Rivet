package dev.rivet;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class SnapshotCodec {
    private static final int VERSION = 1;

    private SnapshotCodec() {
    }

    static byte[] encode(SnapshotState state) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes);
             BukkitObjectOutputStream output = new BukkitObjectOutputStream(gzip)) {
            output.writeInt(VERSION);
            writeItems(output, state.inventory());
            writeItems(output, state.armour());
            output.writeObject(state.offhand());
            output.writeInt(state.xpLevel());
            output.writeFloat(state.xpProgress());
            output.writeDouble(state.health());
            output.writeInt(state.hunger());
            output.writeFloat(state.saturation());
        }
        return bytes.toByteArray();
    }

    static SnapshotState decode(byte[] encoded) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(encoded));
             BukkitObjectInputStream input = new BukkitObjectInputStream(gzip)) {
            int version = input.readInt();
            if (version != VERSION) {
                throw new IOException("Unsupported snapshot data version " + version);
            }
            ItemStack[] inventory = readItems(input);
            ItemStack[] armour = readItems(input);
            ItemStack offhand = readItem(input);
            return new SnapshotState(inventory, armour, offhand, input.readInt(),
                input.readFloat(), input.readDouble(), input.readInt(), input.readFloat());
        } catch (ClassNotFoundException exception) {
            throw new IOException("Snapshot contains an unavailable item type", exception);
        }
    }

    static String hash(byte[] encoded) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void writeItems(BukkitObjectOutputStream output, ItemStack[] items)
        throws IOException {
        output.writeInt(items.length);
        for (ItemStack item : items) {
            output.writeObject(item);
        }
    }

    private static ItemStack[] readItems(BukkitObjectInputStream input)
        throws IOException, ClassNotFoundException {
        int length = input.readInt();
        if (length < 0 || length > 100) {
            throw new IOException("Invalid saved inventory length " + length);
        }
        ItemStack[] items = new ItemStack[length];
        for (int index = 0; index < length; index++) {
            items[index] = readItem(input);
        }
        return items;
    }

    private static ItemStack readItem(BukkitObjectInputStream input)
        throws IOException, ClassNotFoundException {
        Object item = input.readObject();
        if (item != null && !(item instanceof ItemStack)) {
            throw new IOException("Snapshot contained a non-item value");
        }
        return (ItemStack) item;
    }
}
