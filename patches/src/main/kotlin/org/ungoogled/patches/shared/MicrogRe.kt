package org.ungoogled.patches.shared

/**
 * The names microG-RE (MicroG-RE / ReVanced GmsCore) serves Google Play services under, shared by every
 * app's "Add microG-RE support": microG-RE installs as its own package with its own authorities and
 * permissions, so each Play services name an app uses is pointed at microG-RE's. Bind actions stay as
 * they are: microG-RE answers Google's own actions, except for push ([PUSH_ACTION_ROUTES]).
 */
object MicrogRe {
    /** The package microG installs as: MicroG-RE and ReVanced GmsCore both use it. */
    const val PACKAGE = "app.revanced.android.gms"
    const val VENDOR = "app.revanced"
    const val C2DM = "$VENDOR.android.c2dm"

    /** Play services permissions microG declares under its own names. */
    val PERMISSION_ROUTES = mapOf(
        "com.google.android.c2dm.permission.RECEIVE" to "$C2DM.permission.RECEIVE",
        "com.google.android.c2dm.permission.SEND" to "$C2DM.permission.SEND",
        "com.google.android.providers.gsf.permission.READ_GSERVICES" to "$VENDOR.android.providers.gsf.permission.READ_GSERVICES",
        "com.google.android.gms.permission.CAR_SPEED" to "$PACKAGE.permission.CAR_SPEED",
    )

    /**
     * Push (C2DM / GCM / FCM) actions, the exception to Google's own names: microG-RE takes registrations only
     * under its own REGISTER / TOKEN_REQUEST actions and delivers every message with its own RECEIVE action, so
     * an app keeping Google's gets no token and no messages.
     */
    val PUSH_ACTION_ROUTES = mapOf(
        "com.google.android.c2dm.intent.REGISTER" to "$C2DM.intent.REGISTER",
        "com.google.android.c2dm.intent.REGISTRATION" to "$C2DM.intent.REGISTRATION",
        "com.google.android.c2dm.intent.UNREGISTER" to "$C2DM.intent.UNREGISTER",
        "com.google.android.c2dm.intent.RECEIVE" to "$C2DM.intent.RECEIVE",
        "com.google.android.gcm.intent.SEND" to "$VENDOR.android.gcm.intent.SEND",
        "com.google.iid.TOKEN_REQUEST" to "$VENDOR.iid.TOKEN_REQUEST",
    )

    /** Whole strings in an app's code: the account type, Play services' package, its providers and permissions. */
    val STRING_ROUTES = PERMISSION_ROUTES + mapOf(
        "com.google" to VENDOR,
        "subscribedfeeds" to "$VENDOR.subscribedfeeds",
        "com.google.android.gms" to PACKAGE,
        "com.google.android.gms.auth.accounts" to "$PACKAGE.auth.accounts",
        "com.google.android.gms.chimera" to "$PACKAGE.chimera",
        "com.google.android.gms.fonts" to "$PACKAGE.fonts",
        "com.google.android.gms.phenotype" to "$PACKAGE.phenotype",
    )

    /** Content URIs, by prefix. */
    val URI_ROUTES = listOf(
        "content://com.google.android.gms.phenotype" to "content://$PACKAGE.phenotype",
        "content://com.google.android.gsf.gservices" to "content://$VENDOR.android.gsf.gservices",
        "content://com.google.settings" to "content://$VENDOR.settings",
        "content://subscribedfeeds" to "content://$VENDOR.subscribedfeeds",
    )

    /** microG-RE's name for [value], a whole string in an app's code, or null when it is not a Play services name. */
    fun route(value: String): String? {
        STRING_ROUTES[value]?.let { return it }
        for ((from, to) in URI_ROUTES) if (value.startsWith(from)) return to + value.substring(from.length)
        return null
    }
}
