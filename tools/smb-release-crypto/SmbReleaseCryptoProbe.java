import dalvik.system.PathClassLoader;
import java.lang.reflect.Constructor;
import java.security.MessageDigest;
import java.security.Provider;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Runs against the APK's classes, without adding unshrunk crypto dependencies. */
public final class SmbReleaseCryptoProbe {
    public static void main(String[] args) {
        try {
            verify(args);
        } catch (Throwable error) {
            error.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static void verify(String[] args) throws Exception {
        ClassLoader loader = new PathClassLoader(args[0], ClassLoader.getSystemClassLoader());
        Provider provider = (Provider) loader.loadClass(args[1]).getDeclaredConstructor().newInstance();
        check("MD4 empty", "31d6cfe0d16ae931b73c59d7e0c089c0",
                MessageDigest.getInstance("MD4", provider).digest(new byte[0]));

        // RFC 4493 examples 1 and 2: independently verify the retained MAC implementation.
        Mac mac = Mac.getInstance("AESCMAC", provider);
        mac.init(new SecretKeySpec(hex("2b7e151628aed2a6abf7158809cf4f3c"), "AES"));
        check("AES-CMAC empty", "bb1d6929e95937287fa37d129b756746", mac.doFinal());
        check("AES-CMAC block", "070a16b46b4d4144f79bdd9dd04a287c",
                mac.doFinal(hex("6bc1bee22e409f96e93d7e117393172a")));

        Class<?> signingDigest = loader.loadClass(args[2]);
        // R8 may reorder constructor parameters. Both arrays contain zeros here.
        Constructor<?> constructor = Arrays.stream(signingDigest.getDeclaredConstructors())
                .filter(candidate -> candidate.getParameterTypes().length == 3
                        && Arrays.stream(candidate.getParameterTypes()).filter(t -> t == int.class).count() == 1
                        && Arrays.stream(candidate.getParameterTypes()).filter(t -> t == byte[].class).count() == 2)
                .findFirst().orElseThrow(() -> new AssertionError("Unexpected signing constructors"));
        constructor.setAccessible(true);
        for (int dialect : new int[] {0x0202, 0x0210, 0x0300, 0x0302, 0x0311}) {
            Class<?>[] types = constructor.getParameterTypes();
            Object[] parameters = new Object[types.length];
            int arrays = 0;
            for (int i = 0; i < types.length; i++) {
                if (types[i] == int.class) parameters[i] = dialect;
                else if (types[i] == byte[].class) parameters[i] = new byte[arrays++ == 0 ? 16 : 64];
                else throw new AssertionError("Unexpected signing constructor: " + constructor);
            }
            constructor.newInstance(parameters);
            System.out.printf("PASS SMB signing initialization %04x%n", dialect);
        }
        System.out.println("SMB_RELEASE_CRYPTO_PASS");
    }

    private static void check(String name, String expected, byte[] actual) {
        if (!Arrays.equals(hex(expected), actual)) throw new AssertionError(name);
        System.out.println("PASS " + name);
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }
}
