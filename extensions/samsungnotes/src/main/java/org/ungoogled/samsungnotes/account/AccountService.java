package org.ungoogled.samsungnotes.account;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Samsung Account app's request service (com.msc.sa.aidl.ISAService) for Notes, inside Notes: Notes binds it to
 * ask for its access token. Without the app, the answer comes from the account signed in on Samsung's web page, renewed
 * with its refresh token when Notes says the token expired. Notes' own client code (AuthApi) and sync engine stay as
 * they are.
 */
final class AccountService implements InvocationHandler {
    private static final String TAG = SamsungAuth.TAG;
    private static final String SERVICE = "com.msc.sa.aidl.ISAService";
    private static final String CALLBACK = "com.msc.sa.aidl.ISACallback";
    private static Binder binder;

    private final Context context;
    private final Map<String, Object> callbacks = new ConcurrentHashMap<>();
    private final AtomicInteger next = new AtomicInteger(1);

    private AccountService(Context context) {
        this.context = context;
    }

    /** A local binder whose interface is this service; ISAService.Stub.asInterface returns it as it is. */
    static synchronized IBinder binder(Context context) {
        if (binder != null) return binder;
        try {
            ClassLoader loader = AccountService.class.getClassLoader();
            Class<?> service = Class.forName(SERVICE, false, loader);
            Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{service}, new AccountService(context.getApplicationContext()));
            Binder b = new Binder();
            b.attachInterface((IInterface) proxy, SERVICE);
            binder = b;
            return b;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "account service unavailable: " + e);
            return null;
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "asBinder":
                return binder;
            case "registerCallback": {
                // (appId, appSecret, packageName, ISACallback) -> a registration code.
                String code = "ungoogled-" + next.getAndIncrement();
                if (args[3] != null) callbacks.put(code, args[3]);
                return code;
            }
            case "unregisterCallback":
                return callbacks.remove((String) args[0]) != null;
            case "requestAccessToken": {
                Object callback = callbacks.get((String) args[1]);
                if (callback == null) return false;
                int id = (Integer) args[0];
                Bundle request = (Bundle) args[2];
                new Thread(() -> answerToken(callback, id, request), "UASamsungAccountToken").start();
                return true;
            }
            case "requestRequiredConsent": {
                Object callback = callbacks.get((String) args[1]);
                if (callback == null) return false;
                int id = (Integer) args[0];
                // Samsung's sign-in page asks for any terms the account still has to accept before it hands out a
                // token; afterwards nothing is pending.
                Bundle result = new Bundle();
                result.putString("consent_list", "[]");
                new Thread(() -> deliver(callback, "onReceiveRequiredConsent", id, AccountStore.get(context).signedIn(), result),
                    "UASamsungAccountConsent").start();
                return true;
            }
            case "toString":
                return "SamsungAccountWebService";
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == args[0];
            default:
                // requestAuthCode, requestSCloudAccessToken, ...: the web sign-in has nothing for them.
                Log.i(TAG, "not available without the Samsung Account app: " + method.getName());
                return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
        }
    }

    private void answerToken(Object callback, int id, Bundle request) {
        AccountStore store = AccountStore.get(context);
        if (!store.signedIn()) {
            deliver(callback, "onReceiveAccessToken", id, false, error("SAC_0102", "Not signed in to a Samsung account"));
            return;
        }
        String expired = request != null ? request.getString("expired_access_token") : null;
        boolean renew = store.accessExpired() || (!AccountStore.empty(expired) && expired.equals(store.accessToken()));
        if (renew) {
            SamsungAuth.Result r = SamsungAuth.refresh(store, SamsungAccount.clientId());
            if (r == SamsungAuth.Result.REFUSED) {
                store.needsSignIn(true);
                SamsungAccount.askToSignInAgain(context);
                deliver(callback, "onReceiveAccessToken", id, false, error("SAC_0402", "Sign in to your Samsung account again"));
                return;
            }
            if (r == SamsungAuth.Result.NETWORK) {
                deliver(callback, "onReceiveAccessToken", id, false, error("SAC_0301", "Network error"));
                return;
            }
        }
        deliver(callback, "onReceiveAccessToken", id, true, store.toResult());
    }

    private static Bundle error(String code, String message) {
        Bundle b = new Bundle();
        b.putString("error_code", code);
        b.putString("error_message", message);
        return b;
    }

    /** Calls ISACallback.<name>(int, boolean, Bundle) on Notes' callback (through the public interface). */
    private static void deliver(Object callback, String name, int id, boolean ok, Bundle data) {
        try {
            Class<?> type = Class.forName(CALLBACK, false, AccountService.class.getClassLoader());
            type.getMethod(name, int.class, boolean.class, Bundle.class).invoke(callback, id, ok, data);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "account answer not delivered: " + e);
        }
    }
}
