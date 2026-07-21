# PAX POSLink 2 SDK (Java — semi-integrated card payments)

Copy **POSLink 2 Java** JAR files from the [PAX Developer Center](https://developer.pax.us/resources) into this folder.

## Which SDK to download

| Download from PAX Developer Center | Use for this app? |
|---|---|
| **POSLink 2 Semi-Integration — Java** (Windows/Linux/macOS) | **Yes** — card payment terminal communication |
| POSLink Peripheries (Android) | **No** — printer/scanner/cash drawer on Paydroid devices |
| POSLink Full Integration (iOS/macOS native) | **No** — native mobile SDK, not Java |
| POSLink UI (Android) | **No** — custom terminal UI on Android |

The app loads `com.pax.poslink.PosLink`, `CommSetting`, `PaymentRequest`, and related classes at runtime via reflection (`PosLink2TerminalClient`). Android Peripheries JARs (`com.pax.poslinkperipheries.*`, `POSLink_*_Android_*.jar`) do **not** contain these classes and will not enable card payments.

## What to copy

From the **POSLink 2 Semi-Integration Java/Android** bundle (`POSLink_Semi_Integration_Java_Android_V2.02.00_*`), copy these **Java** JARs into this directory:

```
libs/core/java/POSLink_Core_Java_*.jar
libs/core/java/json-*.jar
libs/core/common_lib/PaxLog_*.jar
libs/core/common_lib/gson-*.jar
libs/plugin_semiintegration/java/POSLink_Semi_Java_Plugin_*.jar
libs/plugin_semiintegration/java/POSLink_Admin_Java_Plugin_*.jar
libs/plugin_semiintegration/common_lib/jsch-*.jar
libs/plugin_semiintegration/common_lib/okio-*.jar
libs/plugin_semiintegration/common_lib/okhttp-*.jar
```

**Do not copy:** Android JARs, `-sources.jar` files, `jniLibs/`, UART/RXTX libs (unless using serial comm), or demo APKs.

POSLink 2 V2.02 uses the `com.pax.poslinksemiintegration` API (not the legacy `com.pax.poslink.PosLink` classes). The app adapter supports both.

## Verify the JARs

After copying, confirm the payment API is present:

```bash
javap -classpath "lib/pax/*" com.pax.poslinksemiintegration.POSLinkSemi \
    com.pax.poslinksemiintegration.transaction.DoCreditRequest
```

Restart the application after adding JARs. Hardware Settings → PAX terminal status should show **SDK found** instead of "SDK not found".

## Runtime loading

The reflection loader scans this `lib/pax/` folder **relative to the working directory** at runtime. You do **not** need to bundle the JARs into the fat JAR — ship the `lib/pax/` directory (with the JARs) alongside `pasal-pos-*.jar` and run the app from that location. To bundle them into the fat JAR instead, add system-scope dependencies in `pom.xml` pointing at the files in this directory.

When `app.testing.mode=true` or JARs are absent, the app uses mock mode for development.
