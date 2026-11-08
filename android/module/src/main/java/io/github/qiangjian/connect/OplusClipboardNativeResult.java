package io.github.qiangjian.connect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Reinjects a REAL remote write outcome into the sampled consumer's own response observer. */
final class OplusClipboardNativeResult {
    private final Constructor<?> observer;
    private final Method next, complete, error, builder, status, build;

    OplusClipboardNativeResult(ClassLoader loader) throws ReflectiveOperationException {
        String base = "com.oplus.linker.synergy.wisetransfer.clipboard.icdf.consumer.ClipBoardConsumerImpl";
        Class<?> consumer = Class.forName(base, false, loader);
        observer = Class.forName(base + "$3", false, loader).getDeclaredConstructor(consumer);
        observer.setAccessible(true);
        Class<?> callback = Class.forName("d83", false, loader); // Original DEX name, not JADX's defpackage.
        next = callback.getMethod("onNext", Object.class);
        complete = callback.getMethod("onCompleted");
        error = callback.getMethod("onError", Throwable.class);
        Class<?> result = Class.forName("com.oplus.linker.synergy.Clipboard$Result", false, loader);
        Class<?> resultBuilder = Class.forName("com.oplus.linker.synergy.Clipboard$Result$Builder", false, loader);
        builder = result.getMethod("newBuilder");
        status = resultBuilder.getMethod("setStatus", int.class);
        build = resultBuilder.getMethod("build");
    }

    Object observer(Object consumer) throws ReflectiveOperationException { return observer.newInstance(consumer); }
    void deliver(Object callback, Throwable failure) throws ReflectiveOperationException {
        if (failure != null) {
            // The OEM observer logs Throwable.toString(); never forward payload-bearing parser
            // exceptions or remote diagnostic text into that logger.
            error.invoke(callback, new IllegalStateException("QJ:clipboard_write_failed"));
            return;
        }
        Object result = builder.invoke(null);
        status.invoke(result, 0); // Sample ClipBoardServiceImpl.Invoker.shutdown emits status=0 on success.
        next.invoke(callback, build.invoke(result));
        complete.invoke(callback);
    }
}
