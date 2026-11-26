package org.ungoogled.patches.samsung

/*
 * The Samsung ports that work with each other, for "Change package name" (see ChangePackageName.kt). Each list is what
 * the app's stock manifest declares and its renamed copy moves (permissions, provider authorities, its own intent
 * actions by isOwnAction's rule), renamed there by cloneName; the other app's renamed copy uses the same names.
 * Made from the APKs with .github/scripts/samsung-clone/declared_names.py; regenerate when a version is added.
 */

internal const val SAMSUNG_PHONE_PACKAGE = "com.samsung.android.dialer"
internal const val SAMSUNG_CONTACTS_PACKAGE = "com.samsung.android.app.contacts"
internal const val ONE_UI_HOME_PACKAGE = "com.sec.android.app.launcher"
internal const val HOME_UP_PACKAGE = "com.samsung.android.app.homestar"

internal const val SAMSUNG_PHONE_CLONE = "org.ungoogled.samsungdialer"
internal const val SAMSUNG_CONTACTS_CLONE = "org.ungoogled.samsungcontacts"
internal const val ONE_UI_HOME_CLONE = "org.ungoogled.samsunglauncher"
internal const val HOME_UP_CLONE = "org.ungoogled.homestar"

private val PHONE_DECLARED = setOf(
    "com.android.providers.contacts.sfinder.FindoSuggestionsProvider",
    "com.samsung.android.dialer.ACTION_DELETE_MISSED_CALL_NOTIFICATION",
    "com.samsung.android.dialer.ACTION_DELETE_VVM_NOTIFICATION",
    "com.samsung.android.dialer.ACTION_OPEN_NEW_CALLING_MESSAGE_BOX",
    "com.samsung.android.dialer.ACTION_VIEW_PERMISSIONS",
    "com.samsung.android.dialer.CapsuleProvider",
    "com.samsung.android.dialer.action.ADD_TAG",
    "com.samsung.android.dialer.androidx-startup",
    "com.samsung.android.dialer.contact_files",
    "com.samsung.android.dialer.permission.CALL_LOG_PICKER",
    "com.samsung.android.dialer.permission.DIALPAD_STATE",
    "com.samsung.android.dialer.permission.DOWNLOAD_PHOTORING",
    "com.samsung.android.dialer.places.action.SELECT_MAP",
    "com.samsung.android.dialer.places.action.SHOW_PLACES_CATEGORIES",
    "com.samsung.android.dialer.places.action.SHOW_PLACES_EOS",
    "com.samsung.android.dialer.places.action.SHOW_PLACES_LIST",
    "com.samsung.android.dialer.places.action.SHOW_PLACE_DETAIL",
    "com.samsung.android.dialer.recentsearch",
    "com.samsung.android.intent.action.SEARCH",
    "com.samsung.android.spv.ACTION_VIEW_DETAIL",
    "com.samsung.contacts.action.SPEED_DIAL",
    "com.samsung.contacts.action.VIEW_CALL_LOG",
    "com.samsung.contacts.action.VIEW_CALL_PLUS_SHARED_CONTENTS",
    "com.samsung.dialer.action.ACTION_PHONE_PREVIEW",
    "com.samsung.dialer.action.SEARCH",
    "com.samsung.dialer.action.SHOW_MISSEDCALL",
    "com.samsung.dialer.action.START_CONTACTS",
    "com.samsung.dialer.action.START_FAVORITES",
    "com.samsung.dialer.action.START_KEYPAD",
    "com.samsung.dialer.action.START_LOGS",
    "com.samsung.dialer.action.START_PLACES",
    "com.samsung.dialer.backup",
    "com.samsung.dialer.callmessage.CallMessageActivity",
    "com.samsung.dialer.permission.DELETE_MISSED_CALL_NOTIFICATION",
    "com.samsung.dialer.permission.NETWORK_PERMISSION_STATE",
    "com.sec.android.airview.HOVER",
    "com.sec.android.app.secsetupwizard.APP_PERMISSIONS_FOR_CHINA",
    "com.sec.android.log.ph91251zlh",
)

private val CONTACTS_DECLARED = setOf(
    "com.samsung.action.INTERACTION_GROUP_SELECT_MEMBER",
    "com.samsung.android.IMPORT_EXPORT_TAB",
    "com.samsung.android.app.contacts.CapsuleProvider",
    "com.samsung.android.app.contacts.androidx-startup",
    "com.samsung.android.app.contacts.contact_files",
    "com.samsung.android.app.contacts.permission.BACKUP_REPOSITORY",
    "com.samsung.android.app.contacts.permission.MANAGE_CONTACT_ACCESS",
    "com.samsung.android.app.contacts.permission.SOS_MSG_PICKER",
    "com.samsung.android.app.contacts.recentsearch",
    "com.samsung.android.contacts.ACTION_ABOUT_PAGE",
    "com.samsung.android.contacts.accountfilterprovider",
    "com.samsung.android.contacts.action.SHOW_THIRD_PARTY_OPTIONS",
    "com.samsung.android.contacts.agif_editor_history",
    "com.samsung.android.contacts.editor.STICKER_SETTING",
    "com.samsung.android.contacts.permission.READ_ACCOUNT_FILTER",
    "com.samsung.android.contacts.permission.READ_EMERGENCY_INFO",
    "com.samsung.android.contacts.permission.WRITE_ACCOUNT_FILTER",
    "com.samsung.android.contacts.setting",
    "com.samsung.android.contacts.setting.ACTION_VIEW_APP_PERMISSIONS",
    "com.samsung.android.contacts.setting.SERVICE_NUMBER",
    "com.samsung.android.contacts.setting.VCARD_TEXT_CODE",
    "com.samsung.android.contacts.settings.ACCOUNT_VISIBILITY",
    "com.samsung.android.contacts.trashbin.trashprovider.ManageTrashProvider",
    "com.samsung.android.devicesearch.contacts",
    "com.samsung.android.dialtacts.common.groups.GROUP_LIST",
    "com.samsung.android.spv.ACTION_VIEW_DETAIL",
    "com.samsung.contact.VIEW_CONTACT_SETTING",
    "com.samsung.contacts.action.CANCEL_IMPORT_EXPORT",
    "com.samsung.contacts.action.CANCEL_MOVE_CONTACTS",
    "com.samsung.contacts.action.CONTACTS_HOME",
    "com.samsung.contacts.action.CONTACT_SEARCH",
    "com.samsung.contacts.action.CREATE_GROUP",
    "com.samsung.contacts.action.EASY_MANAGING",
    "com.samsung.contacts.action.EDIT_CONTACT",
    "com.samsung.contacts.action.EDIT_PROFILE_CARD",
    "com.samsung.contacts.action.GROUP_EDIT",
    "com.samsung.contacts.action.JOINED_CONTACT",
    "com.samsung.contacts.action.MERGE_CONTACT",
    "com.samsung.contacts.action.MOVE_SIM_CONTACTS_TO_PHONE",
    "com.samsung.contacts.action.PREFER_SIM",
    "com.samsung.contacts.action.PROFILE_PICTURE_AND_CARD_SETTING",
    "com.samsung.contacts.action.QUICK_CONTACT",
    "com.samsung.contacts.action.SET_AS_CONTACT_CARD",
    "com.samsung.contacts.action.SET_AS_CONTACT_PICTURE",
    "com.samsung.contacts.action.SET_AS_PROFILE_CARD",
    "com.samsung.contacts.action.SET_AS_PROFILE_PICTURE",
    "com.samsung.contacts.action.SHARE_CUSTOM_VCF",
    "com.samsung.contacts.action.SHORTCUT_CREATE_CONTACT",
    "com.samsung.contacts.action.SHOW_EMERGENCY_GROUP",
    "com.samsung.contacts.action.SHOW_GROUP_DETAIL",
    "com.samsung.contacts.action.SHOW_GROUP_LIST_PICK",
    "com.samsung.contacts.action.SHOW_MY_EMERGENCY_DETAIL",
    "com.samsung.contacts.action.SHOW_MY_EMERGENCY_DETAIL_MEDICAL_INFO",
    "com.samsung.contacts.action.SHOW_OR_CREATE_PROFILE",
    "com.samsung.contacts.action.SHOW_RECYCLE_BIN",
    "com.samsung.contacts.action.SHOW_VCARD_LIST",
    "com.samsung.contacts.action.SYNC_CONTACTS",
    "com.samsung.contacts.action.VIEW_CONTACT",
    "com.samsung.contacts.action.taskedge.CREATE_CONTACT",
    "com.samsung.contacts.action.taskedge.SEARCH_CONTACT",
    "com.samsung.contacts.backup",
    "com.samsung.contacts.detail.FORWARD_TO_INTERMEDIATE",
    "com.samsung.contacts.permission.CUSTOM_FILTER",
    "com.samsung.contacts.permission.GROUP_DETAIL",
    "com.samsung.contacts.permission.JOINED_CONTACT",
    "com.samsung.contacts.permission.MY_EMERGENCY_DETAIL",
    "com.samsung.contacts.permission.MY_EMERGENCY_EDIT",
    "com.samsung.contacts.permission.PREFERRED_SIM",
    "com.samsung.contacts.permission.PROFILE_SHARING_SETTING",
    "com.samsung.contacts.permission.QR_CODE",
    "com.samsung.contacts.permission.SET_DEFAULT",
    "com.samsung.contacts.permission.START_RELATION_DETAIL",
    "com.samsung.contacts.permission.TRASH_BIN",
    "com.samsung.contacts.reorderfavorites.ACTION_REORDER_FAVORITES",
    "com.samsung.contacts.samsungcloudprovider",
    "com.sec.android.app.contact.permission.CONTACT_SETTINGS",
    "com.sec.android.app.dialertab.calllog.MISSED_CALL",
    "com.sec.android.app.secsetupwizard.APP_PERMISSIONS_FOR_CHINA",
    "com.sec.android.intent.action.SEC_APPLICATION_SETTINGS",
    "com.sec.android.log.com8o87xad",
)

private val ONE_UI_HOME_DECLARED = setOf(
    "com.honeyspace.permission.PLUGIN_HONEY",
    "com.honeyspace.permission.RECEIVE_VISIBLE_WIDGET",
    "com.honeyspace.search.datamodel.provider.backup",
    "com.honeyspace.ui.common.widget.WidgetSearchProvider",
    "com.samsung.android.app.appsedge.action.LAUNCH_FROM_RECENT",
    "com.samsung.android.app.appsedge.permission.OPEN_APP_PICKER",
    "com.samsung.android.app.cocktailbarservice.permission.ACCESS_PANEL",
    "com.samsung.android.app.cocktailbarservice.permission.EDGE_ENABLE",
    "com.samsung.android.app.cocktailbarservice.permission.EDGE_HANDLER_STATE",
    "com.samsung.android.app.cocktailbarservice.permission.ENABLE_EDGE_PANEL",
    "com.samsung.android.app.galaxyfinder.applications",
    "com.samsung.android.app.galaxyfinder.index",
    "com.samsung.android.app.galaxyfinder.permission.ACCESS_APPLICATION_PROVIDER",
    "com.samsung.android.launcher.permission.MESSAGE_CHANGED",
    "com.samsung.android.launcher.permission.READ_SETTINGS",
    "com.samsung.android.launcher.permission.TASKBAR_PERFORMED",
    "com.samsung.android.launcher.permission.UNINSTALL",
    "com.samsung.android.launcher.permission.WRITE_SETTINGS",
    "com.samsung.android.provider.indexing.permission.ACCESS_PROVIDER",
    "com.samsung.app.cocktailbarservice.settings.SETTINGSMAIN",
    "com.samsung.app.honeyspace.edge.appsedge.app.AppsEdgeDataProvider",
    "com.samsung.app.honeyspace.edge.backup.DownloadedPanelProvider",
    "com.samsung.app.honeyspace.edge.history.cocktailprovider",
    "com.samsung.app.honeyspace.edge.search",
    "com.samsung.app.honeyspace.edge.settings.EdgeSettingProvider",
    "com.samsung.app.honeyspace.edge.settings.cocktailbaruicontrollerprovider",
    "com.sec.android.app.launcher.ACTION_EASY_MODE_WIDGET_SETTINGS_CHANGED",
    "com.sec.android.app.launcher.ACTION_IP_ROTATE_EVENT",
    "com.sec.android.app.launcher.ACTION_TIME_EXPIRED",
    "com.sec.android.app.launcher.CapsuleProvider",
    "com.sec.android.app.launcher.androidx-startup",
    "com.sec.android.app.launcher.backup",
    "com.sec.android.app.launcher.backupFileProvider",
    "com.sec.android.app.launcher.command",
    "com.sec.android.app.launcher.grid_control",
    "com.sec.android.app.launcher.gts",
    "com.sec.android.app.launcher.permission.OVERLAY_APPS",
    "com.sec.android.app.launcher.permission.PLUGIN",
    "com.sec.android.app.launcher.permission.READ_SETTINGS",
    "com.sec.android.app.launcher.permission.WRITE_SETTINGS",
    "com.sec.android.app.launcher.provider.routines.v3",
    "com.sec.android.app.launcher.searchindexprovider",
    "com.sec.android.app.launcher.settings",
    "com.sec.android.app.launcher.settings.id",
    "com.sec.android.app.secsetupwizard.APP_PERMISSIONS_FOR_CHINA",
    "com.sec.android.intent.action.SEC_APPLICATION_SETTINGS",
    "com.sec.android.log.exitmqobp9",
    "com.sec.permission.BACKUP_RESTORE_HOMESCREEN",
)

private val HOME_UP_DECLARED = setOf(
    "com.samsung.android.app.homestar.action.ABOUT",
    "com.samsung.android.app.homestar.action.SETTINGS",
    "com.samsung.android.app.homestar.androidx-startup",
    "com.samsung.android.app.homestar.gts.gtsprovider",
    "com.samsung.android.app.homestar.permission.HOMESTAR_DATA",
    "com.samsung.android.app.homestar.provider",
    "com.samsung.android.app.homestar.restoreFileProvider",
    "com.samsung.android.app.sharestar.ShareStarProvider",
    "com.samsung.android.app.sharestar.permission.SHARE_STAR_DATA",
)

/** Samsung Phone, as Samsung Contacts sees it: each is useful alone, so names pick the renamed Phone when installed. */
internal val SAMSUNG_PHONE_SIBLING = CloneSibling(
    stock = SAMSUNG_PHONE_PACKAGE,
    clone = SAMSUNG_PHONE_CLONE,
    optionKey = "phonePackageName",
    title = "Samsung Phone package name",
    declared = PHONE_DECLARED,
    atRuntime = true,
)

/** Samsung Contacts, as Samsung Phone sees it. */
internal val SAMSUNG_CONTACTS_SIBLING = CloneSibling(
    stock = SAMSUNG_CONTACTS_PACKAGE,
    clone = SAMSUNG_CONTACTS_CLONE,
    optionKey = "contactsPackageName",
    title = "Samsung Contacts package name",
    declared = CONTACTS_DECLARED,
    atRuntime = true,
)

/**
 * One UI Home, as Home Up sees it. Home Up's plugins only load into a One UI Home signed with the same key, so a renamed
 * Home Up always means the renamed One UI Home. (Its com.android.* names are left out: the launcher port already moves
 * them under its package, and Home Up names none of them.)
 */
internal val ONE_UI_HOME_SIBLING = CloneSibling(
    stock = ONE_UI_HOME_PACKAGE,
    clone = ONE_UI_HOME_CLONE,
    optionKey = "oneUiHomePackageName",
    title = "One UI Home package name",
    declared = ONE_UI_HOME_DECLARED,
    atRuntime = false,
)

/** Home Up, as One UI Home sees it. */
internal val HOME_UP_SIBLING = CloneSibling(
    stock = HOME_UP_PACKAGE,
    clone = HOME_UP_CLONE,
    optionKey = "homeUpPackageName",
    title = "Home Up package name",
    declared = HOME_UP_DECLARED,
    atRuntime = false,
)
