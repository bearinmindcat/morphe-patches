package org.ungoogled.honorweather;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.reactivex.rxjava3.core.Observable;

/**
 * Honor Weather reaches Honor's weather service (and, through it, every supplier) with Retrofit services: its config
 * server (which refuses phones without an Honor device id), device registration, ad targeting, votes, feedback, ...
 * Off MagicOS none of that is used: the app's service factory hands out [service] instead, which answers the
 * supplier's AccuWeather-format requests from Open-Meteo (Accu) and fails everything else at once, as if offline,
 * without a connection being made.
 * <p>
 * As a second line, [install] puts a proxy selector in front of every Java connection the app (and the Honor SDKs it
 * bundles) can make: only Open-Meteo's hosts, and only once the user allowed them, get a direct connection; anything
 * else goes to a proxy that does not exist on the phone, so it fails locally (no DNS lookup, no packet out).
 */
final class HonorServers {
    private static final Map<Class<?>, Object> SERVICES = new HashMap<>();

    private HonorServers() {
    }

    /** The port's stand-in for one of the app's Retrofit service interfaces. */
    static synchronized Object service(Class<?> type) {
        Object service = SERVICES.get(type);
        if (service == null) {
            service = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, new Handler());
            SERVICES.put(type, service);
        }
        return service;
    }

    private static final class Handler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "equals": return proxy == args[0];
                    case "hashCode": return System.identityHashCode(proxy);
                    default: return "Open-Meteo stand-in for " + method.getDeclaringClass().getName();
                }
            }
            final String url = url(method, args);
            if (Accu.ours(url) && method.getReturnType() == Observable.class) {
                Type item = item(method);
                if (item == String.class) return Observable.fromCallable(() -> Accu.answer(url));
                if (item instanceof Class && List.class.isAssignableFrom((Class<?>) item)
                        || item instanceof ParameterizedType && ((ParameterizedType) item).getRawType() instanceof Class
                        && List.class.isAssignableFrom((Class<?>) ((ParameterizedType) item).getRawType())) {
                    // A typed list the supplier has nothing for (AccuWeather's life indices): empty.
                    return Observable.fromCallable(ArrayList::new);
                }
            }
            IOException off = new IOException("Honor Weather off MagicOS: Honor's servers are not used ("
                    + method.getDeclaringClass().getSimpleName() + "." + method.getName() + ")");
            if (method.getReturnType() == Observable.class) return Observable.error(off);
            throw off;
        }
    }

    /** The @Url argument of a Retrofit method, or null. */
    private static String url(Method method, Object[] args) {
        if (args == null) return null;
        Annotation[][] annotations = method.getParameterAnnotations();
        for (int i = 0; i < annotations.length && i < args.length; i++) {
            for (Annotation a : annotations[i]) {
                if ("retrofit2.http.Url".equals(a.annotationType().getName()) && args[i] != null) return args[i].toString();
            }
        }
        return null;
    }

    /** The item type of an Observable&lt;T&gt; return type. */
    private static Type item(Method method) {
        Type type = method.getGenericReturnType();
        if (type instanceof ParameterizedType) {
            Type[] arguments = ((ParameterizedType) type).getActualTypeArguments();
            if (arguments.length == 1) return arguments[0];
        }
        return null;
    }

    /**
     * A network error that arrives after its request was given up (the app cancels the requests of a refresh it no
     * longer needs; Honor's requests now fail at once) has nowhere to go, and RxJava then ends the app. Those are logged;
     * every other such error ends the app as before.
     */
    static synchronized void quietLateNetworkErrors() {
        if (io.reactivex.rxjava3.plugins.RxJavaPlugins.getErrorHandler() != null) return;
        io.reactivex.rxjava3.plugins.RxJavaPlugins.setErrorHandler(error -> {
            Throwable cause = error instanceof io.reactivex.rxjava3.exceptions.UndeliverableException && error.getCause() != null
                    ? error.getCause() : error;
            if (cause instanceof IOException || cause instanceof InterruptedException) {
                android.util.Log.w("HonorWeather", "late network error: " + cause);
                return;
            }
            Thread thread = Thread.currentThread();
            Thread.UncaughtExceptionHandler handler = thread.getUncaughtExceptionHandler();
            if (handler != null) handler.uncaughtException(thread, error);
        });
    }

    /** Only Open-Meteo, only when allowed; every other connection goes to a proxy that is not there. */
    static synchronized void install() {
        ProxySelector current = ProxySelector.getDefault();
        if (current instanceof OnlyOpenMeteo) return;
        ProxySelector.setDefault(new OnlyOpenMeteo(current));
    }

    private static final class OnlyOpenMeteo extends ProxySelector {
        private static final List<java.net.Proxy> NOWHERE = Collections.singletonList(
                new java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress.createUnresolved("127.0.0.1", 9)));
        private final ProxySelector system;

        OnlyOpenMeteo(ProxySelector system) {
            this.system = system;
        }

        @Override
        public List<java.net.Proxy> select(URI uri) {
            String host = uri == null ? null : uri.getHost();
            if (host != null && Consent.allowed() && Accu.hosts().contains(host.toLowerCase(java.util.Locale.ROOT))) {
                return system != null ? system.select(uri) : Collections.singletonList(java.net.Proxy.NO_PROXY);
            }
            return NOWHERE;
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException e) {
            if (system != null && uri != null && Accu.hosts().contains(String.valueOf(uri.getHost()))) {
                system.connectFailed(uri, address, e);
            }
        }
    }
}
