# -*- coding: utf-8 -*-
"""控制台「排队」tab 的静态契约测试。

排队任务是新加的第 5 个 tab（端上 WorkManager 队列 + 待同步消息队列）。
它依赖三样东西同时在位：

  1. 外壳：tab 按钮、角标、概览指标；
  2. 数据：GET /queue 拉取，且接口挂了不许拖垮整个控制台；
  3. 渲染：按状态分区 + 筛选 + 空态，过期任务单独灰显。

这里只断言静态标记（HTML / JS 文本），因为真跑起来要浏览器；
服务端接口行为由 server/test_queue.py 覆盖，端上采集由 Android 单测覆盖，
归类纯函数规则由 server/test_console_classify.py 覆盖。
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import console_ui  # noqa: E402

HTML = console_ui.CONSOLE_HTML
SW = console_ui.CONSOLE_SW


def test_queue_tab_exists_in_shell():
    assert 'id="tab-queue"' in HTML, "缺少排队 tab 按钮"
    assert "switchTab('queue')" in HTML, "排队 tab 没有接到 switchTab"
    assert 'id="badge-queue"' in HTML, "排队 tab 缺少角标"


def test_queue_overview_metric_exists():
    assert 'id="ov-queue"' in HTML, "概览条缺少排队指标"
    assert "ov-queue" in HTML


def test_queue_is_in_tab_order_everywhere():
    order = "['conv', 'todo', 'queue', 'stat', 'adv']"
    assert HTML.count(order) >= 3, "排队 tab 没进 tab 顺序数组（切换/方向键/初始 tab）"


def test_queue_data_is_fetched_from_queue_api():
    assert "async function fetchQueue(" in HTML, "缺少 fetchQueue"
    assert "api('queue?limit=200'" in HTML, "排队数据应从 GET /queue 拉"


def test_queue_fetch_failure_does_not_break_the_console():
    assert "fetchQueue(ctrl.signal).catch(" in HTML, "排队接口失败必须单独兜住"


def test_queue_render_uses_classify_module_with_fallback():
    assert "function renderQueue(" in HTML, "缺少 renderQueue"
    assert "CC.groupQueueTasks" in HTML, "排队分区应走归类模块"
    assert "!CC || !CC.groupQueueTasks" in HTML, "归类模块缺失时要有平铺降级"


def test_queue_filter_bar_uses_delegated_action():
    assert 'data-action="queue-filter"' in HTML, "排队筛选按钮没有走事件委托"
    assert "action === 'queue-filter'" in HTML, "handleAction 没有处理排队筛选"
    assert 'class="chip-btn" aria-pressed="' in HTML, "筛选按钮应是可访问的原生按钮"


def test_queue_card_marks_state_and_stale():
    assert "function queueCard(" in HTML, "缺少 queueCard"
    for cls in (".q-card {", ".q-card.q-running {", ".q-card.q-queued {",
                ".q-card.q-failed {", ".q-card.q-done {", ".q-card.q-stale {"):
        assert cls in HTML, "缺少排队卡片样式 %s" % cls


def test_queue_summary_shows_five_counters():
    for cell in ("qs-running", "qs-queued", "qs-failed", "qs-done", "qs-stale"):
        assert cell in HTML, "排队汇总缺少 %s" % cell


def test_queue_empty_state_registered():
    assert "emptyStateFor('queue')" in HTML, "排队 tab 没有空态"


def test_service_worker_lets_queue_go_to_network():
    assert "/queue" in SW, "Service Worker 必须放行 /queue，否则会命中离线兜底分支"
    assert "indexOf('/queue') >= 0" in SW, "Service Worker 放行条件写错"


def test_group_dimension_switcher_exists():
    assert 'data-action="conv-dim"' in HTML, "缺少会话内二级归类维度切换"
    assert "function convDimBar(" in HTML, "缺少 convDimBar"
    assert "function setConvGroupDim(" in HTML, "缺少 setConvGroupDim"


def test_group_dimension_is_persisted():
    assert "wx_group_v1" in HTML, "维度选择没有持久化键"
    assert "LS.set('wx_group_v1'" in HTML, "维度切换没有写回 localStorage"


def test_group_dimension_uses_rules_layer():
    assert "CC.groupMessages" in HTML, "会话分组没有走规则层"
    assert "function groupMsgsByDim(" in HTML, "缺少 groupMsgsByDim"
    assert "function convGroupHtml(" in HTML, "缺少 convGroupHtml"


def test_group_dimension_aligns_date_to_local_midnight():
    assert "function dayStartMs(" in HTML, "日期分组必须按当天零点对齐"
    assert "dayStartMs(m && m.timestamp)" in HTML, "getTs 没有走当天零点对齐"


def test_group_dimension_falls_back_when_rules_missing():
    assert "if (!CC || !CC.groupMessages) return null;" in HTML, "规则层缺失没有降级出口"


def test_group_reuses_collapse_event_delegation():
    assert 'data-action="toggle"' in HTML, "折叠组没有复用事件委托"
    assert "aria-expanded=" in HTML, "折叠组缺少 aria-expanded"


def test_queue_device_filter_exists():
    assert 'data-action="queue-device"' in HTML, "缺少排队设备筛选"
    assert "function setQueueDevice(" in HTML, "缺少 setQueueDevice"
    assert "var QUEUE_DEVICES" in HTML, "缺少 QUEUE_DEVICES 状态"


def test_queue_devices_come_from_api_response():
    assert "devices: r.devices || []" in HTML, "fetchQueue 没带设备清单"
    assert "QUEUE_DEVICES = (res[3] && res[3].devices) || [];" in HTML, "load() 没落地设备清单"


def test_queue_device_filter_applies_to_list():
    assert "if (QUEUE_DEVICE) {" in HTML, "设备筛选没有生效到列表"
