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
    assert "api('queue?limit=500'" in HTML, "排队数据应从 GET /queue 拉（取服务端上限 500）"


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


def test_queue_badge_uses_server_side_summary():
    """活跃任务数（概览条 / tab 角标）必须优先用服务端全库 summary。

    服务端 GET /queue 的 summary 是全库口径（不再按当前页统计），
    前端若还去数 QUEUE 数组，任务超一页时角标会一直停在页大小上，
    与概览条（走 /stats）对不上。
    """
    assert "var sm = QUEUE_SUMMARY;" in HTML, "活跃数没有优先读 summary"
    assert "return Number(sm.queued || 0) + Number(sm.running || 0);" in HTML, \
        "summary 口径下活跃 = queued + running"
    # 降级出口：summary 缺失时仍数已加载列表
    assert "if (sm && typeof sm.queued === 'number'" in HTML, "缺少 summary 可用性判定"


def test_queue_summary_card_states_full_library_scope():
    """文案必须说明「汇总＝全库口径、列表最多 500 条」，避免用户误读。"""
    assert "上方汇总为全库口径" in HTML, "排队汇总没有标注全库口径"
    assert "下方列表最多显示最近 500 条" in HTML, "排队列表没有标注条数上限"

def test_queue_device_chip_shows_stale_and_retention_note():
    """设备 chip 的「活跃」必须只算未过期，并把过期条数单独标出来。

    设备维度以前直接数 state（不看 TTL）：同一时刻设备 chip 说「2 活跃」、
    上方汇总说 running 0。现在 chip 走 devices[].active（已过 TTL 口径），
    额外的 devices[].stale 渲染成「· N 已过期」，并在脚注说明保留期清理。
    """
    assert "var sl = Number((d && d.stale) || 0);" in HTML, \
        "设备 chip 没有读 stale 计数"
    assert "' + esc(sl) + ' 已过期'" in HTML, \
        "设备 chip 没有渲染已过期条数"
    assert "7 天前的行会在读取或上报时顺带清掉" in HTML, \
        "排队脚注没有说明保留期清理"


def test_queue_state_chip_counts_use_server_summary():
    """状态 chip 的条数必须走 summary（全库口径），不能数当前页。

    真机口径：某设备 600 条排队中 + 20 条已完成，GET /queue?limit=500 的 tasks
    只有 500 条排队中。chip 若数列表，会显示「已完成 0」而汇总卡显示「已完成 20」，
    同一屏两个数字打架。现在两者同源（QUEUE_SUMMARY），降级出口才退回数列表。
    """
    assert "function queueStateTotal(" in HTML, "缺少状态计数 helper"
    assert "if (sm && typeof sm[st] === 'number') return Number(sm[st] || 0);" in HTML, \
        "状态计数没有优先读 summary"
    assert "var cnt = queueStateTotal(st);" in HTML, "chip 没有走状态计数 helper"
    # 旧写法（在 chip 里遍历 QUEUE 数 state）必须已经消失
    assert "for (var i = 0; i < QUEUE.length; i++) {\r\n      if ((QUEUE[i].state || '') === st) cnt++;" not in HTML, \
        "chip 里还留着按当前页计数的旧循环"


def test_queue_note_states_chip_scope():
    """脚注要说清 chip 数字也是全库口径，免得用户拿它和列表条数对。"""
    assert "状态 chip 上的数字同样是全库口径" in HTML, "脚注没有说明 chip 口径"
    assert "过期行只计入「已过期」" in HTML, "脚注没有说明过期行不计入四状态"


def test_queue_empty_state_distinguishes_page_and_library():
    """列表空但全库有命中时，别只说「当前没有排队任务」。

    任务超一页时选「已完成」、这一页全是排队中：列表为空、chip 说全库 20 条。
    此时空态必须解释「当前页没有 / 全库有多少」，否则用户以为任务丢了。
    """
    assert "function queueEmptyHtml(" in HTML, "缺少排队空态裁决函数"
    assert "html: queueEmptyHtml()" in HTML, "renderQueue 没用空态裁决函数"
    assert "'当前页没有' + (name || '') + '任务'" in HTML, "空态没有区分当前页与全库"
    assert "它们不在这最近 500 条里" in HTML, "空态没有解释为什么不在这页"
    # 搜索 / 设备筛选是「本页里挑」，命中 0 时不能拿全库数安慰
    assert "if (QUERY.trim() || QUEUE_DEVICE) return emptyStateFor('queue');" in HTML, \
        "搜索、设备筛选为 0 命中时必须退回通用空态"


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
