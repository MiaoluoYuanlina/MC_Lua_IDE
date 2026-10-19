package wiki.ica.xiaomiao.mcluaide.server;

import org.luaj.vm2.LuaError;

/** Only explicitly safe, pre-mutation state failures may use this type. */
final class LuaRecoverableError extends LuaError {
    LuaRecoverableError(String message) { super(message); }

    static LuaRecoverableError find(Throwable error) {
        for (int depth = 0; error != null && depth < 16; depth++) {
            if (error instanceof LuaRecoverableError recoverable) return recoverable;
            Throwable cause = error.getCause();
            if (cause == error) break;
            error = cause;
        }
        return null;
    }

    static LuaError fromServer(Throwable cause, String fallback) {
        LuaRecoverableError recoverable = find(cause);
        return recoverable != null ? recoverable : new LuaError(fallback);
    }
}
