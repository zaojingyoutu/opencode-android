package com.opencode.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * ServerManager.parseStatus 纯解析逻辑的单元测试。
 * 这些分支全是实测踩坑修出来的 (limit=1 升序、孤儿消息、长命令 tool part),
 * 没有测试时每次改动都靠真机回归, 代价太高。
 */
public class ServerManagerStatusTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long MIN = 60_000L;

    private static String session(String id, String title, long updated) throws Exception {
        return new JSONObject()
                .put("id", id)
                .put("title", title)
                .put("time", new JSONObject().put("updated", updated))
                .toString();
    }

    private static String msg(String role, long created, boolean completed,
            boolean error, String partsJson) throws Exception {
        JSONObject time = new JSONObject().put("created", created);
        if (completed) time.put("completed", created + 1000);
        JSONObject info = new JSONObject()
                .put("role", role)
                .put("time", time);
        if (error) info.put("error", new JSONObject().put("name", "TestError"));
        return new JSONObject().put("info", info).put("parts", new JSONArray(partsJson)).toString();
    }

    private static ServerManager.Status parse(String sessionsJson, String msgsJson)
            throws Exception {
        return ServerManager.parseStatus(new JSONArray(sessionsJson),
                new JSONArray(msgsJson), NOW);
    }

    // ---- 会话选择 ----

    @Test
    public void emptySessions_unavailable() throws Exception {
        ServerManager.Status s = parse("[]", "[]");
        assertEquals(-1, s.sessionUpdated);
        assertFalse(s.pending);
        assertFalse(s.replying);
    }

    @Test
    public void picksLatestUpdatedSession() throws Exception {
        String sessions = "[" + session("ses_old", "old", NOW - MIN)
                + "," + session("ses_new", "new", NOW) + "]";
        String msgs = "[" + msg("user", NOW - MIN, true, false, "[]") + "]";
        ServerManager.Status s = parse(sessions, msgs);
        assertEquals("ses_new", s.sessionId);
        assertEquals("new", s.sessionTitle);
    }

    // ---- 消息选择 (limit=1 升序回归) ----

    @Test
    public void ascendingMessages_lastElementIsLatest() throws Exception {
        // 回归: 该端点升序返回, 取第一条 (已完成的 user 消息) 曾导致 replying 恒 false
        String textPart = "[{\"type\":\"text\",\"time\":{\"start\":" + (NOW - MIN)
                + ",\"end\":" + (NOW - MIN + 1000) + "},\"text\":\"生成中\"}]";
        String msgs = "["
                + msg("user", NOW - 5 * MIN, true, false, "[]") + ","
                + msg("assistant", NOW - MIN, false, false, textPart) + "]";
        String sessions = "[" + session("ses_1", "t", NOW) + "]";
        ServerManager.Status s = parse(sessions, msgs);
        assertTrue(s.pending);
        assertTrue(s.replying);
        assertEquals("生成中", s.lastText);
    }

    // ---- pending / replying / error ----

    @Test
    public void completedMessage_notPending() throws Exception {
        String msgs = "[" + msg("assistant", NOW - MIN, true, false, "[]") + "]";
        ServerManager.Status s = parse("[" + session("ses_1", "t", NOW) + "]", msgs);
        assertFalse(s.pending);
        assertFalse(s.replying);
        assertFalse(s.error);
    }

    @Test
    public void erroredMessage_errorFlag() throws Exception {
        String msgs = "[" + msg("assistant", NOW - MIN, false, true, "[]") + "]";
        ServerManager.Status s = parse("[" + session("ses_1", "t", NOW) + "]", msgs);
        assertFalse(s.pending);
        assertTrue(s.error);
    }

    @Test
    public void freshPendingText_replying() throws Exception {
        String textPart = "[{\"type\":\"text\",\"time\":{\"start\":" + (NOW - 30_000)
                + ",\"end\":" + (NOW - 20_000) + "},\"text\":\"部分输出\"}]";
        String msgs = "[" + msg("assistant", NOW - 30_000, false, false, textPart) + "]";
        ServerManager.Status s = parse("[" + session("ses_1", "t", NOW) + "]", msgs);
        assertTrue(s.pending);
        assertTrue(s.replying);
    }

    @Test
    public void stalePendingNoTool_notReplying() throws Exception {
        // 纯文本生成停滞超过新鲜度窗口 (孤儿) → pending 但不在回复
        String textPart = "[{\"type\":\"text\",\"time\":{\"start\":" + (NOW - 30 * MIN)
                + ",\"end\":" + (NOW - 30 * MIN + 1000) + "},\"text\":\"停住了\"}]";
        String msgs = "[" + msg("assistant", NOW - 30 * MIN, false, false, textPart) + "]";
        ServerManager.Status s = parse("[" + session("ses_1", "t", NOW) + "]", msgs);
        assertTrue(s.pending);
        assertFalse(s.replying);
    }

    @Test
    public void runningTool_alwaysReplying_evenWhenStale() throws Exception {
        // 关键回归: 长命令 (构建/下载) 期间无任何时间戳更新, 未结束的 tool part
        // 必须视为回复中, 否则看护线程放锁冻死任务 (实测"会话自己断开"的根源)
        String toolPart = "[{\"type\":\"tool\",\"tool\":\"bash\","
                + "\"state\":{\"status\":\"running\","
                + "\"time\":{\"start\":" + (NOW - 40 * MIN) + "}}}]";
        String msgs = "[" + msg("assistant", NOW - 40 * MIN, false, false, toolPart) + "]";
        ServerManager.Status s = parse("[" + session("ses_1", "t", NOW) + "]", msgs);
        assertTrue(s.pending);
        assertTrue(s.replying);
    }

    @Test
    public void toolTimeWindowOpen_countsAsRunning() throws Exception {
        // status 字段缺失时, 时间窗开了没关也算运行中
        String toolPart = "[{\"type\":\"tool\",\"tool\":\"bash\","
                + "\"state\":{\"time\":{\"start\":" + (NOW - 40 * MIN) + "}}}]";
        String msgs = "[" + msg("assistant", NOW - 40 * MIN, false, false, toolPart) + "]";
        ServerManager.Status s = parse("[" + session("ses_1", "t", NOW) + "]", msgs);
        assertTrue(s.replying);
    }

    // ---- 待批准工具 (等待用户权限) ----

    @Test
    public void pendingTool_waitingApproval() throws Exception {
        // state.status == "pending" 是权限未批, agent 被卡住等用户批准 (与 running 区分)
        String toolPart = "[{\"type\":\"tool\",\"tool\":\"bash\","
                + "\"state\":{\"status\":\"pending\",\"raw\":\"rm -rf build\",\"input\":{}}}]";
        String msgs = "[" + msg("assistant", NOW - MIN, false, false, toolPart) + "]";
        ServerManager.Status s = parse("[" + session("ses_1", "t", NOW) + "]", msgs);
        assertTrue(s.waitingApproval);
        assertEquals("bash rm -rf build", s.permissionText);
    }

    @Test
    public void runningTool_notWaitingApproval() throws Exception {
        // 已批准的 running 不算待批准, 否则会把正在跑的长命令误报成"等你批准"
        String toolPart = "[{\"type\":\"tool\",\"tool\":\"bash\","
                + "\"state\":{\"status\":\"running\",\"input\":{},\"time\":{\"start\":" + (NOW - MIN) + "}}}]";
        String msgs = "[" + msg("assistant", NOW - MIN, false, false, toolPart) + "]";
        ServerManager.Status s = parse("[" + session("ses_1", "t", NOW) + "]", msgs);
        assertFalse(s.waitingApproval);
    }

    @Test
    public void pendingTool_summaryFallsBackToToolName() throws Exception {
        String toolPart = "[{\"type\":\"tool\",\"tool\":\"webfetch\","
                + "\"state\":{\"status\":\"pending\",\"input\":{},\"raw\":\"\"}}]";
        String msgs = "[" + msg("assistant", NOW - MIN, false, false, toolPart) + "]";
        ServerManager.Status s = parse("[" + session("ses_1", "t", NOW) + "]", msgs);
        assertTrue(s.waitingApproval);
        assertEquals("webfetch", s.permissionText);
    }

    @Test
    public void pendingTool_summaryTruncatedTo50() throws Exception {
        StringBuilder cmd = new StringBuilder("echo ");
        for (int i = 0; i < 60; i++) cmd.append("字");
        String toolPart = "[{\"type\":\"tool\",\"tool\":\"bash\","
                + "\"state\":{\"status\":\"pending\",\"raw\":\"" + cmd + "\"}}]";
        String msgs = "[" + msg("assistant", NOW - MIN, false, false, toolPart) + "]";
        ServerManager.Status s = parse("[" + session("ses_1", "t", NOW) + "]", msgs);
        assertTrue(s.waitingApproval);
        // 65 字输入截断到 50 + 省略号, 前缀 "bash "
        assertEquals("bash " + "echo " + "字".repeat(45) + "…", s.permissionText);
    }

    // ---- 通知摘要 ----

    @Test
    public void lastText_takesLastTextPart_andFoldsWhitespace() throws Exception {
        String parts = "[{\"type\":\"text\",\"text\":\"第一段\\n  多行\"},"
                + "{\"type\":\"text\",\"text\":\"最终  结果   文本\"}]";
        String msgs = "[" + msg("assistant", NOW - MIN, false, false, parts) + "]";
        ServerManager.Status s = parse("[" + session("ses_1", "标题", NOW) + "]", msgs);
        assertEquals("最终 结果 文本", s.lastText);
        assertEquals("标题", s.sessionTitle);
    }

    @Test
    public void lastText_truncatedTo100() throws Exception {
        StringBuilder long_ = new StringBuilder();
        for (int i = 0; i < 200; i++) long_.append("字");
        String parts = "[{\"type\":\"text\",\"text\":\"" + long_ + "\"}]";
        String msgs = "[" + msg("assistant", NOW - MIN, false, false, parts) + "]";
        ServerManager.Status s = parse("[" + session("ses_1", "t", NOW) + "]", msgs);
        assertEquals(101, s.lastText.length()); // 100 + 省略号
        assertTrue(s.lastText.endsWith("…"));
    }

    @Test
    public void noMessages_safeDefaults() throws Exception {
        ServerManager.Status s = parse("[" + session("ses_1", "t", NOW) + "]", "[]");
        assertEquals("ses_1", s.sessionId);
        assertFalse(s.pending);
        assertEquals("", s.lastText);
    }
}
