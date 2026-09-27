package com.opencode.android;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.junit.Test;

/**
 * ServerService.permStateOf 三态映射的单元测试。
 * 僵尸审批卡自愈 pill 全靠这个快照判定 server 是否真无待批：
 * 只有 server 确认空 (EMPTY) 才允许提示刷新, 其他一律藏 pill (fail-closed)。
 */
public class ServerServicePermStateTest {

    @Test
    public void nullMeansUnknown() {
        assertEquals(ServerService.PERM_UNKNOWN, ServerService.permStateOf(null));
    }

    @Test
    public void emptyMeansConfirmedEmpty() throws Exception {
        assertEquals(ServerService.PERM_EMPTY, ServerService.permStateOf(new JSONArray("[]")));
    }

    @Test
    public void nonEmptyMeansPending() throws Exception {
        assertEquals(ServerService.PERM_NONEMPTY,
                ServerService.permStateOf(new JSONArray("[{\"id\":\"perm_1\"}]")));
    }
}
