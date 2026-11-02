# ADB socket and identity lifecycle — standard for future apps

Applicable to Android apps using `com.github.MuntashirAkon:libadb-android`,
verified against the upstream `3.1.1` implementation (Nov 2026).

**Root cause:** `AbsAdbConnectionManager.close()` calls
`getPrivateKey().destroy()` and prints `DestroyFailedException`.
`AdbConnection.close()` also destroys its supplied key, while the
connection reader can print `SocketException: Socket closed` during intentional
shutdown. These log messages alone do not prove an application crash.

**Required pattern**
1. Preserve the persisted authorized ADB RSA private key and certificate.
   Never erase or regenerate them on ordinary reconnect, Stop, or Quit.
2. Supply each libadb manager with a **new PKCS#8-decoded key object** copied
   from the persisted key. Never supply the cached persistent key itself.
3. Use `disconnect()` for ordinary transport teardown; avoid the manager's
   key-destroying `close()`. Retire the manager reference after disconnect.
4. After shell timeout, drop the shell, disconnect the entire manager, and
   require new authorization verification (`id -u == 2000`) on reconnect.
5. Reuse verified healthy connections; prevent duplicate connects, apply
   retry backoff, honor user-initiated Quit, and maintain one shutdown owner.
6. Close the ADB stream before buffered input/output wrappers to wake readers.
7. Keep timeouts finite; propagate cancellation, avoid logging sensitive
   pairing codes or credentials, and do not globally suppress socket warnings.
8. Test **key-object isolation**, repeated reconnect, timeout cleanup,
   Quit while reconnecting, and identity preservation.

Upstream reference: `MuntashirAkon/libadb-android` tag `3.1.1`.
Implementation reference: `N2K02/McpAdbAndroidLogger` PR #30.
