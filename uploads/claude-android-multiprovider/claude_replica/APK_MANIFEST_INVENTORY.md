# Detailed Android manifest inventory

Sumber: binary `AndroidManifest.xml` dari APK yang diberikan; hasil decode struktur AXML. Ini adalah inventaris deklarasi manifest, bukan bukti bahwa setiap fitur diaktifkan pada semua akun/perangkat.

## Application / package metadata

- Application ID: `com.anthropic.claude`
- Version name: `1.261120.19`
- Version code: `26112019`
- Min SDK: `32`
- Target / compile SDK: `37`
- `ClaudeApplication`: `com.anthropic.claude.application.ClaudeApplication`

## Component counts

| Manifest tag | Count |
|---|---:|
| `activity` | 26 |
| `activity-alias` | 6 |
| `service` | 23 |
| `receiver` | 19 |
| `provider` | 10 |
| `uses-permission` | 66 |

Deklarasi `activity` dan `activity-alias` dipisahkan agar tidak terhitung ganda.

## `activity` declarations

- `com.anthropic.claude.mainactivity.MainActivity` — exported=`true`, launchMode=`1`
- `com.anthropic.claude.deeplink.NewWindowActivity` — exported=`false`
- `com.anthropic.claude.mainactivity.AssistantOverlayActivity` — exported=`true`, launchMode=`2`, taskAffinity=`com.anthropic.claude.assist`
- `com.anthropic.claude.artifactapp.ArtifactAppActivity` — exported=`false`
- `com.anthropic.claude.notification.NotificationAnswerActivity` — exported=`false`, launchMode=`1`
- `com.anthropic.claude.deeplink.CallWindowActivity` — exported=`false`, launchMode=`1`
- `com.anthropic.claude.deeplink.PasswordManagerReturnActivity` — exported=`true`, enabled=`false`
- `com.anthropic.claude.deeplink.DeepLinkActivity` — exported=`true`, launchMode=`2`
- `com.anthropic.claude.deeplink.SecretLinkActivity` — exported=`true`, enabled=`2131034121`, launchMode=`0`
- `com.anthropic.claude.deeplink.BringToFrontActivity` — exported=`true`, launchMode=`2`
- `com.anthropic.claude.policy.PermissionsRationaleActivity` — exported=`true`
- `com.anthropic.claude.widget.ClaudeWidgetConfigActivity` — exported=`true`
- `com.anthropic.claude.filedownload.OpenDownloadActivity` — exported=`false`
- `com.anthropic.claude.personalfinance.plaidscreens.PlaidScreensLockActivity` — exported=`false`, launchMode=`1`
- `com.plaid.internal.redirect.LinkRedirectActivity` — exported=`true`, enabled=`false`, launchMode=`2`
- `com.plaid.internal.link.LinkActivity` — exported=`false`, launchMode=`0`
- `com.android.billingclient.api.ProxyBillingActivity` — exported=`false`
- `com.android.billingclient.api.ProxyBillingActivityV2` — exported=`false`
- `androidx.media3.session.BluetoothValidationActivity` — exported=`true`, permission=`android.permission.BLUETOOTH_PRIVILEGED`
- `androidx.glance.appwidget.action.ActionTrampolineActivity` — exported=`false`, enabled=`true`
- `androidx.glance.appwidget.action.InvisibleActionTrampolineActivity` — exported=`false`, enabled=`true`, launchMode=`3`, taskAffinity=`androidx.glance.appwidget.ListAdapterCallbackTrampoline`
- `androidx.credentials.playservices.controllers.identityauth.HiddenActivity` — exported=`false`, enabled=`true`
- `androidx.credentials.playservices.controllers.identitycredentials.IdentityCredentialApiHiddenActivity` — exported=`false`, enabled=`true`
- `com.google.android.gms.common.api.GoogleApiActivity` — exported=`false`
- `com.google.android.play.agesignals.AgeSharingConsentWrapperActivity` — exported=`false`
- `com.google.android.play.core.common.PlayCoreDialogWrapperActivity` — exported=`false`

## `activity-alias` declarations

- `com.anthropic.claude.mainactivity.AppOnlyMainActivity` — targetActivity=`com.anthropic.claude.mainactivity.MainActivity`, exported=`false`
- `com.anthropic.claude.mainactivity.AppOnlyAssistantOverlay` — targetActivity=`com.anthropic.claude.mainactivity.AssistantOverlayActivity`, exported=`false`
- `com.anthropic.claude.deeplink.OpenWithClaude` — targetActivity=`com.anthropic.claude.deeplink.DeepLinkActivity`, exported=`true`, enabled=`false`
- `com.anthropic.claude.deeplink.AppOnlyDeepLinkActivity` — targetActivity=`com.anthropic.claude.deeplink.DeepLinkActivity`, exported=`false`
- `com.anthropic.claude.deeplink.SecretLinksInClaudeTask` — targetActivity=`com.anthropic.claude.deeplink.DeepLinkActivity`, exported=`true`, enabled=`2131034120`
- `com.anthropic.claude.ViewPermissionUsageActivity` — targetActivity=`com.anthropic.claude.policy.PermissionsRationaleActivity`, exported=`true`, permission=`android.permission.START_VIEW_PERMISSION_USAGE`

## `service` declarations

- `com.google.android.gms.metadata.ModuleDependencies` — exported=`false`, enabled=`false`
- `com.anthropic.claude.observability.EventFlushJob` — exported=`false`, permission=`android.permission.BIND_JOB_SERVICE`
- `com.anthropic.claude.bell.VoiceModeService` — exported=`false`, enabled=`true`, foregroundServiceType=`0x82`, stopWithTask=`false`
- `com.anthropic.claude.bell.assist.ClaudeVoiceInteractionService` — exported=`true`, permission=`android.permission.BIND_VOICE_INTERACTION`
- `com.anthropic.claude.bell.assist.ClaudeVoiceInteractionSessionService` — exported=`true`, permission=`android.permission.BIND_VOICE_INTERACTION`
- `com.anthropic.claude.bell.assist.ClaudeRecognitionService` — exported=`true`, permission=`android.permission.BIND_TEXT_SERVICE`
- `com.anthropic.claude.chat.MessageSseService`
- `com.anthropic.claude.bell.tts.TTSPlaybackService` — exported=`false`, enabled=`true`, foregroundServiceType=`0x2`, stopWithTask=`false`
- `com.anthropic.claude.firebase.fcm.AnthropicFirebaseMessagingService` — exported=`false`
- `com.anthropic.claude.media.player.service.FilePlaybackService` — exported=`false`, foregroundServiceType=`0x2`
- `androidx.health.platform.client.impl.sdkservice.HealthDataSdkService` — exported=`true`, enabled=`true`
- `androidx.glance.appwidget.GlanceRemoteViewsService` — exported=`true`, permission=`android.permission.BIND_REMOTEVIEWS`
- `com.google.mlkit.common.internal.MlKitComponentDiscoveryService` — exported=`false`, directBootAware=`true`
- `com.google.firebase.messaging.FirebaseMessagingService` — exported=`false`, directBootAware=`true`
- `com.google.firebase.components.ComponentDiscoveryService` — exported=`false`, directBootAware=`true`
- `androidx.credentials.playservices.CredentialProviderMetadataHolder` — exported=`false`, enabled=`true`
- `androidx.work.impl.background.systemjob.SystemJobService` — exported=`true`, enabled=`2131034116`, permission=`android.permission.BIND_JOB_SERVICE`, directBootAware=`false`
- `androidx.work.impl.foreground.SystemForegroundService` — exported=`false`, enabled=`2131034115`, directBootAware=`false`
- `androidx.room.MultiInstanceInvalidationService` — exported=`false`, directBootAware=`true`
- `androidx.room3.MultiInstanceInvalidationService` — exported=`false`, directBootAware=`true`
- `androidx.core.widget.RemoteViewsCompatService` — permission=`android.permission.BIND_REMOTEVIEWS`
- `com.google.android.datatransport.runtime.backends.TransportBackendDiscovery` — exported=`false`
- `com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService` — exported=`false`, permission=`android.permission.BIND_JOB_SERVICE`

## `receiver` declarations

- `com.anthropic.claude.notification.HubPermissionActionReceiver` — exported=`false`
- `com.anthropic.claude.shortcut.ShortcutPinnedReceiver` — exported=`false`
- `com.anthropic.claude.update.InstallAppUpdateReceiver` — exported=`false`
- `com.anthropic.claude.code.remote.notification.CCRPermissionActionReceiver` — exported=`false`
- `com.anthropic.claude.code.remote.notification.SessionReplyActionReceiver` — exported=`false`
- `com.anthropic.claude.code.remote.notification.CodeProjectGroupDismissReceiver` — exported=`false`
- `com.anthropic.claude.artifactapp.ArtifactAppPinnedReceiver` — exported=`false`
- `com.anthropic.claude.widget.WidgetPinnedReceiver` — exported=`false`
- `com.anthropic.claude.widget.ClaudeAppWidgetReceiver` — exported=`false`
- `com.anthropic.claude.widget.CodeShortcutsWidgetReceiver` — exported=`false`, enabled=`2131034122`
- `com.anthropic.claude.widget.usage.UsageWidgetReceiver` — exported=`false`, enabled=`2131034122`
- `androidx.glance.appwidget.action.ActionCallbackBroadcastReceiver` — exported=`false`, enabled=`true`
- `androidx.glance.appwidget.UnmanagedSessionReceiver` — exported=`false`, enabled=`true`
- `androidx.glance.appwidget.MyPackageReplacedReceiver` — exported=`false`, enabled=`true`
- `com.google.firebase.iid.FirebaseInstanceIdReceiver` — exported=`true`, permission=`com.google.android.c2dm.permission.SEND`
- `androidx.work.impl.utils.ForceStopRunnable$BroadcastReceiver` — exported=`false`, enabled=`true`, directBootAware=`false`
- `androidx.work.impl.diagnostics.DiagnosticsReceiver` — exported=`true`, enabled=`true`, permission=`android.permission.DUMP`, directBootAware=`false`
- `androidx.profileinstaller.ProfileInstallReceiver` — exported=`true`, enabled=`true`, permission=`android.permission.DUMP`, directBootAware=`false`
- `com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver` — exported=`false`

## `provider` declarations

- `(unnamed)` — authorities=`androidx.car.app.connection`
- `androidx.core.content.FileProvider` — exported=`false`, authorities=`com.anthropic.claude.provider`
- `androidx.startup.InitializationProvider` — exported=`false`, authorities=`com.anthropic.claude.androidx-startup`
- `com.plaid.internal.webview.PlaidFileProvider` — exported=`false`, authorities=`com.anthropic.claude.com.plaid.internal.webview.PlaidFileProvider`
- `org.jetbrains.compose.resources.AndroidContextProvider` — exported=`false`, enabled=`true`, authorities=`com.anthropic.claude.resources.AndroidContextProvider`
- `io.sentry.android.core.SentryInitProvider` — exported=`false`, authorities=`com.anthropic.claude.SentryInitProvider`
- `io.sentry.android.core.SentryPerformanceProvider` — exported=`false`, authorities=`com.anthropic.claude.SentryPerformanceProvider`
- `com.datadog.android.profiling.DdProfilingContentProvider` — exported=`false`, authorities=`com.anthropic.claude.provider.datadog.profiling`
- `com.google.mlkit.common.internal.MlKitInitProvider` — exported=`false`, authorities=`com.anthropic.claude.mlkitinitprovider`
- `com.datadog.android.rum.DdRumContentProvider` — exported=`false`, authorities=`com.anthropic.claude.provider.datadog.rum`

## `uses-permission` declarations

- `android.permission.INTERNET`
- `android.permission.ACCESS_NETWORK_STATE`
- `android.permission.VIBRATE`
- `android.permission.RECORD_AUDIO`
- `android.permission.ACCESS_FINE_LOCATION`
- `android.permission.ACCESS_COARSE_LOCATION`
- `android.permission.READ_CALENDAR`
- `android.permission.WRITE_CALENDAR`
- `android.permission.DETECT_SCREEN_CAPTURE`
- `android.permission.HIDE_OVERLAY_WINDOWS`
- `android.permission.health.READ_STEPS`
- `android.permission.health.READ_DISTANCE`
- `android.permission.health.READ_ACTIVE_CALORIES_BURNED`
- `android.permission.health.READ_TOTAL_CALORIES_BURNED`
- `android.permission.health.READ_FLOORS_CLIMBED`
- `android.permission.health.READ_ELEVATION_GAINED`
- `android.permission.health.READ_WHEELCHAIR_PUSHES`
- `android.permission.health.READ_SPEED`
- `android.permission.health.READ_STEPS_CADENCE`
- `android.permission.health.READ_CYCLING_PEDALING_CADENCE`
- `android.permission.health.READ_POWER`
- `android.permission.health.READ_EXERCISE`
- `android.permission.health.READ_WEIGHT`
- `android.permission.health.READ_HEIGHT`
- `android.permission.health.READ_BODY_FAT`
- `android.permission.health.READ_LEAN_BODY_MASS`
- `android.permission.health.READ_BONE_MASS`
- `android.permission.health.READ_BODY_WATER_MASS`
- `android.permission.health.READ_BASAL_METABOLIC_RATE`
- `android.permission.health.READ_HEART_RATE`
- `android.permission.health.READ_RESTING_HEART_RATE`
- `android.permission.health.READ_HEART_RATE_VARIABILITY`
- `android.permission.health.READ_BLOOD_PRESSURE`
- `android.permission.health.READ_BLOOD_GLUCOSE`
- `android.permission.health.READ_OXYGEN_SATURATION`
- `android.permission.health.READ_RESPIRATORY_RATE`
- `android.permission.health.READ_BODY_TEMPERATURE`
- `android.permission.health.READ_BASAL_BODY_TEMPERATURE`
- `android.permission.health.READ_VO2_MAX`
- `android.permission.health.READ_SKIN_TEMPERATURE`
- `android.permission.health.READ_HYDRATION`
- `android.permission.health.READ_NUTRITION`
- `android.permission.health.READ_SLEEP`
- `android.permission.health.READ_CERVICAL_MUCUS`
- `android.permission.health.READ_MENSTRUATION`
- `android.permission.health.READ_OVULATION_TEST`
- `android.permission.health.READ_INTERMENSTRUAL_BLEEDING`
- `android.permission.health.READ_SEXUAL_ACTIVITY`
- `android.permission.health.READ_MINDFULNESS`
- `android.permission.health.READ_PLANNED_EXERCISE`
- `android.permission.health.READ_HEALTH_DATA_HISTORY`
- `com.android.alarm.permission.SET_ALARM`
- `android.permission.WRITE_EXTERNAL_STORAGE` — maxSdkVersion=`28`
- `android.permission.CHANGE_NETWORK_STATE`
- `android.permission.FOREGROUND_SERVICE`
- `android.permission.FOREGROUND_SERVICE_MICROPHONE`
- `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK`
- `android.permission.POST_NOTIFICATIONS`
- `android.permission.USE_BIOMETRIC`
- `android.permission.WAKE_LOCK`
- `android.permission.MODIFY_AUDIO_SETTINGS`
- `com.android.vending.BILLING`
- `com.google.android.c2dm.permission.RECEIVE`
- `android.permission.RECEIVE_BOOT_COMPLETED`
- `com.anthropic.claude.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
- `com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE`
