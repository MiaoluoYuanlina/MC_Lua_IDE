package wiki.ica.xiaomiao.mcluaide.client;

import com.sun.jna.*;
import com.sun.jna.ptr.*;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Exact HKCU key only; DPAPI current-user encryption, with no plaintext fallback or process arguments. */
final class LuaAiSecretStore {
    static final String KEY = "Software\\MC_Lua_IDE\\AI";
    private static final String VALUE = "EncryptedConfigurationV1";
    private static final Pointer HKCU = new Pointer((long) (int) 0x80000001);
    private final String path;
    LuaAiSecretStore() { this(KEY); }
    LuaAiSecretStore(String path) {
        if (!path.equals(KEY) && !path.matches("Software\\\\MC_Lua_IDE\\\\AI_Test_[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Unsafe registry path");
        this.path = path;
    }
    static boolean supported() { return System.getProperty("os.name", "").startsWith("Windows"); }
    interface Registry extends StdCallLibrary {
        int RegOpenKeyExW(Pointer root, WString sub, int options, int access, PointerByReference result);
        int RegCreateKeyExW(Pointer root, WString sub, int reserved, WString clazz, int options, int access, Pointer attributes, PointerByReference result, IntByReference disposition);
        int RegQueryValueExW(Pointer key, WString name, Pointer reserved, IntByReference type, byte[] data, IntByReference size);
        int RegSetValueExW(Pointer key, WString name, int reserved, int type, byte[] data, int size);
        int RegCloseKey(Pointer key);
        int RegDeleteKeyW(Pointer root, WString sub);
    }
    interface Crypt extends StdCallLibrary {
        boolean CryptProtectData(Blob in, WString description, Pointer entropy, Pointer reserved, Pointer prompt, int flags, Blob out);
        boolean CryptUnprotectData(Blob in, PointerByReference description, Pointer entropy, Pointer reserved, Pointer prompt, int flags, Blob out);
    }
    interface Kernel extends StdCallLibrary { Pointer LocalFree(Pointer memory); }
    @Structure.FieldOrder({"size", "data"})
    public static class Blob extends Structure { public int size; public Pointer data; }
    private static Registry registry() throws IOException { if (!supported()) throw new IOException("此平台不支持 Windows 注册表/DPAPI；AI 保持关闭"); return Native.load("Advapi32", Registry.class); }
    LuaAiSettings load() throws IOException {
        Registry reg = registry(); PointerByReference handle = new PointerByReference();
        int status = reg.RegOpenKeyExW(HKCU, new WString(path), 0, 1, handle);
        if (status == 2) return new LuaAiSettings(); check(status);
        try {
            IntByReference size = new IntByReference(), type = new IntByReference();
            status = reg.RegQueryValueExW(handle.getValue(), new WString(VALUE), null, type, null, size);
            if (status == 2) return new LuaAiSettings(); check(status);
            if (type.getValue() != 3 || size.getValue() < 1 || size.getValue() > 131072) throw new IOException("AI 加密配置格式不正确");
            byte[] encrypted = new byte[size.getValue()]; check(reg.RegQueryValueExW(handle.getValue(), new WString(VALUE), null, type, encrypted, size));
            byte[] plain = transform(Arrays.copyOf(encrypted, size.getValue()), false);
            try { return LuaAiSettings.decode(new String(plain, StandardCharsets.UTF_8)); }
            catch (RuntimeException error) { throw new IOException("AI 加密配置损坏，未启用自动分析"); }
            finally { Arrays.fill(plain, (byte) 0); }
        } finally { reg.RegCloseKey(handle.getValue()); }
    }
    void save(LuaAiSettings settings) throws IOException {
        for (var api : settings.apis.values()) api.validate(false);
        if (settings.enabled) settings.api().validate(true);
        byte[] plain = LuaAiSettings.JSON.toJson(settings).getBytes(StandardCharsets.UTF_8), encrypted;
        try { encrypted = transform(plain, true); } finally { Arrays.fill(plain, (byte) 0); }
        Registry reg = registry(); PointerByReference handle = new PointerByReference();
        check(reg.RegCreateKeyExW(HKCU, new WString(path), 0, null, 0, 2, null, handle, null));
        try { check(reg.RegSetValueExW(handle.getValue(), new WString(VALUE), 0, 3, encrypted, encrypted.length)); }
        finally { reg.RegCloseKey(handle.getValue()); }
    }
    private static byte[] transform(byte[] input, boolean encrypt) throws IOException {
        if (!supported()) throw new IOException("Windows DPAPI 不可用");
        Crypt crypt = Native.load("Crypt32", Crypt.class); Kernel kernel = Native.load("Kernel32", Kernel.class);
        Blob in = new Blob(), out = new Blob(); PointerByReference description = new PointerByReference();
        try (Memory memory = new Memory(Math.max(1, input.length))) {
            memory.write(0, input, 0, input.length); in.size = input.length; in.data = memory;
            boolean success = encrypt ? crypt.CryptProtectData(in, new WString("MC Lua IDE AI"), null, null, null, 1, out)
                    : crypt.CryptUnprotectData(in, description, null, null, null, 1, out);
            try {
                if (!success || out.data == null || out.size < 1 || out.size > 131072) throw new IOException("DPAPI 加解密失败；没有保存明文");
                return out.data.getByteArray(0, out.size);
            } finally {
                memory.clear();
                if (out.data != null) { if (!encrypt && out.size > 0 && out.size <= 131072) out.data.clear(out.size); kernel.LocalFree(out.data); }
                if (description.getValue() != null) kernel.LocalFree(description.getValue());
            }
        }
    }
    private static void check(int status) throws IOException { if (status != 0) throw new IOException("Windows 注册表操作失败（" + status + "）"); }
    void deleteTestKey() throws IOException { if (!path.startsWith(KEY + "_Test_")) throw new IOException("Only a dedicated test key can be deleted"); check(registry().RegDeleteKeyW(HKCU, new WString(path))); }
}
