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

## Helper survival across adbd restarts

The recording helper is launched over the ADB shell, so by default it lives in
adbd's cgroup. Any adbd restart (TLS→TCP `tcpip:5555`, `usb:`, USB debugging
toggled, Wi-Fi loss without a USB transport) makes init kill that whole cgroup.

- `AdbdRestartGuard`: VRec never promotes TLS→TCP while a helper is alive, a
  call is live or enhanced capture is active. Promotion runs inside
  `AdbLifecycle.coordinateSetup` and re-probes the live helper first, so it can
  never interleave with a helper launch. A user Retry on an already verified
  session with a live helper does not call the core's `retry()`.
- Launch: `setsid nohup sh -c '<CGROUP_ESCAPE> exec app_process …'` moves the
  launcher into the root cgroup (v2, then v1 roots) before exec, like Shizuku's
  starter. The result is logged as `VREC_CGROUP=…` in
  `/data/local/tmp/vrec-capture.out`; `unchanged` means Android refused the move
  and the previous behaviour applies.
- Recovery is allowed by default once a live UID 2000 shell is verified; only an
  explicit Disconnect revokes it.
