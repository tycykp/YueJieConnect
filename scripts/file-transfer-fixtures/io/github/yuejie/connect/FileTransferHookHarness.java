package io.github.yuejie.connect;
import io.github.libxposed.api.*;
import io.github.libxposed.api.XposedInterface.*;
import java.lang.reflect.*;
import java.util.*;
public final class FileTransferHookHarness {
    private static final Map<Executable, Hooker> HOOKS = new HashMap<>();
    public static XposedModule module() {
        XposedModule module = new XposedModule() {};
        XposedInterface api = (XposedInterface) Proxy.newProxyInstance(module.getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (proxy, method, args) -> {
            if (!method.getName().equals("hook")) throw new UnsupportedOperationException(method.getName());
            Executable executable = (Executable) args[0];
            return new HookBuilder() {
                String id;
                public HookBuilder setId(String id) { this.id = id; return this; }
                public HookBuilder setPriority(int priority) { return this; }
                public HookBuilder setExceptionMode(ExceptionMode mode) { return this; }
                public HookHandle intercept(Hooker hooker) {
                    HOOKS.put(executable, hooker);
                    return new HookHandle() {
                        public Executable getExecutable() { return executable; }
                        public String getId() { return id; }
                        public void unhook() { HOOKS.remove(executable); }
                        public HookHandle replaceHook(Hooker next) { HOOKS.put(executable, next); return this; }
                    };
                }
            };
        });
        module.attachFramework(api, () -> {}); return module;
    }
    public static Object call(Method method, Object owner, Object... args) {
        try {
            Hooker hook = HOOKS.get(method);
            if (hook == null) return original(method, owner, args);
            return hook.intercept(new Chain() {
                public Executable getExecutable() { return method; }
                public Object getThisObject() { return owner; }
                public List<Object> getArgs() { return Arrays.asList(args); }
                public Object getArg(int index) { return args[index]; }
                public Object proceed() throws Throwable { return original(method, owner, args); }
                public Object proceed(Object[] actual) throws Throwable { return original(method, owner, actual); }
                public Object proceedWith(Object actual) throws Throwable { return original(method, actual, args); }
                public Object proceedWith(Object actual, Object[] values) throws Throwable { return original(method, actual, values); }
            });
        } catch (Throwable failure) { throw new AssertionError(failure); }
    }
    private static Object original(Method method, Object owner, Object[] args) throws Throwable {
        try { return method.invoke(owner, args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }
    public static void send(com.xiaomi.mirror.message.DragMessage message, long session, String peer) {
        try { call(q4.A.class.getMethod("P", com.xiaomi.mirror.message.Message.class, long.class, String.class),
                null, message, session, peer); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    public static int hooks() { return HOOKS.size(); }
}
