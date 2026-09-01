package com.opencode.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 通知纯逻辑的单元测试 (弹窗文案 / 按钮点击 / 聊天框接收的 SSE 事件解析)。
 * 这些逻辑原来都嵌在 Android 组件 (ServerService) 里没法测, 已抽成
 * ServerManager 的静态纯函数: permissionNotice / permReplyLabel /
 * permissionReplyUrl / permissionReplyBody / sseEventKind / ssePermissionPayload。
 */
public class ServerManagerNotifTest {

    // ---- 弹窗文案: 标题兜底 + 详情提取 + 正文组装 ----

    @Test
    public void permissionNotice_usesSessionTitleWhenPresent() throws Exception {
        JSONObject req = new JSONObject()
                .put("id", "req_1")
                .put("permission", "bash")
                .put("metadata", new JSONObject().put("command", "ls -la"));
        ServerManager.PermissionNotice n = ServerManager.permissionNotice(req, "我的会话");
        assertEquals("req_1", n.id);
        assertEquals("我的会话", n.title);
        assertEquals("AI 请求: ls -la", n.text);
    }

    @Test
    public void permissionNotice_fallsBackToOpenCodeTitle() throws Exception {
        JSONObject req = new JSONObject().put("id", "req_1");
        ServerManager.PermissionNotice n = ServerManager.permissionNotice(req, "");
        assertEquals("OpenCode", n.title);
    }

    @Test
    public void permissionNotice_detailFallsBackToPattern() throws Exception {
        // metadata.command 缺失 → 退回 patterns[0]
        JSONObject req = new JSONObject()
                .put("id", "req_1")
                .put("patterns", new org.json.JSONArray(new String[]{"rm -rf build"}));
        ServerManager.PermissionNotice n = ServerManager.permissionNotice(req, "t");
        assertEquals("AI 请求: rm -rf build", n.text);
    }

    @Test
    public void permissionNotice_detailUsesCommandOverPattern() throws Exception {
        // 两者都有时 command 优先
        JSONObject req = new JSONObject()
                .put("id", "req_1")
                .put("metadata", new JSONObject().put("command", "git push"))
                .put("patterns", new org.json.JSONArray(new String[]{"old"}));
        ServerManager.PermissionNotice n = ServerManager.permissionNotice(req, "t");
        assertEquals("AI 请求: git push", n.text);
    }

    @Test
    public void permissionNotice_noDetailFallsBackToPermType() throws Exception {
        JSONObject req = new JSONObject().put("id", "req_1").put("permission", "webfetch");
        ServerManager.PermissionNotice n = ServerManager.permissionNotice(req, "t");
        assertEquals("AI 请求 webfetch", n.text);
    }

    @Test
    public void permissionNotice_noDetailNoPermType_placeholder() throws Exception {
        ServerManager.PermissionNotice n = ServerManager.permissionNotice(new JSONObject(), "t");
        assertEquals("AI 请求批准操作", n.text);
    }

    @Test
    public void permissionNotice_nullReq_safeDefaults() {
        ServerManager.PermissionNotice n = ServerManager.permissionNotice(null, "");
        assertEquals("", n.id);
        assertEquals("OpenCode", n.title);
        assertEquals("AI 请求批准操作", n.text);
    }

    // ---- 按钮点击: 回复文案 + 接口 URL + 请求体 ----

    @Test
    public void permReplyLabel_reject_isDenied() {
        assertEquals("已拒绝", ServerManager.permReplyLabel("reject"));
    }

    @Test
    public void permReplyLabel_once_isApproved() {
        assertEquals("已批准", ServerManager.permReplyLabel("once"));
    }

    @Test
    public void permReplyLabel_unknown_approvedByDefault() {
        assertEquals("已批准", ServerManager.permReplyLabel(""));
    }

    @Test
    public void permissionReplyUrl_buildsEndpoint() {
        assertEquals("http://127.0.0.1:18888/permission/req_9/reply",
                ServerManager.permissionReplyUrl("http://127.0.0.1:18888", "req_9"));
    }

    @Test
    public void permissionReplyBody_once() {
        assertEquals("{\"reply\":\"once\"}", ServerManager.permissionReplyBody("once"));
    }

    @Test
    public void permissionReplyBody_reject() {
        assertEquals("{\"reply\":\"reject\"}", ServerManager.permissionReplyBody("reject"));
    }

    // ---- 聊天框接收: SSE 事件分类 + 权限事件 payload 提取 ----

    @Test
    public void sseEventKind_permissionAsked() {
        assertEquals("permission",
                ServerManager.sseEventKind("{\"type\":\"permission.asked\",\"properties\":{\"id\":\"r1\"}}"));
    }

    @Test
    public void sseEventKind_messageUpdated() {
        assertEquals("message",
                ServerManager.sseEventKind("{\"type\":\"message.updated\",\"properties\":{}}"));
    }

    @Test
    public void sseEventKind_partUpdated_isMessage() {
        assertEquals("message",
                ServerManager.sseEventKind("{\"type\":\"message.part.updated\",\"properties\":{}}"));
    }

    @Test
    public void sseEventKind_unknown_isEmpty() {
        assertEquals("", ServerManager.sseEventKind("{\"type\":\"session.idle\"}"));
    }

    @Test
    public void sseEventKind_null_isEmpty() {
        assertEquals("", ServerManager.sseEventKind(null));
    }

    @Test
    public void ssePermissionPayload_propertiesPreferred() throws Exception {
        // 事件负载在 properties 里
        JSONObject p = ServerManager.ssePermissionPayload(
                "{\"type\":\"permission.asked\",\"properties\":{\"id\":\"r1\",\"permission\":\"bash\"}}");
        assertEquals("r1", p.optString("id"));
        assertEquals("bash", p.optString("permission"));
    }

    @Test
    public void ssePermissionPayload_flatFallback() throws Exception {
        // 扁平结构 (无 properties) 时整个事件对象即请求对象
        JSONObject p = ServerManager.ssePermissionPayload(
                "{\"type\":\"permission.asked\",\"id\":\"r2\",\"permission\":\"edit\"}");
        assertEquals("r2", p.optString("id"));
        assertEquals("edit", p.optString("permission"));
    }

    @Test
    public void ssePermissionPayload_noId_returnsNull() throws Exception {
        assertNull(ServerManager.ssePermissionPayload("{\"type\":\"permission.asked\",\"properties\":{}}"));
    }

    @Test
    public void ssePermissionPayload_propertiesWithoutId_usesFlat() throws Exception {
        // properties 存在但没有 id → 退回用整个事件对象找 id
        JSONObject p = ServerManager.ssePermissionPayload(
                "{\"type\":\"permission.asked\",\"id\":\"r3\",\"properties\":{\"permission\":\"bash\"}}");
        assertEquals("r3", p.optString("id"));
    }

    // ---- 待批准请求逐条去重 (多会话/多目录并存时, 每个请求都要弹横幅) ----

    @Test
    public void pendingNotifications_emptyPerms_returnsEmpty() {
        assertTrue(ServerManager.pendingNotifications(new org.json.JSONArray(), "").isEmpty());
    }

    @Test
    public void pendingNotifications_nullPerms_returnsEmpty() {
        assertTrue(ServerManager.pendingNotifications(null, "").isEmpty());
    }

    @Test
    public void pendingNotifications_returnsAllUnnotified() throws Exception {
        JSONArray perms = new org.json.JSONArray()
                .put(new JSONObject().put("id", "req_a").put("permission", "bash"))
                .put(new JSONObject().put("id", "req_b").put("permission", "edit"));
        java.util.List<JSONObject> out = ServerManager.pendingNotifications(perms, "");
        assertEquals(2, out.size());
        assertEquals("req_a", out.get(0).optString("id"));
        assertEquals("req_b", out.get(1).optString("id"));
    }

    @Test
    public void pendingNotifications_skipsAlreadyNotified() throws Exception {
        // 第二个会话/目录的请求 (req_b) 未通知过, 即使列表里第一个 (req_a) 已通知也要返回它 —
        // 回归: 之前只取 perms[0], 新目录请求被旧目录请求盖住, 横幅永远显示第一条
        JSONArray perms = new org.json.JSONArray()
                .put(new JSONObject().put("id", "req_a"))
                .put(new JSONObject().put("id", "req_b"));
        java.util.List<JSONObject> out = ServerManager.pendingNotifications(perms, "req_a,");
        assertEquals(1, out.size());
        assertEquals("req_b", out.get(0).optString("id"));
    }

    @Test
    public void pendingNotifications_skipsEmptyAndNoId() throws Exception {
        JSONArray perms = new org.json.JSONArray()
                .put(new JSONObject().put("id", "req_a"))
                .put(new JSONObject());
        java.util.List<JSONObject> out = ServerManager.pendingNotifications(perms, "req_a,");
        assertTrue(out.isEmpty());
    }
}
