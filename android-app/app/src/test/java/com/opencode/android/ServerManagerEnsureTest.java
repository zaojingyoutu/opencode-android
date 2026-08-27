package com.opencode.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.opencode.android.ServerManager.EnsureAction;

import org.junit.Test;

/**
 * ServerManager.ensureRuntime 的增量升级决策逻辑单元测试。
 *
 * 场景来源:
 *   - 升级只换 opencode 二进制时, 必须保留用户在容器里 apk add 的工具
 *     (否则每次 App 升级工具都丢);
 *   - 基础系统 (alpine/git/libs) 变化极少, 且必须整删重装 (无法安全原地替换 apk 管理的文件);
 *   - 旧版整包指纹 (.opencode-version) 存在时做一次性迁移全量重装。
 */
public class ServerManagerEnsureTest {

    private static final String BASE_V = "a1b2c3d4";
    private static final String BIN_V = "e5f6a7b8";

    @Test
    public void freshInstall_noRootfs_fullExtract() {
        // bin 不存在 → 必须全量 (解 base + 写 bin)
        assertEquals(EnsureAction.FULL_EXTRACT,
                ServerManager.decideEnsure(false, false, false, false));
    }

    @Test
    public void upToDate_nothing() {
        assertEquals(EnsureAction.NOTHING,
                ServerManager.decideEnsure(true, true, true, false));
    }

    @Test
    public void binChanged_binOnly() {
        // 只换二进制 → 原位覆盖, 用户工具保留
        assertEquals(EnsureAction.BIN_ONLY,
                ServerManager.decideEnsure(true, true, false, false));
    }

    @Test
    public void baseChanged_fullExtract() {
        // 基础系统变了 → 整删重装
        assertEquals(EnsureAction.FULL_EXTRACT,
                ServerManager.decideEnsure(true, false, true, false));
    }

    @Test
    public void baseAndBinChanged_fullExtract() {
        assertEquals(EnsureAction.FULL_EXTRACT,
                ServerManager.decideEnsure(true, false, false, false));
    }

    @Test
    public void legacyMarkerForcesFullExtract() {
        // 旧版整包指纹仍在 (即使 base/bin 都对上) → 迁移, 一次性全量
        assertEquals(EnsureAction.FULL_EXTRACT,
                ServerManager.decideEnsure(true, true, true, true));
    }

    @Test
    public void binMissingEvenIfMarkersExist_fullExtract() {
        // bin 文件被删/损坏 → 全量兜底
        assertEquals(EnsureAction.FULL_EXTRACT,
                ServerManager.decideEnsure(false, true, true, false));
    }

    @Test
    public void parseVersionFile_full() {
        assertArrayEquals(new String[]{BASE_V, BIN_V},
                ServerManager.parseVersionFile("base=" + BASE_V + "\nbin=" + BIN_V + "\nopencode=v9.3.1\n"));
    }

    @Test
    public void parseVersionFile_missingBase() {
        assertArrayEquals(new String[]{"", BIN_V},
                ServerManager.parseVersionFile("bin=" + BIN_V + "\n"));
    }

    @Test
    public void parseVersionFile_empty() {
        assertArrayEquals(new String[]{"", ""}, ServerManager.parseVersionFile(""));
        assertArrayEquals(new String[]{"", ""}, ServerManager.parseVersionFile(null));
    }
}
