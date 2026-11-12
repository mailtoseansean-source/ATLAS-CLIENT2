package dev.atlasclient.core.nbt;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Small, dependency-free implementation of Minecraft's NBT binary format, used for schematic and
 * region files so they can be parsed and tested without the game. Values map to Java types:
 * byte→Byte, short→Short, int→Integer, long→Long, float→Float, double→Double, byte[]→byte[],
 * string→String, list→{@link ListTag}, compound→{@link Compound}, int[]→int[], long[]→long[].
 */
public final class Nbt {
    public static final int END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6,
            BYTE_ARRAY = 7, STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;

    /** Defensive limits so a malicious or corrupt file cannot exhaust memory. */
    private static final int MAX_DEPTH = 512;
    private static final int MAX_ARRAY = 64 * 1024 * 1024;

    private Nbt() { }

    /** Root tag with its name. */
    public record Named(String name, Compound tag) { }

    public static final class ListTag extends ArrayList<Object> {
        private int elementType;

        public ListTag(int elementType) { this.elementType = elementType; }

        public int elementType() { return elementType; }

        public Compound compound(int i) { return (Compound) get(i); }

        public int intAt(int i) { return ((Number) get(i)).intValue(); }

        public String stringAt(int i) { return (String) get(i); }

        @Override
        public boolean add(Object o) {
            int t = typeOf(o);
            if (isEmpty() && (elementType == END || elementType == t)) {
                elementType = t;
            } else if (t != elementType) {
                throw new IllegalArgumentException("list element type mismatch: " + t + " vs " + elementType);
            }
            return super.add(o);
        }
    }

    public static final class Compound {
        private final Map<String, Object> map = new LinkedHashMap<>();

        public Set<String> keys() { return map.keySet(); }
        public boolean contains(String key) { return map.containsKey(key); }
        public boolean contains(String key, int type) {
            Object v = map.get(key);
            return v != null && typeOf(v) == type;
        }
        public Object get(String key) { return map.get(key); }
        public int size() { return map.size(); }

        public Compound put(String key, Object value) {
            typeOf(value); // validate
            map.put(key, value);
            return this;
        }

        public Compound putByte(String k, int v) { return put(k, (byte) v); }
        public Compound putShort(String k, int v) { return put(k, (short) v); }
        public Compound putInt(String k, int v) { return put(k, v); }
        public Compound putLong(String k, long v) { return put(k, v); }
        public Compound putString(String k, String v) { return put(k, v); }

        public int getInt(String key, int def) {
            Object v = map.get(key);
            return v instanceof Number n && !(v instanceof Float) && !(v instanceof Double) ? n.intValue() : def;
        }

        public long getLong(String key, long def) {
            Object v = map.get(key);
            return v instanceof Number n ? n.longValue() : def;
        }

        public String getString(String key, String def) {
            Object v = map.get(key);
            return v instanceof String s ? s : def;
        }

        public Compound getCompound(String key) {
            Object v = map.get(key);
            return v instanceof Compound c ? c : null;
        }

        public ListTag getList(String key) {
            Object v = map.get(key);
            return v instanceof ListTag l ? l : null;
        }

        public byte[] getByteArray(String key) {
            Object v = map.get(key);
            return v instanceof byte[] b ? b : null;
        }

        public int[] getIntArray(String key) {
            Object v = map.get(key);
            return v instanceof int[] a ? a : null;
        }

        public long[] getLongArray(String key) {
            Object v = map.get(key);
            return v instanceof long[] a ? a : null;
        }
    }

    public static int typeOf(Object v) {
        if (v instanceof Byte) return BYTE;
        if (v instanceof Short) return SHORT;
        if (v instanceof Integer) return INT;
        if (v instanceof Long) return LONG;
        if (v instanceof Float) return FLOAT;
        if (v instanceof Double) return DOUBLE;
        if (v instanceof byte[]) return BYTE_ARRAY;
        if (v instanceof String) return STRING;
        if (v instanceof ListTag) return LIST;
        if (v instanceof Compound) return COMPOUND;
        if (v instanceof int[]) return INT_ARRAY;
        if (v instanceof long[]) return LONG_ARRAY;
        throw new IllegalArgumentException("Unsupported NBT value: " + (v == null ? "null" : v.getClass()));
    }

    // ---------------------------------------------------------------- reading

    /** Reads a file that may be gzip-compressed, zlib-compressed or uncompressed. */
    public static Named readFile(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return readAuto(in);
        }
    }

    public static Named readAuto(InputStream raw) throws IOException {
        PushbackInputStream in = new PushbackInputStream(new BufferedInputStream(raw), 2);
        int b0 = in.read();
        int b1 = in.read();
        if (b0 < 0 || b1 < 0) {
            throw new IOException("File is empty");
        }
        in.unread(b1);
        in.unread(b0);
        InputStream decoded;
        if (b0 == 0x1F && b1 == 0x8B) {
            decoded = new GZIPInputStream(in);
        } else if (b0 == 0x78) {
            decoded = new InflaterInputStream(in);
        } else {
            decoded = in;
        }
        return read(new DataInputStream(new BufferedInputStream(decoded)));
    }

    public static Named readBytes(byte[] data) throws IOException {
        return readAuto(new ByteArrayInputStream(data));
    }

    public static Named read(DataInput in) throws IOException {
        int type = in.readUnsignedByte();
        if (type != COMPOUND) {
            throw new IOException("Root tag is not a compound (type " + type + ")");
        }
        String name = in.readUTF();
        return new Named(name, (Compound) readPayload(in, COMPOUND, 0));
    }

    private static Object readPayload(DataInput in, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT nesting too deep");
        }
        switch (type) {
            case BYTE: return in.readByte();
            case SHORT: return in.readShort();
            case INT: return in.readInt();
            case LONG: return in.readLong();
            case FLOAT: return in.readFloat();
            case DOUBLE: return in.readDouble();
            case BYTE_ARRAY: {
                int len = checkedLength(in.readInt());
                byte[] b = new byte[len];
                in.readFully(b);
                return b;
            }
            case STRING: return in.readUTF();
            case LIST: {
                int elemType = in.readUnsignedByte();
                int len = in.readInt();
                if (len < 0) {
                    len = 0;
                }
                checkedLength(len);
                ListTag list = new ListTag(elemType);
                for (int i = 0; i < len; i++) {
                    list.add(readPayload(in, elemType, depth + 1));
                }
                return list;
            }
            case COMPOUND: {
                Compound c = new Compound();
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == END) {
                        break;
                    }
                    String key = in.readUTF();
                    c.put(key, readPayload(in, t, depth + 1));
                }
                return c;
            }
            case INT_ARRAY: {
                int len = checkedLength(in.readInt());
                int[] a = new int[len];
                for (int i = 0; i < len; i++) a[i] = in.readInt();
                return a;
            }
            case LONG_ARRAY: {
                int len = checkedLength(in.readInt());
                long[] a = new long[len];
                for (int i = 0; i < len; i++) a[i] = in.readLong();
                return a;
            }
            default:
                throw new IOException("Unknown NBT tag type " + type);
        }
    }

    private static int checkedLength(int len) throws IOException {
        if (len < 0 || len > MAX_ARRAY) {
            throw new IOException("Invalid NBT array length " + len);
        }
        return len;
    }

    // ---------------------------------------------------------------- writing

    public static void writeGzipFile(Path file, String rootName, Compound root) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            write(new DataOutputStream(out), rootName, root);
        }
    }

    public static byte[] toGzipBytes(String rootName, Compound root) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (OutputStream out = new GZIPOutputStream(bos)) {
            DataOutputStream dos = new DataOutputStream(out);
            write(dos, rootName, root);
            dos.flush();
        }
        return bos.toByteArray();
    }

    public static byte[] toUncompressedBytes(String rootName, Compound root) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        write(dos, rootName, root);
        dos.flush();
        return bos.toByteArray();
    }

    public static void write(DataOutput out, String rootName, Compound root) throws IOException {
        out.writeByte(COMPOUND);
        out.writeUTF(rootName);
        writePayload(out, root);
    }

    private static void writePayload(DataOutput out, Object v) throws IOException {
        switch (typeOf(v)) {
            case BYTE -> out.writeByte((Byte) v);
            case SHORT -> out.writeShort((Short) v);
            case INT -> out.writeInt((Integer) v);
            case LONG -> out.writeLong((Long) v);
            case FLOAT -> out.writeFloat((Float) v);
            case DOUBLE -> out.writeDouble((Double) v);
            case BYTE_ARRAY -> {
                byte[] b = (byte[]) v;
                out.writeInt(b.length);
                out.write(b);
            }
            case STRING -> out.writeUTF((String) v);
            case LIST -> {
                ListTag l = (ListTag) v;
                out.writeByte(l.isEmpty() ? END : l.elementType());
                out.writeInt(l.size());
                for (Object o : l) {
                    writePayload(out, o);
                }
            }
            case COMPOUND -> {
                Compound c = (Compound) v;
                for (String key : c.keys()) {
                    Object child = c.get(key);
                    out.writeByte(typeOf(child));
                    out.writeUTF(key);
                    writePayload(out, child);
                }
                out.writeByte(END);
            }
            case INT_ARRAY -> {
                int[] a = (int[]) v;
                out.writeInt(a.length);
                for (int i : a) out.writeInt(i);
            }
            case LONG_ARRAY -> {
                long[] a = (long[]) v;
                out.writeInt(a.length);
                for (long l : a) out.writeLong(l);
            }
            default -> throw new IOException("unreachable");
        }
    }

    /** Convenience: builds a list of compounds. */
    public static ListTag compoundList(List<Compound> items) {
        ListTag list = new ListTag(COMPOUND);
        list.addAll(items);
        return list;
    }
}
