package com.aether.engine.proxy

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.util.Log

/**
 * GuestProcessRegistry — SNAKE a7.m()/p3/jv0.P2 parity (ขั้น ② ของแผนประกอบ)
 *
 * provenance (audit C16): ตัวเลขบรรทัด jadx (a7.java:171/317/601, jv0.P2:285 ฯลฯ)
 * มาจาก T2 transcript — ตอนนี้ commit ไว้ที่ reference/NATIVE_CALLSITE_MAP.md
 * แล้ว; T1 ที่ machine-verify ได้ = reference/snake/F2_dex_natives.txt (class+sig
 * ระดับ DexLayout) — sig ตรวจซ้ำด้วย scripts/native_chain_parity.py ทุก preflight
 *
 * หลักฐาน (NATIVE_CALLSITE_MAP.md §3 hops 10–12):
 *   a7.java:171 m(): Bundle "SnakeEngine_client_config" ← p3 → ContentResolver.call(
 *     content://com.snake.proxy_content_provider_<slot>, "_Engine_|_init_process_")
 *   → ANDROID เป็นฝ่าย spawn :pN เพราะ provider ถูก touch (ไม่ใช่ startActivity)
 *   ProxyContentProvider.java:15-28 (child): call → jv0.P2(p3) → ตอบ Bundle
 *     "_Engine_|_client_" = asBinder() ของ child → server linkToDeath คุมชีพ
 *   jv0.java:283-297 P2(): reject ถ้า config pkg ใหม่คนละกับที่ process ผูกไว้
 *
 * p3 field map (p3.java:6-13): m=pkg o=slot r=userId — Aether ยังไม่มี
 * virtual-UID (p3.p/q ของ SNAKE มาจาก x6.y2 — ขึ้นกับขั้น PMS เสมือน)
 *
 * ข้อได้เปรียบเชิงลำดับ: config ถึง child *ตอน provider install* ซึ่งอยู่ใน
 * bindApplication ของ framework — ก่อน activity dispatch ใด ๆ (intent extras
 * แบบเดิมมาถึงช้ากว่าและหลุดเข้า getIntent() ของ guest ได้)
 */
data class ClientConfig(
    val guestPkg: String,
    val slot: Int,
    val userId: Int,
)

object GuestProcessTable {
    private const val TAG = "AetherGuestTable"

    /** ชื่อ method/keys — mirror SNAKE "_Engine_|_init_process_" / "_Engine_|_client_" */
    const val METHOD_INIT = "_Engine_|_init_process_"
    const val BUNDLE_CLIENT = "_Engine_|_client_"

    /** manifest ประกาศ ProxyContentProvider$P0..P3 (authorities content://com.aether.proxy.content.N) */
    const val MAX_SLOTS = 4

    /** keys ของ request bundle — endpoint สองฝั่งเป็นโค้ดเรา (blueprint B4: ธีม snake `_S_|_*`)
     *  ผู้เขียน = configToBundle() · ผู้อ่าน = GuestProcessHolder.handleInit() — ชุดเดียวตรงกัน */
    const val EXTRA_GUEST_PKG = "_S_|_guest_pkg_"
    const val EXTRA_SLOT      = "_S_|_slot_"
    const val EXTRA_USER_ID   = "_S_|_user_id_"
    const val EXTRA_SUCCESS   = "_S_|_success_"
    const val EXTRA_ERROR     = "_S_|_error_"

    /** authority per-slot (P2 จะเทียบ snake `proxy_content_provider_<n>` — ตอนนี้คงเดิมทั้ง 2 ฝั่ง) */
    fun providerAuthority(slot: Int): String = "content://com.aether.proxy.content.$slot"

    /** ClientConfig → request Bundle (คีย์ ≡ ที่ handleInit อ่าน — ห้ามต่างกัน) */
    fun configToBundle(cfg: ClientConfig): Bundle = Bundle().apply {
        putString(EXTRA_GUEST_PKG, cfg.guestPkg)
        putInt(EXTRA_SLOT, cfg.slot)
        putInt(EXTRA_USER_ID, cfg.userId)
    }

    // ─── server-side state (process ที่เรียก launchInSandbox) ───
    private val slotOf = HashMap<String, Int>()          // guestPkg → slot (a7 map)
    private val clientOf = HashMap<Int, IBinder>()       // slot → child binder proxy
    private val deathOf = HashMap<Int, IBinder.DeathRecipient>()

    fun slotFor(guestPkg: String): Int? = synchronized(this) { slotOf[guestPkg] }

    /** snapshot สำหรับ bridge chainCheck/handshakeStatus — ไม่ใช่ state mutation */
    fun status(): String = synchronized(this) {
        if (slotOf.isEmpty()) return "slots: none (host-process table)"
        slotOf.entries.joinToString("; ") { (pkg, s) ->
            "$pkg→:p$s ${if (isSlotAlive(s)) "alive" else "DEAD"}"
        }
    }

    fun isSlotAlive(slot: Int): Boolean =
        synchronized(this) { clientOf[slot]?.pingBinder() == true }

    /**
     * a7.l() parity: slot 0..3 ที่ (ก) ไม่มี guest อื่น map อยู่ (ข) ชื่อ process
     * "<pkg>:pN" ไม่ปรากฏใน getRunningAppProcesses (A16 คืนเฉพาะ process ของ
     * ตัวเอง — com.aether:pN เป็นของเรา ตรวจได้ตรง)
     * คืน -1 = "No processes available" (a7.java:317 semantics)
     */
    fun allocate(ctx: Context, guestPkg: String): Int = synchronized(this) {
        slotOf[guestPkg]?.let { if (isSlotAlive(it)) return it }
        val running: Set<String> = runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses?.mapNotNull { it.processName?.lowercase() }?.toSet()
                ?: emptySet()
        }.getOrDefault(emptySet())
        for (s in 0 until MAX_SLOTS) {
            val taken = slotOf.entries.any { (k, v) -> v == s && k != guestPkg }
            if (!taken && "${ctx.packageName.lowercase()}:p$s" !in running) return s
        }
        -1
    }

    /**
     * a7.m() parity — provider handshake:
     *   1. ContentResolver.call(content://com.aether.proxy.content.<slot>, METHOD_INIT, cfg)
     *      → ถ้า :pN ยังไม่เกิด Android spawn ให้เอง (จุดเริ่ม process ที่ framework thật)
     *   2. reply "_Engine_|_client_" = IBinder ของ child → linkToDeath:
     *      guest ตาย → ปล่อย slot อัตโนมัติ (SNAKE a7.java:601 DeathRecipient)
     * คืน false ถ้า handshake ไม่สำเร็จ → caller ยัง launch ด้วย intent-extras
     * แบบเดิมได้ (fallback ไม่ให้พังกว่าเดิม — พิสูจน์แล้วว่า reach onResume)
     */
    fun spawnAndConfig(ctx: Context, cfg: ClientConfig): Boolean {
        val uri = android.net.Uri.parse(providerAuthority(cfg.slot))
        val extras = configToBundle(cfg)
        val reply = try {
            ctx.contentResolver.call(uri, METHOD_INIT, null, extras)
        } catch (e: Throwable) {
            Log.w(TAG, "handshake call slot=${cfg.slot} failed: ${e.message}")
            null
        }
        // snake ProxyContentProvider.java:21-23: reply = putParcelable(_Engine_|_client_)
        // ตัวเดียว — absence ของ binder = refused (ไม่มี success/error flag)
        val client = reply?.getBinder(BUNDLE_CLIENT)
        if (client == null) {
            Log.w(TAG, "slot ${cfg.slot} refused (no ${BUNDLE_CLIENT} in reply)")
            return false
        }
        synchronized(this) {
            try {
                deathOf[cfg.slot]?.let { old -> clientOf[cfg.slot]?.unlinkToDeath(old, 0) }
                val recipient = IBinder.DeathRecipient {
                    Log.i(TAG, "guest :p${cfg.slot} (${cfg.guestPkg}) died — slot released")
                    synchronized(this) {
                        clientOf.remove(cfg.slot)
                        deathOf.remove(cfg.slot)
                        if (slotOf[cfg.guestPkg] == cfg.slot) slotOf.remove(cfg.guestPkg)
                    }
                }
                client.linkToDeath(recipient, 0)
                clientOf[cfg.slot] = client
                deathOf[cfg.slot] = recipient
            } catch (e: Exception) {
                Log.w(TAG, "linkToDeath slot=${cfg.slot}: ${e.message}")
            }
            slotOf[cfg.guestPkg] = cfg.slot
        }
        Log.i(TAG, "handshake OK: ${cfg.guestPkg} → :p${cfg.slot} (a7.m parity)")
        return true
    }
}

/**
 * Child-side holder — ProxyContentProvider ใน process ลูกเรียก handleInit()
 * ตอน framework install provider (= ช่วง bindApplication ก่อน activity dispatch)
 * ทุก component ที่ต้องรู้ identity ของ guest อ่านจาก config นี้ก่อน intent
 */
object GuestProcessHolder {
    private const val TAG = "AetherGuestHolder"

    @Volatile
    var config: ClientConfig? = null
        private set

    /** binder ให้ server linkToDeath — SNAKE jv0 (extends h00.a = Binder) */
    private val clientBinder = Binder()

    /** fallback path (handshake ล้ม): seed จาก intent extras ของ ProxyActivity
     *  — จุดเดียวกันกับที่โค้ดเดิมอ่าน target_package; ให้ consumer มี config
     *  ใช้ได้เสมอ ไม่ว่าทาง ② จะ landing หรือไม่ */
    fun seed(guestPkg: String, slot: Int) {
        if (config == null && guestPkg.isNotEmpty() && slot >= 0) {
            config = ClientConfig(guestPkg, slot, 0)
            Log.i(TAG, "p3 seeded from intent: $guestPkg → slot $slot (fallback)")
        }
    }

    /**
     * audit C14: ล้าง config — เรียกเมื่อ slot ถอดปล่อย/ผูกใหม่ เช่น guest session
     * ก่อนหน้าจบลง (ProxyActivity.onDestroy) เพื่อไม่ให้ identity เก่าชนะ intent ใหม่
     * (เดิม config ไม่เคยถูกล้างเลย = ปุ่ม diag ทิ้ง 'chaincheck' ปินไว้ตลอดชีพ)
     */
    fun reset() {
        val had = config != null
        config = null
        if (had) Log.i(TAG, "p3 config reset (slot released)")
    }

    /** ตอบ ProxyContentProvider.call(METHOD_INIT) — semantics เท่า jv0.P2:285 */
    fun handleInit(extras: Bundle?): Bundle {
        val pkg = extras?.getString(GuestProcessTable.EXTRA_GUEST_PKG)
        val slot = extras?.getInt(GuestProcessTable.EXTRA_SLOT, -1) ?: -1
        if (pkg.isNullOrEmpty() || slot < 0) {
            return Bundle().apply {
                putBoolean(GuestProcessTable.EXTRA_SUCCESS, false)
                putString(GuestProcessTable.EXTRA_ERROR, "bad config")
            }
        }
        val cur = config
        if (cur != null && cur.guestPkg != pkg && cur.guestPkg != DIAG_PKG) {
            // jv0.P2: "Reject init process: X, this process is: Y"
            // ข้อยกเว้นเดียวของ audit C14: config ของปุ่มวินิจฉัย (DIAG_PKG) ไม่
            // มีสิทธิ์ปinned slot ถาวร — session จริงต้อง rebind ทับได้เสมอ
            Log.e(TAG, "Reject init: $pkg — process bound to ${cur.guestPkg} (slot ${cur.slot})")
            return Bundle().apply {
                putBoolean(GuestProcessTable.EXTRA_SUCCESS, false)
                putString(GuestProcessTable.EXTRA_ERROR, "reject:${cur.guestPkg}")
            }
        }
        if (cur != null && cur.guestPkg == DIAG_PKG && pkg != DIAG_PKG) {
            Log.i(TAG, "rebinding over diag config: $DIAG_PKG → $pkg (C14)")
        }
        config = ClientConfig(pkg, slot, extras.getInt(GuestProcessTable.EXTRA_USER_ID, 0))
        Log.i(TAG, "p3 accepted: $pkg → slot $slot (SNAKE jv0.P2 parity)")
        return Bundle().apply {
            putBoolean(GuestProcessTable.EXTRA_SUCCESS, true)
            putBinder(GuestProcessTable.BUNDLE_CLIENT, clientBinder)
        }
    }

    /** package ปลอมที่ chain-check ใช้ — const เดียวทั้ง repo (C14) */
    const val DIAG_PKG = "com.aether.test.chaincheck"
}
