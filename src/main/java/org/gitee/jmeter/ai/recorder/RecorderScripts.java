package org.gitee.jmeter.ai.recorder;

/**
 * 注入脚本：捕获阶段 UI 事件监听（click/change/submit、密码只记动作）
 * + 可拖拽悬浮控制条（预备「● 开始录制」/录制「■ 停止录制 (N)」，位置存 sessionStorage）+ binding 回传控制指令。
 *
 * <p><b>安全边界</b>：本脚本运行在 CDP **隔离世界**（{@code __jaiRecWorld}），
 * binding {@code __jaiRec} 也只绑定在该世界。页面主世界脚本既看不到 binding 也拿不到脚本
 * 内部状态，无法伪造控制指令或窃取令牌——因此控制指令不再需要令牌（令牌方案可被页面
 * 覆盖全局 setter 窃取，已被真实浏览器实证绕过）。隔离世界仍可监听 DOM 事件并渲染悬浮条。
 *
 * <p>其余要点：经 {@code Page.addScriptToEvaluateOnNewDocument}（{@code worldName} +
 * {@code runImmediately}，覆盖既有文档）每页注入、SPA 导航存活、注入不受页面 CSP 限制；
 * 样式全走 CSSOM（el.style）不吃 style-src；双重安装守卫；悬浮条自身的事件不进 UI 事件流；
 * {@code __jaiRecSetState(state, count)} 由引擎经隔离世界 evaluate 推送。
 */
public final class RecorderScripts {

    public static final String INIT_SCRIPT = """
            (function () {
              if (window.__jaiRecInstalled) return;
              window.__jaiRecInstalled = true;

              function send(obj) {
                // binding 注册与脚本求值存在早期窗口竞态：注册前静默丢回传会让悬浮条
                // 点击无声失效——有界重试（200ms × 25 = 5s）兜住该窗口
                var s = JSON.stringify(obj);
                var tries = 0;
                (function go() {
                  try {
                    if (typeof window.__jaiRec === 'function') { window.__jaiRec(s); return; }
                  } catch (e) {}
                  if (++tries < 25) setTimeout(go, 200);
                })();
              }
              function selectorOf(el) {
                if (!el || el === document) return 'document';
                var parts = [];
                var node = el, depth = 0;
                while (node && node.nodeType === 1 && depth < 4) {
                  var part = node.tagName.toLowerCase();
                  if (node.id) { parts.unshift(part + '#' + node.id); break; }
                  var parent = node.parentElement;
                  if (parent) part += ':nth-child(' + (Array.prototype.indexOf.call(parent.children, node) + 1) + ')';
                  parts.unshift(part);
                  node = parent; depth++;
                }
                return parts.join('>');
              }

              var pill = document.createElement('div');
              var state = 'armed', count = 0;
              var POS_KEY = '__jaiRecPillPos';
              var savedPos = null;
              try { savedPos = JSON.parse(sessionStorage.getItem(POS_KEY) || 'null'); } catch (e) {}
              function clampPos(x, y) {
                var maxX = Math.max(0, window.innerWidth - pill.offsetWidth);
                var maxY = Math.max(0, window.innerHeight - pill.offsetHeight);
                return { x: Math.min(Math.max(0, x), maxX), y: Math.min(Math.max(0, y), maxY) };
              }
              function render() {
                var s = pill.style;
                s.position = 'fixed'; s.zIndex = '2147483647'; s.userSelect = 'none';
                if (savedPos) {
                  var rp = clampPos(savedPos.x, savedPos.y);
                  s.left = rp.x + 'px'; s.top = rp.y + 'px'; s.right = 'auto';
                }
                else { s.top = '12px'; s.right = '12px'; }
                s.padding = '6px 14px'; s.borderRadius = '999px'; s.cursor = 'pointer';
                s.font = '12px/1.4 system-ui, sans-serif'; s.color = '#fff';
                s.boxShadow = '0 2px 10px rgba(0,0,0,.35)';
                s.background = state === 'recording' ? 'rgba(220,38,38,.92)' : 'rgba(23,23,23,.85)';
                pill.textContent = state === 'recording'
                  ? '\\u25A0 停止录制' + (count ? ' (' + count + ')' : '')
                  : '\\u25CF 开始录制';
              }
              window.__jaiRecSetState = function (s, c) {
                if (typeof s === 'string') state = s;
                if (typeof c === 'number') count = c;
                render();
              };
              // 拖拽换位：按住移动 >3px 视为拖拽，位置存 sessionStorage——SPA/刷新存活，
              // 跨标签不迁移；释放点在胶囊外时 click 派发到公共祖先而非按压点，一律吞掉
              // （防边缘误滑把「按住胶囊」记成页面点击/误触开始停止）
              var dragOffX = 0, dragOffY = 0, dragBase = null;
              var dragging = false, moved = false, pendingDragClick = false;
              pill.addEventListener('mousedown', function (e) {
                if (e.button !== 0) return;
                dragging = true; moved = false;
                var r = pill.getBoundingClientRect();
                dragOffX = e.clientX - r.left; dragOffY = e.clientY - r.top;
                dragBase = { x: r.left, y: r.top };
                e.preventDefault();
              });
              document.addEventListener('mousedown', function (e) {
                // 残拖清理：丢过 mouseup（窗外/跨 iframe 释放）后按到页面上，立即终止
                if (dragging && !inPill(e)) dragging = false;
              }, true);
              document.addEventListener('mousemove', function (e) {
                if (!dragging) return;
                if (!(e.buttons & 1)) { dragging = false; return; } // 松键事件已丢失：中止粘拖
                var p = clampPos(e.clientX - dragOffX, e.clientY - dragOffY);
                if (Math.abs(p.x - dragBase.x) > 3 || Math.abs(p.y - dragBase.y) > 3) moved = true;
                if (moved) {
                  savedPos = p;
                  pill.style.left = p.x + 'px'; pill.style.top = p.y + 'px'; pill.style.right = 'auto';
                }
              }, true);
              document.addEventListener('mouseup', function (e) {
                if (!dragging) return;
                dragging = false;
                if (moved) {
                  try { sessionStorage.setItem(POS_KEY, JSON.stringify(savedPos)); } catch (err) {}
                }
                if (moved || !inPill(e)) pendingDragClick = true;
              }, true);
              pill.addEventListener('click', function (ev) {
                ev.stopPropagation(); ev.preventDefault();
                if (pendingDragClick) { pendingDragClick = false; return; }
                if (state === 'recording') { state = 'armed'; count = 0; send({ t: 'ctrl', a: 'stop' }); }
                else { state = 'recording'; send({ t: 'ctrl', a: 'begin' }); }
                render();
              });
              function inPill(e) { return e.target && pill.contains(e.target); }

              document.addEventListener('click', function (e) {
                if (inPill(e)) return;
                if (pendingDragClick) { pendingDragClick = false; return; }
                var el = e.target;
                var text = (el && el.textContent ? el.textContent : '').trim().replace(/ +/g, ' ').slice(0, 80);
                send({ t: 'ui', ev: 'click', url: location.href, selector: selectorOf(el),
                       tag: el && el.tagName ? el.tagName.toLowerCase() : '', text: text });
              }, true);
              document.addEventListener('change', function (e) {
                if (inPill(e)) return;
                var el = e.target;
                if (!el || el.nodeType !== 1) return;
                var d = { t: 'ui', ev: 'change', url: location.href, selector: selectorOf(el),
                          tag: el.tagName.toLowerCase(), name: el.name || el.id || '' };
                if ((el.type || '').toLowerCase() === 'password') d.redacted = true;
                else d.value = String(el.value == null ? '' : el.value).slice(0, 200);
                send(d);
              }, true);
              document.addEventListener('submit', function (e) {
                send({ t: 'ui', ev: 'submit', url: location.href, selector: selectorOf(e.target) });
              }, true);

              function mount() {
                if (!document.documentElement) return;
                document.documentElement.appendChild(pill);
                render();
              }
              if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount, true);
              else mount();
            })();
            """;

    /** 引擎推送悬浮条状态的表达式（在隔离世界中求值）。 */
    public static String stateExpression(String state, long count) {
        return "window.__jaiRecSetState && window.__jaiRecSetState('" + state + "'," + count + ")";
    }

    private RecorderScripts() {
    }
}
