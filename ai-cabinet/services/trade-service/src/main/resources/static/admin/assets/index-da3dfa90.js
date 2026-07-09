(function(){const t=document.createElement("link").relList;if(t&&t.supports&&t.supports("modulepreload"))return;for(const n of document.querySelectorAll('link[rel="modulepreload"]'))a(n);new MutationObserver(n=>{for(const i of n)if(i.type==="childList")for(const c of i.addedNodes)c.tagName==="LINK"&&c.rel==="modulepreload"&&a(c)}).observe(document,{childList:!0,subtree:!0});function s(n){const i={};return n.integrity&&(i.integrity=n.integrity),n.referrerPolicy&&(i.referrerPolicy=n.referrerPolicy),n.crossOrigin==="use-credentials"?i.credentials="include":n.crossOrigin==="anonymous"?i.credentials="omit":i.credentials="same-origin",i}function a(n){if(n.ep)return;n.ep=!0;const i=s(n);fetch(n.href,i)}})();const ss="admin_theme";function as(){return localStorage.getItem(ss)||"dark"}function ns(e){const t=e==="light"?"light":"dark";document.documentElement.setAttribute("data-theme",t),localStorage.setItem(ss,t);const s=document.getElementById("themeToggle");s&&(s.textContent=t==="dark"?"浅色":"深色",s.title=t==="dark"?"切换为浅色主题":"切换为深色主题",s.setAttribute("aria-label",s.title))}function va(){ns(as())}function ba(){ns(as()==="dark"?"light":"dark")}function o(e){return e==null?"":String(e).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;").replace(/'/g,"&#39;")}function r(e){return o(e).replace(/`/g,"&#96;")}function Ct(e){return(e||"").replace(/\D/g,"").slice(0,11)}function fa(e){return/^1\d{10}$/.test(Ct(e))}function ga(){return"请输入11位有效手机号"}function he(e){if(!e)return"未知错误";const t=e.message&&String(e.message).trim()||"";if(t&&/[\u4e00-\u9fff]/.test(t))return t;const s=t.toLowerCase();return s.includes("missing token")||s.includes("invalid token")?"登录已失效，请重新登录":s.includes("permission denied")?"无权限执行此操作":s.includes("consumer")||s.includes("operator")?"请使用运营账号登录后台":s.includes("device not found")||s.includes("device_not_found")?"设备不存在":s.includes("session_state")||s.includes("session state")?"会话状态异常，请刷新页面":s.includes("occupied")||s.includes("busy")?"设备使用中，请稍后再试":s.includes("balance")?"余额不足":s.includes("blacklist")?"账号受限":t||"请求失败"}function ya(e){const t=he(e);return e&&(e.status===401||e.status===403||/401|403|登录已失效|无权限|权限不足/i.test(t))}function b(e){return ya(e)?(typeof logout=="function"&&logout(),typeof showErr=="function"&&showErr("loginErr",e.status===403?"权限不足或登录已失效，请重新登录":"登录已失效，请重新登录"),!0):!1}function $a(e){const t=e||"-",a={PAID:"已支付",PENDING:"待支付",REFUNDED:"已退款",CANCELLED:"已取消"}[t]||t;return`<span class="badge ${t==="PAID"?"badge-done":t==="PENDING"?"badge-active":t==="REFUNDED"?"badge-fail":t==="CANCELLED"?"badge-offline":"badge-active"}">${o(a)}</span>`}const Ia={CREATED:"已创建",OPENING:"开门中",SHOPPING:"购物中",RECOGNIZING:"识别商品中",WAITING_UPLOAD:"录像上传中",SETTLING:"结算中",COMPLETED:"已完成",DISPUTED:"待审核",FAILED:"失败",CANCELLED:"已取消"};function Rt(e){return Ia[e]||e||"-"}function ka(e){return e?`<span class="badge ${["COMPLETED","CANCELLED"].includes(e)?"badge-done":["FAILED","DISPUTED"].includes(e)?"badge-fail":"badge-active"}">${o(Rt(e))}</span>`:"-"}const wa={NONE:"无需上传",LOCAL_QUEUED:"待上传",UPLOADING:"上传中",UPLOADED:"已上传",FAILED:"上传失败"};function os(e){return wa[e]||e||"-"}function Ea(e){const s=String(e||"UNKNOWN").toUpperCase()==="ONLINE",a=is(e);return`<span class="badge ${s?"badge-online":"badge-offline"}">${o(a)}</span>`}function is(e){const t=String(e||"UNKNOWN").toUpperCase();return t==="ONLINE"?"在线":t==="OFFLINE"?"离线":"未知"}function et(e){if(!(String((e==null?void 0:e.onlineStatus)||"").toUpperCase()==="ONLINE"))return{tone:"offline",title:"离线",hint:"设备暂未联网，顾客可能无法开门购物"};if(e!=null&&e.activeSessionId){const s=Rt(e.activeSessionState);return{tone:"busy",title:s,hint:`有顾客正在${s}，暂不可开门购物`}}return e!=null&&e.replenishmentInProgress?{tone:"busy",title:"补货中",hint:"运营补货进行中，暂不可开门购物"}:{tone:"idle",title:"空闲",hint:"可正常开门购物"}}function Sa(e){const t=et(e);return`<span class="device-run-badge ${t.tone}"><span class="dot" aria-hidden="true"></span>${o(t.title)}</span>`}function cs(e,t={}){const s=t.fmtTime||(i=>i||"-"),a=(e||[]).map(i=>{const c=et(i),l=r(i.deviceId);return`<button type="button" class="device-live-card ${c.tone}" onclick="viewDeviceDetail('${l}')" title="${r(c.hint)}">
      <div class="device-live-top">
        <span class="device-live-dot" aria-hidden="true"></span>
        <span class="device-live-state">${o(c.title)}</span>
      </div>
      <div class="device-live-name">${o(i.deviceName||i.deviceId)}</div>
      <code class="device-live-id">${o(i.deviceId)}</code>
      <div class="device-live-hint">${o(c.hint)}</div>
      <div class="device-live-meta">最近在线 ${o(s(i.updatedAt))}</div>
    </button>`}).join(""),n=(e||[]).reduce((i,c)=>{const l=et(c);return i[l.tone]=(i[l.tone]||0)+1,i},{idle:0,busy:0,offline:0});return`<div class="card device-live-panel" id="deviceLivePanel">
    <div class="pane-head">
      <div>
        <h3 class="pane-title" style="margin:0">柜机实时状态</h3>
        <p class="sub" style="margin:4px 0 0">空闲 ${n.idle||0} · 占用 ${n.busy||0} · 离线 ${n.offline||0}</p>
      </div>
      <div class="filters" style="margin:0">
        <button type="button" class="btn-ghost btn-sm" onclick="navigate('devices')">设备管理</button>
        ${ke(t.refreshFn||"refreshDashboardDevicePanel()","刷新")}
      </div>
    </div>
    ${a?`<div class="device-live-grid">${a}</div>`:`<div class="device-live-empty">${P("暂无设备","请先在设备页注册柜机","navigate('devices')")}</div>`}
  </div>`}const Ta={OPEN:"待审核",RESOLVED:"已结案",CLOSED:"已结案"};function La(e){const t=String(e||"").toUpperCase();return Ta[t]||"-"}function Ca(e){const t=String(e||"").toUpperCase(),s=La(t);return`<span class="badge ${t==="OPEN"?"badge-active":t==="RESOLVED"||t==="CLOSED"?"badge-done":"badge-offline"}">${o(s)}</span>`}const Ra={WECHAT:"微信",ALIPAY:"支付宝",MOCK:"其他"},Ba={PUBLISHED:"已发布",DRAFT:"草稿",REVOKED:"已撤回"},Pa={STABLE:"稳定版",BETA:"测试版",GRAY:"灰度"},Aa={PENDING:"待执行",RUNNING:"进行中",COMPLETED:"已完成",FAILED:"失败",MATCHED:"已对平",UNMATCHED:"有差异"},Da={PENDING:"待处理",IN_PROGRESS:"进行中",COMPLETED:"已完成",CANCELLED:"已取消",OPEN:"待处理"},Ma={ACTIVE:"正常",INACTIVE:"停用",PENDING:"待审核"},Na={LOW:"低",MEDIUM:"中",HIGH:"高",CRITICAL:"严重"},Oa={DOOR_OPEN_FAIL:"开门失败",DISPUTE_SPIKE:"争议异常",LOW_BALANCE:"余额不足",BLACKLIST_HIT:"黑名单命中"},xa={AI_CABINET_V1:"AI智能柜 V1"},Ua={SINGLE:"单摄",MULTI:"多摄融合"},Fa={DISPUTE_RESOLVE:"争议结案",USER_BALANCE:"调整余额",DEVICE_EDIT:"编辑设备",SKU_EDIT:"编辑商品",RBAC_ASSIGN:"分配角色",MERCHANT_EDIT:"编辑商户",REPLENISH_EDIT:"补货调整"},Ha={SESSION:"会话",ORDER:"订单",USER:"用户",DEVICE:"设备",SKU:"商品",DISPUTE:"争议",MERCHANT:"商户"};function ls(e){return Ra[String(e||"").toUpperCase()]||e||"-"}function _a(e){return Ba[String(e||"").toUpperCase()]||e||"-"}function qa(e){return Pa[String(e||"").toUpperCase()]||e||"-"}function rs(e){return Aa[String(e||"").toUpperCase()]||e||"-"}function Zt(e){return Da[String(e||"").toUpperCase()]||e||"-"}function ja(e){return Ma[String(e||"").toUpperCase()]||e||"-"}function Ga(e){return Na[String(e||"").toUpperCase()]||e||"-"}function Va(e){return Oa[String(e||"").toUpperCase()]||e||"-"}function Wa(e){return xa[String(e||"").toUpperCase()]||e||"-"}function za(e){return Ua[String(e||"").toUpperCase()]||e||"-"}function Ka(e){return Fa[String(e||"").toUpperCase()]||e||"-"}function Qa(e){return Ha[String(e||"").toUpperCase()]||e||"-"}function d(e,t){const s=document.getElementById("toastRoot");if(!s){alert(e);return}const a=document.createElement("div");a.className="toast toast-"+(t||"info"),a.textContent=e,s.appendChild(a),setTimeout(()=>a.classList.add("show"),10),setTimeout(()=>{a.classList.remove("show"),setTimeout(()=>a.remove(),300)},3200)}function J(e,t,s){if(b(t)||!e)return;const a=s!==!1?"card err":"err";e.innerHTML=`<div class="${a}">${o(t.message||"加载失败")}</div>`}const Ya={dashboard:"stats",devices:"table",sessions:"filters-table",orders:"filters-table",recharges:"filters-table",skus:"filters-table",users:"filters-table",reports:"table",audit:"filters-table",recent:"filters-table",disputes:"table",sla:"stats",ota:"filters-table",risk:"table",reconciliation:"filters-table",replenishment:"table",merchants:"filters-table",rbac:"table","vision-mappings":"filters-table","upload-queue":"filters-table"};function vt(e){return`<div class="skel-bar" style="width:${e}"></div>`}function Ja(e,t){let s="";for(let a=0;a<t;a++){s+='<tr class="skel-row">';for(let n=0;n<e;n++)s+='<td><div class="skel-bar skel-cell"></div></td>';s+="</tr>"}return s}function wt(e,t){e=e||6,t=t||5;let s="<tr>";for(let a=0;a<e;a++)s+='<th><div class="skel-bar skel-th"></div></th>';return s+="</tr>",`<div class="skeleton-table-wrap card" style="padding:0;overflow:hidden">
    <table class="skeleton-table"><thead>${s}</thead>
    <tbody>${Ja(e,t)}</tbody></table></div>`}function Za(){return`<div class="card skeleton-filters">
    <div class="skel-filter-row">
      ${vt("120px")}${vt("160px")}${vt("72px")}
    </div>
  </div>`}function Xa(){return`<div class="page-loading">
    <div class="stats">${Array.from({length:8},()=>'<div class="stat skel-stat"><div class="skel-bar skel-label"></div><div class="skel-bar skel-value"></div></div>').join("")}</div>
    <div class="card skel-chart">
      <div class="skel-bar skel-title"></div>
      <div class="skel-bars">${Array.from({length:7},()=>'<div class="skel-chart-bar"></div>').join("")}</div>
    </div>
  </div>`}function en(e){return e==="stats"?Xa():e==="filters-table"?Za()+wt(6,6):wt(6,8)}function ds(e){const t=document.getElementById("pageContent");t&&(t.innerHTML=en(Ya[e]||"table"))}function Q(e,t,s){e&&(e.innerHTML=wt(t,s))}function ke(e,t){const s=r(e),a=o(t||"刷新");return`<button type="button" class="btn-ghost btn-sm btn-refresh" data-refresh-action="${s}" onclick="handleRefreshClick(event)"><span class="btn-refresh-icon" aria-hidden="true">↻</span><span class="btn-refresh-label">${a}</span></button>`}async function tn(e){const t=e==null?void 0:e.currentTarget;if(!t||t.classList.contains("is-loading"))return;const a=(t.dataset.refreshAction||"").trim().match(/^([a-zA-Z0-9_$]+)\(\)$/);if(!a)return;const n=window[a[1]];if(typeof n!="function")return;t.classList.add("is-loading"),t.disabled=!0;const i=t.querySelector(".btn-refresh-label"),c=i?i.textContent:"";i&&(i.textContent="刷新中…");try{await n(),d("已刷新","ok")}catch(l){b(l)||d("刷新失败: "+he(l),"err")}finally{t.classList.remove("is-loading"),t.disabled=!1,i&&(i.textContent=c||"刷新")}}function P(e,t,s){const a=s?`<div class="empty-actions">${ke(s)}</div>`:"";return`<div class="empty-state">
    <div class="empty-icon" aria-hidden="true"></div>
    <div class="empty-title">${o(e)}</div>
    ${t?`<div class="empty-hint">${o(t)}</div>`:""}
    ${a}
  </div>`}let Qe=null;function sn(e){Bt(),Qe=e}function Bt(){Qe&&(URL.revokeObjectURL(Qe),Qe=null)}function Pt(e){if(!e)return"unknown";const t=String(e).toLowerCase();return/\.(jpe?g|png|gif|webp|bmp)(\?|$)/.test(t)?"image":/\.(mp4|webm|mov|m4v)(\?|$)/.test(t)?"video":"unknown"}function At(e){return Pt(e)==="image"?"查看截图":"播放视频"}const an={PENDING:"待处理",LEDGER_ONLY:"仅记账",ACCRUED:"待分账",WECHAT_SUBMITTED:"已提交",WECHAT_FAILED:"失败",SUBMITTED:"已提交",SUCCESS:"成功",FAILED:"失败"};function nn(e){const t=(e||"").toUpperCase(),s=an[t]||e||"-";return`<span class="badge ${t==="SUCCESS"||t==="SUBMITTED"||t==="WECHAT_SUBMITTED"?"badge-done":t==="FAILED"||t==="WECHAT_FAILED"?"badge-fail":t==="ACCRUED"?"badge-active":"badge-offline"}">${o(s)}</span>`}const bt=new Map;function we(e){return bt.has(e)||bt.set(e,new Set),bt.get(e)}function N(e){we(e).clear(),A(e)}function Dt(e){return[...we(e)]}function us(e,t,s){const a=String(t),n=we(e);s?n.add(a):n.delete(a),A(e)}function on(e,t){const s=we(e);s.clear(),t&&document.querySelectorAll(`[data-sel-scope="${e}"] .row-select-cb`).forEach(a=>s.add(a.value)),A(e)}function cn(e,t,s){if(s.target.closest("button, input, a, label, select"))return;const a=String(t),n=we(e);s.ctrlKey||s.metaKey?n.has(a)?n.delete(a):n.add(a):(n.clear(),n.add(a)),A(e)}function A(e){const t=we(e);document.querySelectorAll(`[data-sel-scope="${e}"] .selectable-row`).forEach(a=>{a.classList.toggle("selected",t.has(a.dataset.rowId))}),document.querySelectorAll(`[data-sel-scope="${e}"] .row-select-cb`).forEach(a=>{a.checked=t.has(a.value)});const s=document.querySelector(`[data-sel-scope="${e}"] thead .col-check input[type="checkbox"]`);if(s){const a=[...document.querySelectorAll(`[data-sel-scope="${e}"] .row-select-cb`)];s.checked=a.length>0&&a.every(n=>n.checked),s.indeterminate=!s.checked&&t.size>0}document.querySelectorAll(`[data-sel-bar="${e}"]`).forEach(a=>{a.textContent=t.size?`已选 ${t.size} 项（Ctrl+点击可多选）`:""}),document.querySelectorAll(`[data-sel-actions="${e}"]`).forEach(a=>{a.classList.toggle("hidden",t.size===0)}),typeof document<"u"&&document.dispatchEvent(new CustomEvent("selchange",{detail:{scope:e}}))}function H(e,t=""){const s=t?`<span class="selection-actions hidden" data-sel-actions="${r(e)}">${t}</span>`:"";return`<span class="selection-bar meta" data-sel-bar="${r(e)}"></span>${s}`}function V(e){return`<th class="col-check"><input type="checkbox" title="全选" onchange="selToggleAll('${r(e)}', this.checked)"></th>`}function ps(e,t){const s=we(e).has(String(t));return`<input type="checkbox" class="row-select-cb" value="${r(t)}" ${s?"checked":""}
    onclick="event.stopPropagation()" onchange="selToggle('${r(e)}', '${r(t)}', this.checked)">`}function W(e,t){return`<td class="col-check" onclick="event.stopPropagation()">${ps(e,t)}</td>`}function ms(e,t,s,a="",n=""){const c=["selectable-row",we(e).has(String(t))?"selected":"",a].filter(Boolean).join(" "),l=n?`;${n}`:"";return`<${s} class="${c}" data-row-id="${r(t)}"
    onclick="selRowClick('${r(e)}', '${r(t)}', event)${l}">`}function z(e,t,s="",a=""){return ms(e,t,"tr",s,a)}function ln(e,t,s="",a=""){return ms(e,t,"div",s,a)}function j(e,t){return`<div class="table-wrap" data-sel-scope="${r(e)}">${t}</div>`}function nt(e,t=300){let s=null;return(...a)=>{clearTimeout(s),s=setTimeout(()=>e(...a),t)}}let qe=null;function Mt(){qe&&(document.removeEventListener("keydown",qe),qe=null),document.body.classList.remove("modal-open")}function Nt(e){const t=document.getElementById("modalRoot");if(!t)return;const s=t.querySelector(".modal, .confirm-dialog");if(!s)return;s.setAttribute("role","dialog"),s.setAttribute("aria-modal","true");const a=s.querySelector("h3, .confirm-title");a&&!a.id&&(a.id="modalTitle_"+Date.now()),a&&s.setAttribute("aria-labelledby",a.id),Mt(),qe=i=>{if(i.key==="Escape")if(typeof e=="function")e();else{const c=t.querySelector("[data-modal-cancel]");c?c.click():typeof window.closeModal=="function"&&window.closeModal()}},document.addEventListener("keydown",qe),document.body.classList.add("modal-open");const n=s.querySelector("button, input, select, textarea");n==null||n.focus()}function ie(e,t={}){const s=t.title||"请确认",a=t.confirmText||"确定",n=t.cancelText||"取消",i=t.danger?" btn-danger":"";return new Promise(c=>{const l=document.getElementById("modalRoot");if(!l){c(window.confirm(e));return}const p=u=>{l.classList.add("hidden"),l.innerHTML="",Mt(),c(u)};l.innerHTML=`
      <div class="modal-backdrop" data-modal-backdrop>
        <div class="modal confirm-dialog" onclick="event.stopPropagation()">
          <h3 class="confirm-title">${o(s)}</h3>
          <p class="confirm-msg">${o(e)}</p>
          <div class="modal-actions">
            <button type="button" class="btn-ghost" data-modal-cancel>${o(n)}</button>
            <button type="button" class="btn-primary${i}" data-modal-ok>${o(a)}</button>
          </div>
        </div>
      </div>`,l.classList.remove("hidden"),Nt(()=>p(!1)),l.querySelector("[data-modal-cancel]").onclick=()=>p(!1),l.querySelector("[data-modal-ok]").onclick=()=>p(!0),l.querySelector("[data-modal-backdrop]").onclick=u=>{u.target===u.currentTarget&&p(!1)}})}async function _(e,t,s="保存中…"){const a=e&&e.target&&e.target.closest("button")||null;if(a!=null&&a.disabled)return;const n=a?a.textContent:"";a&&(a.disabled=!0,a.classList.add("btn-loading"),a.textContent=s);try{return await t()}finally{a&&(a.disabled=!1,a.classList.remove("btn-loading"),a.textContent=n)}}function ot(e,t){const s=Math.max(1,Math.ceil(e.total/e.size)),a=e.page+1,i=[10,20,50,100].map(c=>`<option value="${c}" ${e.size===c?"selected":""}>${c} 条/页</option>`).join("");return`<div class="pagination">
    <span class="pagination-meta">共 ${e.total} 条 · 第 ${a}/${s} 页</span>
    <div class="pagination-controls">
      <button type="button" class="btn-ghost btn-sm" ${e.page<=0?"disabled":""} onclick="changePage('${r(t)}', 0)">首页</button>
      <button type="button" class="btn-ghost btn-sm" ${e.page<=0?"disabled":""} onclick="changePage('${r(t)}', ${e.page-1})">上一页</button>
      <span class="pagination-jump">第 <input type="number" class="page-jump-input" min="1" max="${s}" value="${a}"
        onkeydown="if(event.key==='Enter')jumpToPage('${r(t)}', this.value)"> 页
        <button type="button" class="btn-ghost btn-sm" onclick="jumpToPage('${r(t)}', this.previousElementSibling.value)">跳转</button></span>
      <button type="button" class="btn-ghost btn-sm" ${a>=s?"disabled":""} onclick="changePage('${r(t)}', ${e.page+1})">下一页</button>
      <button type="button" class="btn-ghost btn-sm" ${a>=s?"disabled":""} onclick="changePage('${r(t)}', ${s-1})">末页</button>
      <label class="page-size-label"><span class="sr-only">每页条数</span>
        <select class="page-size-select" onchange="changePageSize('${r(t)}', this.value)">${i}</select>
      </label>
    </div>
  </div>`}function Ot(e,t,s){if(!e||!e.length||!t)return e||[];const a=s==="asc"?1:-1;return[...e].sort((n,i)=>{let c=n[t],l=i[t];return c==null&&(c=""),l==null&&(l=""),typeof c=="number"&&typeof l=="number"?(c-l)*a:String(c).localeCompare(String(l),"zh-CN")*a})}function Te(e,t,s,a){const n=(a==null?void 0:a.field)===t,i=n?a.dir:"";return`<th class="col-sortable">
    <div class="th-sort-wrap">
      <span class="th-sort-label">${o(s)}</span>
      <span class="sort-dir-btns" role="group" aria-label="${o(s)}排序">
        <button type="button" class="sort-dir-btn${n&&i==="asc"?" active":""}" onclick="event.stopPropagation();setTableSort('${r(e)}','${r(t)}','asc')" title="升序">↑</button>
        <button type="button" class="sort-dir-btn${n&&i==="desc"?" active":""}" onclick="event.stopPropagation();setTableSort('${r(e)}','${r(t)}','desc')" title="降序">↓</button>
      </span>
    </div>
  </th>`}function F(e={}){const t=e.fieldsHtml||"",s=e.onSearch||"",a=e.onReset||"",n=e.refreshFn||"",i=e.extraHtml||"",c=n?ke(n):"";return`<div class="list-filter-bar">
    <div class="list-filter-fields">${t}</div>
    <div class="list-filter-actions">
      ${s?`<button type="button" class="btn-primary btn-sm" onclick="${s}">查询</button>`:""}
      ${a?`<button type="button" class="btn-ghost btn-sm" onclick="${a}">重置</button>`:""}
      ${c}
      ${i}
    </div>
  </div>`}function R(e,t){return`<div class="list-filter-field"><label>${o(e)}</label>${t}</div>`}function rn(e){return`<div class="forbidden-page card">
    <div class="forbidden-icon" aria-hidden="true">403</div>
    <h3>无权访问</h3>
    <p class="sub">您没有「${o(e||"该页面")}」的访问权限，请联系管理员分配角色。</p>
    <button type="button" class="btn-primary" onclick="navigate('dashboard')">返回概览</button>
  </div>`}const dn={PAID:"已支付",PENDING:"待支付",REFUNDED:"已退款",CANCELLED:"已取消"};function un(e){return dn[e]||e||"-"}function ft(e,t,s,a){const n=t?`<div class="table-wrap">${t}</div>`:`<div class="table-empty">${s||P("暂无数据")}</div>`;return`<section class="table-block">
    <div class="table-block-head">
      <h3>${o(e)}</h3>
      ${a||""}
    </div>
    ${n}
  </section>`}function se(e){return String(e??"").replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/"/g,"&quot;")}function hs(e){if(e<=0)return 1;const t=Math.pow(10,Math.floor(Math.log10(e))),s=e/t;return(s<=1?1:s<=2?2:s<=5?5:10)*t}function Xt(e){return"¥"+(e/100).toFixed(e>=1e4?0:2)}function gt(e){return(Number(e)*100).toFixed(1)+"%"}function ze(e,t,s={}){if(t==null||e==null)return"";const a=t===0?e>0?100:0:(e-t)/Math.abs(t)*100,n=a>=0,c=s.invert?!n:n,l=Math.abs(a)<.05?"flat":c?"up":"down",p=a>=0?"+":"",u=s.suffix==="%"?`${p}${a.toFixed(1)}%`:`${p}${a.toFixed(1)}%`;return`<span class="delta-badge ${l}" title="较前一周期">${u}</span>`}function yt({labels:e,series:t,height:s=220,formatY:a=n=>String(n)}){if(!(e!=null&&e.length)||!(t!=null&&t.length))return'<p class="meta chart-empty">暂无趋势数据</p>';const n=640,i=s,c=52,l=16,p=20,u=36,m=n-c-l,k=i-p-u;let w=0;t.forEach(f=>{(f.values||[]).forEach(L=>{L>w&&(w=L)})}),w=hs(w);const T=f=>c+(e.length<=1?m/2:f/(e.length-1)*m),O=f=>p+k-(w>0?f/w*k:0),h=[0,.25,.5,.75,1].map(f=>{const L=p+k*(1-f),I=w*f;return`<line x1="${c}" y1="${L}" x2="${n-l}" y2="${L}" class="chart-grid"/>
      <text x="${c-8}" y="${L+4}" class="chart-axis-y" text-anchor="end">${se(a(I))}</text>`}).join(""),y=e.map((f,L)=>`<text x="${T(L)}" y="${i-8}" class="chart-axis-x" text-anchor="middle">${se(f)}</text>`).join(""),g=t.map(f=>{const L=(f.values||[]).map((M,Ce)=>`${T(Ce)},${O(M)}`).join(" "),I=(f.values||[]).map((M,Ce)=>`<circle cx="${T(Ce)}" cy="${O(M)}" r="4" class="chart-dot" fill="${f.color||"var(--chart-1)"}">
        <title>${se(f.name)} ${se(e[Ce])}: ${se(a(M))}</title>
      </circle>`).join("");return`<polyline points="${L}" fill="none" stroke="${f.color||"var(--chart-1)"}" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>
      ${I}`}).join(""),E=t.map(f=>`<span class="chart-legend-item"><i style="background:${f.color||"var(--chart-1)"}"></i>${se(f.name)}</span>`).join("");return`<div class="svg-chart-wrap">
    <svg class="svg-chart" viewBox="0 0 ${n} ${i}" preserveAspectRatio="xMidYMid meet" role="img" aria-label="折线图">
      ${h}
      ${g}
      ${y}
    </svg>
    <div class="chart-legend">${E}</div>
  </div>`}function pn({labels:e,values:t,height:s=200,formatY:a=i=>String(i),color:n="var(--chart-1)"}){if(!(e!=null&&e.length))return'<p class="meta chart-empty">暂无数据</p>';const i=640,c=s,l=48,p=12,u=16,m=32,k=i-l-p,w=c-u-m,T=hs(Math.max(...t,0)),O=Math.min(48,k/e.length*.55),h=k/e.length,y=t.map((f,L)=>{const I=T>0?f/T*w:0,M=l+L*h+(h-O)/2,Ce=u+w-I;return`<rect x="${M}" y="${Ce}" width="${O}" height="${Math.max(I,2)}" rx="4" fill="${n}" opacity="0.9">
      <title>${se(e[L])}: ${se(a(f))}</title>
    </rect>`}).join(""),g=e.map((f,L)=>`<text x="${l+L*h+h/2}" y="${c-8}" class="chart-axis-x" text-anchor="middle">${se(f)}</text>`).join(""),E=[0,.5,1].map(f=>{const L=u+w*(1-f);return`<line x1="${l}" y1="${L}" x2="${i-p}" y2="${L}" class="chart-grid"/>
      <text x="${l-6}" y="${L+4}" class="chart-axis-y" text-anchor="end">${se(a(T*f))}</text>`}).join("");return`<div class="svg-chart-wrap">
    <svg class="svg-chart" viewBox="0 0 ${i} ${c}" preserveAspectRatio="xMidYMid meet" role="img" aria-label="柱状图">
      ${E}${y}${g}
    </svg>
  </div>`}function mn({segments:e,size:t=160}){const s=e.reduce((m,k)=>m+k.value,0)||1,a=t/2,n=t/2,i=t*.38,c=i*.58;let l=-Math.PI/2;const p=e.map(m=>{const k=m.value/s*Math.PI*2,w=a+i*Math.cos(l),T=n+i*Math.sin(l);l+=k;const O=a+i*Math.cos(l),h=n+i*Math.sin(l),y=a+c*Math.cos(l-k),g=n+c*Math.sin(l-k),E=a+c*Math.cos(l),f=n+c*Math.sin(l),L=k>Math.PI?1:0;return`<path d="${`M ${w} ${T} A ${i} ${i} 0 ${L} 1 ${O} ${h} L ${E} ${f} A ${c} ${c} 0 ${L} 0 ${y} ${g} Z`}" fill="${m.color}"><title>${se(m.label)}: ${m.value}</title></path>`}).join(""),u=e.map(m=>`<span class="chart-legend-item"><i style="background:${m.color}"></i>${se(m.label)} ${Math.round(m.value/s*100)}%</span>`).join("");return`<div class="donut-chart-wrap">
    <svg width="${t}" height="${t}" viewBox="0 0 ${t} ${t}" role="img" aria-label="环形图">${p}</svg>
    <div class="chart-legend donut-legend">${u}</div>
  </div>`}function hn(e){if(!(e!=null&&e.length))return"";const t=Math.max(...e.map(s=>s.value),1);return`<div class="h-bar-list">${e.map(s=>{const a=Math.round(s.value/t*100);return`<div class="h-bar-row">
      <span class="h-bar-label">${se(s.label)}</span>
      <div class="h-bar-track"><div class="h-bar-fill" style="width:${a}%;background:${s.color||"var(--chart-1)"}"></div></div>
      <span class="h-bar-val">${se(s.display??s.value)}</span>
    </div>`}).join("")}</div>`}function vn(e,t,s){const a=(t==null?void 0:t.last7Days)||[],n=(s==null?void 0:s.last7Days)||[],i=a.map(I=>I.date.slice(5)),c=a.map(I=>I.revenueCents),l=a.map(I=>I.orderCount),p=Object.fromEntries(n.map(I=>[I.date,I])),u=a.map(I=>{const M=p[I.date];return M?Math.round((M.recognitionRate||0)*1e3)/10:0}),m=a.map(I=>{const M=p[I.date];return M?Math.round((M.disputeRate||0)*1e3)/10:0}),k=a.map(I=>{const M=p[I.date];return M?(M.completedSessions||0)+(M.disputedSessions||0):0}),w=c.reduce((I,M)=>I+M,0),T=l.reduce((I,M)=>I+M,0),O=u.length?u.reduce((I,M)=>I+M,0)/u.length:0,h=c[c.length-1],y=c[c.length-2],g=l[l.length-1],E=l[l.length-2],f=e.deviceOnline||0,L=Math.max(0,(e.deviceTotal||0)-f);return`
    <div class="analytics-section">
      <div class="analytics-head">
        <h3 class="section-title">数据分析</h3>
        <span class="meta">近 7 日趋势 · 点击指标卡片可跳转详情</span>
      </div>
      <div class="analytics-kpi">
        <div class="kpi-card">
          <div class="kpi-label">7日总营收</div>
          <div class="kpi-value">${Xt(w)}</div>
          ${ze(h,y)}
        </div>
        <div class="kpi-card">
          <div class="kpi-label">7日订单量</div>
          <div class="kpi-value">${T}</div>
          ${ze(g,E)}
        </div>
        <div class="kpi-card">
          <div class="kpi-label">平均识别率</div>
          <div class="kpi-value ok">${O.toFixed(1)}%</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">设备在线率</div>
          <div class="kpi-value">${e.deviceTotal?Math.round(f/e.deviceTotal*100):0}%</div>
        </div>
      </div>
      <div class="analytics-grid">
        <div class="card chart-card">
          <div class="chart-card-head">
            <h4>营收趋势</h4>
            ${ze(h,y)}
          </div>
          ${yt({labels:i,series:[{name:"营收",values:c,color:"var(--chart-1)"}],formatY:I=>Xt(I)})}
        </div>
        <div class="card chart-card">
          <div class="chart-card-head">
            <h4>订单量趋势</h4>
            ${ze(g,E)}
          </div>
          ${yt({labels:i,series:[{name:"订单",values:l,color:"var(--chart-2)"}],formatY:I=>String(Math.round(I))})}
        </div>
        <div class="card chart-card">
          <div class="chart-card-head">
            <h4>识别质量</h4>
            <span class="meta">识别率 vs 争议率</span>
          </div>
          ${yt({labels:i,series:[{name:"识别率",values:u,color:"var(--chart-3)"},{name:"争议率",values:m,color:"var(--chart-4)"}],formatY:I=>I+"%"})}
        </div>
        <div class="card chart-card">
          <div class="chart-card-head">
            <h4>关门会话量</h4>
          </div>
          ${pn({labels:i,values:k,color:"var(--chart-2)",formatY:I=>String(Math.round(I))})}
        </div>
        <div class="card chart-card chart-card-sm">
          <div class="chart-card-head"><h4>设备状态</h4></div>
          ${mn({segments:[{label:"在线",value:f,color:"var(--chart-3)"},{label:"离线",value:L||(f?0:1),color:"var(--chart-muted)"}]})}
        </div>
        <div class="card chart-card chart-card-sm">
          <div class="chart-card-head"><h4>运营健康度</h4></div>
          ${hn([{label:"24h 开门成功率",value:e.doorSuccessRate24h||0,display:gt(e.doorSuccessRate24h),color:"var(--chart-3)"},{label:"24h 自动识别率",value:e.recognitionAutoRate24h||0,display:gt(e.recognitionAutoRate24h),color:"var(--chart-1)"},{label:"24h 争议率",value:e.disputeRate24h||0,display:gt(e.disputeRate24h),color:"var(--chart-4)"}])}
        </div>
      </div>
    </div>`}let Re=new Set,$t=!1;const bn={dashboard:"ops:dashboard:view",devices:"ops:device:list",sessions:"ops:session:list",orders:"ops:order:list",recharges:"ops:order:list",skus:"ops:sku:list",users:"ops:user:list",reports:"ops:device:list",audit:"ops:audit:list",recent:"ops:audit:recent",disputes:"ops:dispute","vision-mappings":"ops:vision:list","upload-queue":"ops:session:upload",sla:"ops:sla",ota:"ops:ota:list",risk:"ops:risk:list",reconciliation:"ops:reconciliation:list",replenishment:"ops:replenishment:list",warehouse:"ops:replenishment:list",finance:"ops:replenishment:list",merchants:"ops:merchant:list",rbac:"ops:rbac:role"},fn={"device.create":"ops:device:edit","device.edit":"ops:device:edit","session.cancel":"ops:session:cancel","sku.edit":"ops:sku:edit","user.balance":"ops:user:balance","recharge.refund":"ops:user:balance","ota.publish":"ops:ota:publish","risk.blacklist":"ops:risk:blacklist","recon.run":"ops:reconciliation:run","replenish.edit":"ops:replenishment:edit","replenish.plan":"ops:replenishment:edit","rbac.assign":"ops:rbac:assign","rbac.role.save":"ops:rbac:role","vision.edit":"ops:vision:edit","merchant.edit":"ops:merchant:edit","merchant.split":"ops:merchant:split","user.verify":"ops:user:list"};async function gn(e){$t=!1;try{const t=await e("/api/v2/ops/admin/rbac/me/permissions","GET");Re=new Set(t||[]),Re.has("*")&&(Re=new Set(["*"]))}catch(t){console.warn("load permissions failed, no permissions granted",t),Re=new Set,$t=!0}return ce(),!$t}function S(e){return!e||Re.has("*")?!0:Re.has(e)}function vs(e){const t=bn[e];return!!(!t||S(t)||e==="audit"&&S("ops:dashboard:view"))}function ce(){document.querySelectorAll(".nav-item[data-page]").forEach(e=>{const t=e.dataset.page;vs(t)?e.classList.remove("hidden"):e.classList.add("hidden")}),document.querySelectorAll("[data-perm]").forEach(e=>{const t=e.getAttribute("data-perm");S(t)?e.style.display="":e.style.display="none"}),document.querySelectorAll(".nav-section").forEach(e=>{const t=e.querySelectorAll(".nav-item[data-page]");if(!t.length)return;const s=[...t].some(a=>!a.classList.contains("hidden"));e.classList.toggle("hidden",!s)})}function ae(e,t,s,a){const n=fn[e];return n&&!S(n)?"":`<button class="${a||"btn-primary btn-sm"}" data-perm="${n||""}" onclick="${s}">${t}</button>`}const Ee={api:null,getCurrentPage:()=>"dashboard",fmtTime:e=>e||"-",fmtMoney:e=>String(e),closeModal:()=>{},opsLoaders:{}},Ge=({}.VITE_API_BASE||"").replace(/\/$/,"")||window.location.origin;let ee=localStorage.getItem("admin_token")||"",ve=[],q="";const G={page:0,size:20,deviceId:"",state:""},oe={page:0,size:20,deviceId:""},Z={page:0,size:20,status:"",userId:""},re={name:"",status:""},it={keyword:""},$e={dashboard:"数据概览",devices:"设备管理",sessions:"开门记录",orders:"订单管理",recharges:"充值管理",skus:"商品管理",users:"用户管理",reports:"设备报表",audit:"操作日志",recent:"最近操作",disputes:"争议审核","vision-mappings":"识别配置","upload-queue":"录像上传",sla:"服务时效",ota:"固件升级",risk:"风控",reconciliation:"对账",replenishment:"补货",warehouse:"仓库",finance:"财务毛利",merchants:"商户分账",rbac:"权限管理"},B={page:0,size:20,phone:"",name:"",role:"",verified:""},tt={page:0,size:20},je={size:20,mine:!1},x={page:0,size:20,status:"OPEN",sessionId:"",deviceId:""},X={sessions:{field:"createdAt",dir:"desc"},orders:{field:"createdAt",dir:"desc"},users:{field:"userId",dir:"desc"}},yn=nt(()=>qs(),350),$n=nt(()=>Gs(),350),In=nt(()=>Js(),350);function It(e){return e==null||Number.isNaN(e)?"-":(Number(e)*100).toFixed(1)+"%"}function bs(e){const t=Ct(e);return t?fa(t)?{ok:!0,phone:t}:{ok:!1,message:ga()}:{ok:!1,message:"请输入手机号"}}function kn(e){return(e||"").trim()}function C(e){return"¥"+(e/100).toFixed(2)}function Y(e){return e?new Date(e).toLocaleString("zh-CN"):"-"}const wn=30*60*1e3,En=8*60*1e3,Sn=5*60*1e3;let st=parseInt(localStorage.getItem("admin_token_expires")||"0",10)||0,Et=wn,fs=Date.now(),Ye=null,_e=null;function ct(){fs=Date.now()}function gs(e,t){ee=e.token,localStorage.setItem("admin_token",ee),localStorage.setItem("admin_userId",e.userId),t&&localStorage.setItem("admin_phone",t),Et=(e.expiresInSeconds||1800)*1e3,st=Date.now()+Et,localStorage.setItem("admin_token_expires",String(st)),e.serverBootEpoch!=null&&ks(e.serverBootEpoch),ct()}async function ys(){if(!ee)return!1;if(_e)return _e;_e=(async()=>{const e=await fetch(Ge+"/api/v2/auth/refresh",{method:"POST",headers:{"Content-Type":"application/json",Authorization:"Bearer "+ee}}),t=await e.json().catch(()=>({}));if(!e.ok||t.code!==0){const s=new Error(he({message:t.message,status:e.status})||"登录已失效");throw s.status=e.status,s}return gs(t.data),!0})();try{return await _e}finally{_e=null}}async function $s(){ee&&(Date.now()-fs>Et||st-Date.now()>En||await ys())}function at(){ct()}function Tn(){Is(),ct(),document.addEventListener("click",at),document.addEventListener("keydown",at),Ye=setInterval(()=>{$s().catch(()=>{})},Sn)}function Is(){Ye&&(clearInterval(Ye),Ye=null),document.removeEventListener("click",at),document.removeEventListener("keydown",at)}async function $(e,t,s,a=!0,n=!1){if(a&&ee){ct();try{await $s()}catch{}}const i={"Content-Type":"application/json"};a&&ee&&(i.Authorization="Bearer "+ee);const c=await fetch(Ge+e,{method:t,headers:i,body:s?JSON.stringify(s):void 0}),l=await c.json().catch(()=>({}));if(c.status===401&&a&&!n)try{return await ys(),$(e,t,s,a,!0)}catch{}if(c.status===401||c.status===403){const p=new Error(he({message:l.message,status:c.status})||(c.status===403?"权限不足":"登录已失效，请重新登录"));throw p.status=c.status,b(p),p}if(!c.ok||l.code!==0){const p=new Error(he({message:l.message||l.error})||JSON.stringify(l));throw p.status=c.status,p}return l.data}function Ln(e,t,s){const a=(e||"").split(";")[0].trim().toLowerCase();if(a.startsWith("image/")||a.startsWith("video/"))return a;const n=String(t||"").toLowerCase();return n.endsWith(".png")?"image/png":/\.(jpe?g)$/.test(n)?"image/jpeg":n.endsWith(".webp")?"image/webp":n.endsWith(".webm")?"video/webm":n.endsWith(".mov")?"video/quicktime":s==="image"?"image/jpeg":"video/mp4"}async function Cn(e,t){const s=localStorage.getItem("admin_token")||ee;if(!s)throw new Error("请先登录");const a=await fetch(`${Ge}/api/v2/ops/admin/sessions/${encodeURIComponent(e)}/video`,{headers:{Authorization:"Bearer "+s}});if(a.status===401||a.status===403){const u=await a.json().catch(()=>({})),m=new Error(he({message:u.message,status:a.status})||(a.status===403?"权限不足":"登录已失效，请重新登录"));throw m.status=a.status,b(m),m}const n=a.headers.get("content-type")||"";if(!a.ok){if(n.includes("application/json")){const u=await a.json().catch(()=>({}));throw new Error(he({message:u.message,status:a.status})||"视频加载失败")}throw new Error(`视频加载失败 (${a.status})`)}if(n.includes("application/json")){const u=await a.json().catch(()=>({}));throw new Error(he({message:u.message,status:a.status})||"视频不存在")}const i=await a.blob();let c="video";if(n.startsWith("image/"))c="image";else if(n.startsWith("video/"))c="video";else{const u=Pt(t);u!=="unknown"&&(c=u)}const l=Ln(n,t,c);return l.startsWith("image/")&&(c="image"),l.startsWith("video/")&&(c="video"),{blob:!i.type||i.type==="application/octet-stream"?new Blob([i],{type:l}):i,kind:c,contentType:l}}function Be(e,t){const s=document.getElementById(e);s.textContent=t,s.classList.remove("hidden")}function ks(e){e!=null&&localStorage.setItem("admin_server_boot",String(e))}function Rn(e){const t=localStorage.getItem("admin_server_boot");return t?String(e)!==t:!0}async function Bn(){return $("/api/v2/auth/server-boot","GET",null,!1)}async function Pn(){var e,t;if(!ee){(e=document.getElementById("loginView"))==null||e.classList.remove("hidden"),(t=document.getElementById("appView"))==null||t.classList.add("hidden");return}try{const s=await Bn();if(Rn(s)){St(),d("服务已重启，请重新登录","warn");return}await fetch(Ge+"/api/v2/ops/admin/rbac/me",{headers:{Authorization:"Bearer "+ee}}).then(async a=>{if(a.status===401||a.status===403)throw Object.assign(new Error("登录已失效"),{status:a.status});const n=await a.json().catch(()=>({}));if(!a.ok||n.code!==0)throw Object.assign(new Error(n.message||"登录已失效"),{status:a.status})}),ks(s),Cs()}catch(s){b(s)||St()}}function ws(){const e=document.getElementById("phone");e&&!e.value&&localStorage.getItem("admin_phone")&&(e.value=localStorage.getItem("admin_phone"))}let ye=localStorage.getItem("admin_login_mode")||"password";function Es(e){var a,n,i;ye=e==="sms"?"sms":"password",localStorage.setItem("admin_login_mode",ye),document.querySelectorAll(".login-tab").forEach(c=>{c.classList.toggle("active",c.dataset.mode===ye)});const t=document.getElementById("loginPasswordBlock"),s=document.getElementById("loginSmsBlock");t&&t.classList.toggle("hidden",ye!=="password"),s&&s.classList.toggle("hidden",ye!=="sms"),(a=document.getElementById("loginErr"))==null||a.classList.add("hidden"),ye==="password"?(n=document.getElementById("password"))==null||n.focus():(i=document.getElementById("code"))==null||i.focus()}let Pe=null;function An(e=60){const t=document.getElementById("sendCodeBtn");if(!t)return;let s=e;t.disabled=!0;const a=()=>{if(s<=0){clearInterval(Pe),Pe=null,t.disabled=!1,t.textContent="获取验证码";return}t.textContent=`${s}s 后重发`,s-=1};a(),Pe=setInterval(a,1e3)}function Ie(e,t){const s=document.getElementById("loginBtn"),a=document.getElementById("sendCodeBtn"),n=document.getElementById("phone"),i=document.getElementById("code"),c=document.getElementById("password"),l=!!e;t==="login"?(s&&(s.disabled=l,s.classList.toggle("btn-loading",l),s.textContent=l?"登录中…":"登录"),a&&(a.disabled=l),n&&(n.readOnly=l),i&&(i.readOnly=l),c&&(c.readOnly=l)):t==="code"&&(a&&(a.disabled=l,a.classList.toggle("btn-loading",l),a.textContent=l?"发送中…":"获取验证码"),s&&(s.disabled=l),n&&(n.readOnly=l))}function Dn(){const e=document.getElementById("loginForm");if(!e)return;e.addEventListener("submit",s=>{s.preventDefault(),Ss()});const t=document.getElementById("phone");t&&!t.dataset.bound&&(t.dataset.bound="1",t.maxLength=11,t.addEventListener("input",()=>{t.value=Ct(t.value)}))}async function Mn(){var a,n;const e=document.getElementById("sendCodeBtn");if(e!=null&&e.disabled)return;const t=bs(document.getElementById("phone").value);if(!t.ok){Be("loginErr",t.message),(a=document.getElementById("phone"))==null||a.focus();return}const s=t.phone;document.getElementById("loginErr").classList.add("hidden"),Ie(!0,"code");try{await $(`/api/v2/auth/sms-code?phoneNumber=${encodeURIComponent(s)}`,"POST",null,!1),An(60),d("验证码已发送","ok"),(n=document.getElementById("code"))==null||n.focus()}catch(i){Be("loginErr",i.message)}finally{Ie(!1,"code")}}async function Ss(){var a,n,i,c;const e=document.getElementById("loginBtn");if(e!=null&&e.disabled)return;const t=bs(document.getElementById("phone").value);if(!t.ok){Be("loginErr",t.message),(a=document.getElementById("phone"))==null||a.focus();return}const s=t.phone;document.getElementById("loginErr").classList.add("hidden"),Ie(!0,"login");try{let l;if(ye==="password"){const p=((n=document.getElementById("password"))==null?void 0:n.value)||"";if(!p){Ie(!1,"login"),Be("loginErr","请输入密码"),(i=document.getElementById("password"))==null||i.focus();return}l=await $("/api/v2/auth/admin-password-login","POST",{phoneNumber:s,password:p},!1)}else{const p=kn(document.getElementById("code").value);if(!p){Ie(!1,"login"),Be("loginErr","请输入验证码"),(c=document.getElementById("code"))==null||c.focus();return}l=await $("/api/v2/auth/admin-login","POST",{phoneNumber:s,code:p},!1)}gs(l,s),Cs()}catch(l){Be("loginErr",l.message)}finally{Ie(!1,"login")}}function St(){var a;jt(),Is(),Pe&&(clearInterval(Pe),Pe=null),ee="",st=0,localStorage.removeItem("admin_token"),localStorage.removeItem("admin_userId"),localStorage.removeItem("admin_server_boot"),localStorage.removeItem("admin_token_expires"),sessionStorage.removeItem("admin_visited_tabs"),U=["dashboard"],Ae=0,Ve=!1,q="";try{history.replaceState(null,"",location.pathname+location.search)}catch{}const e=document.getElementById("tagsView");e&&(e.classList.add("hidden"),e.innerHTML=""),document.getElementById("appView").classList.add("hidden"),document.getElementById("loginView").classList.remove("hidden");const t=document.getElementById("pageContent");t&&(t.innerHTML=""),Ie(!1,"login"),Ie(!1,"code");const s=document.getElementById("sendCodeBtn");s&&(s.disabled=!1,s.textContent="获取验证码"),ws(),(a=document.getElementById("phone"))==null||a.focus()}function Ts(e,t,s){const a=(t||"运").trim().charAt(0).toUpperCase();e.innerHTML=`<div class="user-pill" title="${r(t)}">
    <span class="user-avatar" aria-hidden="true">${o(a)}</span>
    <span class="user-text">
      <span class="user-name">${o(t)}</span>
      <span class="user-detail">${s}</span>
    </span>
  </div>`}function Ls(){const e=document.getElementById("userInfo");if(!e)return;const t=localStorage.getItem("admin_phone")||"";Ts(e,"运营账号",t?`${o(t)} · 加载角色…`:"加载中…")}async function Nn(){const e=document.getElementById("userInfo");if(e)try{const t=await $("/api/v2/ops/admin/rbac/me","GET");localStorage.setItem("admin_userId",t.userId),t.phoneNumber&&localStorage.setItem("admin_phone",t.phoneNumber);const s=t.name||"运营账号",a=t.roleNames&&t.roleNames.length?t.roleNames.join("、"):"未分配角色",n=t.permissionCount>0?` · ${t.permissionCount} 项权限`:"";Ts(e,s,`${o(t.phoneNumber||"-")} · ${o(a)}${o(n)}`)}catch(t){b(t)||Ls()}}function Cs(){document.getElementById("loginView").classList.add("hidden"),document.getElementById("appView").classList.remove("hidden"),Tn(),Ls(),xn();const e=document.getElementById("pageContent");e&&ds("dashboard"),Promise.all([Nn(),gn($)]).then(([,t])=>{t||d("权限加载失败，部分功能不可用，请刷新页面重试","warn");const s=Bs();_n(s),Me(s,{replaceHash:!0,init:!0})}).catch(t=>{b(t)||J(e,t)})}const On=12;let U=[],Ae=0,Ve=!1;function xn(){try{const e=sessionStorage.getItem("admin_visited_tabs");U=e?JSON.parse(e):["dashboard"],(!Array.isArray(U)||!U.length)&&(U=["dashboard"]),U=U.filter(t=>$e[t]),U.includes("dashboard")||U.unshift("dashboard")}catch{U=["dashboard"]}}function Rs(){sessionStorage.setItem("admin_visited_tabs",JSON.stringify(U))}function kt(e){if($e[e]){for(U=U.filter(t=>t!==e),U.push(e);U.length>On;){const t=U.findIndex(s=>s!=="dashboard");if(t>=0)U.splice(t,1);else break}Rs()}}function Je(){const e=document.getElementById("tagsView");if(e){if(U.length<=1){e.classList.add("hidden"),e.innerHTML="";return}e.classList.remove("hidden"),e.innerHTML=U.map(t=>{const s=t===q,a=t!=="dashboard";return`<button type="button" class="tag-item ${s?"active":""}" onclick="navigate('${r(t)}')">
      <span>${o($e[t]||t)}</span>
      ${a?`<span class="tag-close" onclick="event.stopPropagation();closeVisitedTab('${r(t)}')" title="关闭">×</span>`:""}
    </button>`}).join("")}}function Un(e){e!=="dashboard"&&(U=U.filter(t=>t!==e),Rs(),q===e?Me(U[U.length-1]||"dashboard"):Je())}function Tt(){const e=document.getElementById("navBackBtn");e&&(e.disabled=!Ve)}function Fn(){Ve&&history.back()}function Bs(){const e=location.hash.match(/^#\/([a-z]+)$/),t=e?e[1]:"dashboard";return $e[t]?t:"dashboard"}function Ps(e){const t=document.querySelector(".sidebar"),s=document.getElementById("sidebarBackdrop");if(!t)return;const a=e===void 0?!t.classList.contains("open"):!!e;t.classList.toggle("open",a),s==null||s.classList.toggle("hidden",!a)}const As="admin_nav_sections";function Ds(){try{return JSON.parse(localStorage.getItem(As)||"{}")}catch{return{}}}function Ms(e,t){const s=Ds();s[e]=t,localStorage.setItem(As,JSON.stringify(s))}function xt(e,t){const s=document.querySelector(`.nav-section[data-nav-group="${e}"]`);if(!s)return;s.classList.toggle("collapsed",!t);const a=s.querySelector(".nav-section-toggle");a&&a.setAttribute("aria-expanded",t?"true":"false")}function Hn(e){const t=document.querySelector(`.nav-section[data-nav-group="${e}"]`);if(!t||t.classList.contains("hidden"))return;const s=t.classList.contains("collapsed");xt(e,s),Ms(e,s)}function es(e){document.querySelectorAll(".nav-section[data-nav-group]").forEach(t=>{if(t.classList.contains("hidden"))return;const s=t.dataset.navGroup;!!t.querySelector(`.nav-item[data-page="${e}"]:not(.hidden)`)&&(xt(s,!0),Ms(s,!0))})}function _n(e){const t=Ds();document.querySelectorAll(".nav-section[data-nav-group]").forEach(s=>{if(s.classList.contains("hidden"))return;const a=s.dataset.navGroup,n=!!s.querySelector(`.nav-item[data-page="${e}"]:not(.hidden)`);let i;n?i=!0:t[a]!=null?i=!!t[a]:i=a==="overview",xt(a,i)})}function Ns(e,t,s){X[e]={field:t,dir:s},e==="sessions"?We():e==="orders"?lt():e==="users"&&Ue()}function qn(e,t){const s=X[e]||{field:t,dir:"desc"};Ns(e,t,s.field===t&&s.dir==="desc"?"asc":"desc")}function Me(e,t={}){$e[e]||(e="dashboard");const s=!!t.fromPopstate,a="#/"+e;if(!vs(e)){q=e,document.getElementById("pageTitle").textContent=$e[e]||e,document.querySelectorAll(".nav-item").forEach(c=>{c.classList.toggle("active",c.dataset.page===e)}),es(e),Je(),document.getElementById("pageContent").innerHTML=rn($e[e]),!s&&location.hash!==a&&history.pushState({page:e,forbidden:!0},"",a);return}if(e===q&&!s&&!t.force&&!t.init){kt(e),Je();return}!s&&!t.replaceHash?(location.hash!==a&&(history.pushState({page:e},"",a),Ae+=1,Ve=Ae>0,Tt()),kt(e)):t.replaceHash&&location.hash!==a&&(history.replaceState({page:e},"",a),t.init&&kt(e)),q=e,Ps(!1),e!=="devices"&&e!=="dashboard"&&jt(),document.getElementById("pageTitle").textContent=$e[e]||e,document.querySelectorAll(".nav-item").forEach(c=>{c.classList.toggle("active",c.dataset.page===e)}),es(e),Je(),Tt(),ds(e);const n=Ee.opsLoaders||{};({dashboard:te,devices:Ne,sessions:_s,orders:js,recharges:sa,skus:rt,users:Ys,reports:Ao,audit:Do,recent:_t,disputes:Xs,"vision-mappings":n.visionMappings||te,"upload-queue":n.uploadQueue||te,sla:n.sla||te,ota:n.ota||te,risk:n.risk||te,reconciliation:n.reconciliation||te,replenishment:n.replenishment||te,warehouse:n.warehouse||te,finance:Fs,merchants:n.merchants||te,rbac:n.rbac||te}[e]||te)()}function Os(e){return ka(e)}function xs(e){return Ea(e)}function jn(e){return e?`
    <div class="dash-alert">
      <div class="dash-alert-main">
        <span class="dash-alert-dot" aria-hidden="true"></span>
        <span>当前没有在线设备，顾客可能无法正常开门购物</span>
        <button type="button" class="dash-alert-toggle" onclick="this.closest('.dash-alert').classList.toggle('expanded')">查看常见原因</button>
      </div>
      <div class="dash-alert-detail">
        <ul style="margin:0;padding-left:1.2em">
          <li>柜机断电或网络断开</li>
          <li>设备长时间未联网（超过 2 分钟会显示离线）</li>
          <li>现场设备故障，需运维人员检修</li>
        </ul>
        <p class="meta" style="margin:8px 0 0">建议前往「设备管理」查看各柜机最近在线时间，并联系现场人员排查。</p>
      </div>
    </div>`:""}function Gn(e){const t=String((e==null?void 0:e.type)||"").toUpperCase();return t==="DISPUTE"?"navigate('disputes')":t==="UPLOAD_STUCK"?"navigate('upload-queue')":t==="DEVICE_OFFLINE"?"navigate('devices')":t==="LOW_STOCK"||t==="REPLENISHMENT"?"navigate('replenishment')":"navigate('dashboard')"}function Vn(e){if(!e)return"";const t=e.actionItems||[],s=[["Open disputes",e.openDisputes],["Overdue",e.overdueDisputes],["Offline devices",e.offlineDevices],["Waiting upload",e.waitingUploads],["Low stock",e.lowStockItems],["Replenishment",e.pendingReplenishments]].map(([n,i])=>`<div class="workbench-pill"><span>${o(n)}</span><strong>${o(i??0)}</strong></div>`).join(""),a=t.slice(0,8).map(n=>{const i=String(n.severity||"LOW").toLowerCase(),c=[n.deviceId,n.sessionId,n.skuId].filter(Boolean).join(" · ");return`<button type="button" class="workbench-item sev-${r(i)}" onclick="${Gn(n)}">
      <span class="workbench-sev">${o(n.severity||"LOW")}</span>
      <span class="workbench-main">
        <strong>${o(n.title||n.type||"-")}</strong>
        <small>${o(n.detail||c||"-")}</small>
      </span>
      <span class="workbench-meta">${o(c)}</span>
    </button>`}).join("");return`<section class="card workbench-card">
    <div class="pane-head">
      <div>
        <h3 style="margin:0;font-size:1rem;color:var(--text)">Operations workbench</h3>
        <p class="sub" style="margin:4px 0 0">Prioritized issues that affect checkout, replenishment, and SLA.</p>
      </div>
      <button type="button" class="btn-ghost btn-sm" onclick="navigate('disputes')">Review</button>
    </div>
    <div class="workbench-summary">${s}</div>
    ${a?`<div class="workbench-list">${a}</div>`:'<div class="empty-state"><div class="empty-title">No urgent action</div></div>'}
  </section>`}async function te(){const e=document.getElementById("pageContent"),t="dashboard";try{const[s,a,n,i,c,l,p]=await Promise.all([$("/api/v2/ops/admin/stats","GET"),$("/api/v2/ops/admin/trend","GET"),$("/api/v2/ops/admin/trend/ops","GET"),$("/api/v2/ops/admin/audit-logs/recent?size=5&mine=false","GET").catch(()=>[]),$("/api/v2/ops/admin/finance/stats","GET").catch(()=>null),$("/api/v2/ops/admin/devices","GET").catch(()=>[]),$("/api/v2/ops/admin/workbench","GET").catch(()=>null)]);if(q!==t)return;const u=(m,k,w,T,O)=>{let h=`navigate('${T}')`;return O==="lowStock"?h="window.replenishmentFilters&&(window.replenishmentFilters.lowStockOnly=true);navigate('replenishment')":O==="pendingSplit"?h="window.merchantSplitFilters&&(window.merchantSplitFilters.status='PENDING');navigate('merchants')":O==="slotDiscrepancy"&&(h="showSlotDiscrepancies()"),`<div class="stat stat-click" role="button" tabindex="0" onclick="${h}" title="点击查看">
        <div class="label">${m}</div><div class="value ${w||""}">${k}</div>
      </div>`};e.innerHTML=`
      <div class="dashboard-head">
        <div>
          <h3 class="dashboard-head-title">核心指标</h3>
          <p class="dashboard-head-sub">今日运营数据 · 设备约每 30 秒更新在线状态</p>
        </div>
        ${ke("loadDashboard()","刷新")}
      </div>
      ${jn(s.deviceTotal>0&&s.deviceOnline===0)}
      <div class="stats">
        <div class="stat"><div class="label">设备总数</div><div class="value">${s.deviceTotal}</div></div>
        <div class="stat"><div class="label">在线设备</div><div class="value ${s.deviceOnline===0?"warn":"ok"}">${s.deviceOnline}</div></div>
        <div class="stat stat-click" role="button" tabindex="0" onclick="navigate('devices')" title="有活跃会话或补货任务的设备数">
          <div class="label">占用设备</div><div class="value ${s.deviceOccupied>0?"warn":""}">${s.deviceOccupied??0}</div>
        </div>
        <div class="stat stat-click" role="button" tabindex="0" onclick="navigate('sessions')" title="进行中的购物/识别/结算会话数">
          <div class="label">进行中会话</div><div class="value warn">${s.sessionActive}</div>
        </div>
        <div class="stat"><div class="label">今日会话</div><div class="value">${s.sessionToday}</div></div>
        <div class="stat"><div class="label">今日订单</div><div class="value">${s.orderToday}</div></div>
        <div class="stat"><div class="label">今日营收</div><div class="value ok">${C(s.revenueTodayCents)}</div></div>
        ${c?`<div class="stat stat-click" role="button" tabindex="0" onclick="navigate('finance')" title="点击查看毛利报表"><div class="label">今日毛利</div><div class="value ok">${C(c.grossMarginTodayCents)}</div></div>`:""}
        ${c?`<div class="stat stat-click" role="button" tabindex="0" onclick="navigate('finance')"><div class="label">今日销售成本</div><div class="value">${C(c.cogsTodayCents)}</div></div>`:""}
        ${c?`<div class="stat"><div class="label">今日报损</div><div class="value ${c.writeOffTodayCents>0?"warn":""}">${C(c.writeOffTodayCents)}</div></div>`:""}
        ${u("待审争议",s.disputeOpen,"warn","disputes")}
        ${u("超时未处理争议",s.disputeOverdue??0,s.disputeOverdue>0?"warn":"","disputes")}
        ${u("即将超时争议",s.disputeNearSla??0,s.disputeNearSla>0?"warn":"","disputes")}
        ${u("待上传会话",s.sessionWaitingUpload??0,"warn","upload-queue")}
        ${u("低库存商品",s.lowStockSkuCount??0,s.lowStockSkuCount>0?"warn":"","replenishment","lowStock")}
        ${u("临期批次",s.nearExpiryLotCount??0,s.nearExpiryLotCount>0?"warn":"","replenishment")}
        ${u("过期库存",s.expiredLotCount??0,s.expiredLotCount>0?"warn":"","replenishment")}
        ${u("待下架",s.pullOffOpenCount??0,s.pullOffOpenCount>0?"warn":"","replenishment")}
        ${u("账实差异货道",s.slotDiscrepancyCount??0,s.slotDiscrepancyCount>0?"warn":"","devices","slotDiscrepancy")}
        ${u("待分账",s.pendingSplitCount??0,s.pendingSplitCount>0?"warn":"","merchants","pendingSplit")}
        <div class="stat"><div class="label">24h 开门成功率</div><div class="value ok">${It(s.doorSuccessRate24h)}</div></div>
        ${u("24h 争议率",It(s.disputeRate24h),"","disputes")}
        <div class="stat"><div class="label">24h 自动识别率</div><div class="value ok">${It(s.recognitionAutoRate24h)}</div></div>
      </div>
      ${Vn(p)}
      ${cs(l,{fmtTime:Y,refreshFn:"refreshDashboardDevicePanel()"})}
      ${vn(s,a,n)}
      ${i&&i.length?`
      <div class="card">
        <div class="pane-head">
          <h3 style="margin:0;font-size:1rem;color:var(--text)">最新动态</h3>
          <button class="btn-ghost btn-sm" onclick="navigate('audit')">操作日志</button>
        </div>
        ${typeof renderAuditTableHtml=="function"?renderAuditTableHtml(i):""}
      </div>`:""}`,na()}catch(s){if(q!==t)return;J(e,s)}}async function Us(){if(q!=="dashboard")return;const e=document.getElementById("deviceLivePanel");if(!e){te();return}try{const t=await $("/api/v2/ops/admin/devices","GET");if(q!=="dashboard")return;e.outerHTML=cs(t,{fmtTime:Y,refreshFn:"refreshDashboardDevicePanel()"})}catch(t){b(t)||d("刷新柜机状态失败: "+he(t),"err")}}async function Fs(){const e=document.getElementById("pageContent"),t="finance";try{const s=await $("/api/v2/ops/admin/finance/report?days=7","GET");if(q!==t)return;const a=s.summary||{},n=(s.daily||[]).map(c=>`
      <tr>
        <td>${o(c.date)}</td>
        <td>${C(c.revenueCents)}</td>
        <td>${C(c.cogsCents)}</td>
        <td class="${c.grossMarginCents>=0?"ok-text":"warn-text"}">${C(c.grossMarginCents)}</td>
        <td>${C(c.writeOffCents)}</td>
      </tr>`).join(""),i=(s.topSkus||[]).map(c=>`
      <tr>
        <td>${o(c.skuName||c.skuId)}</td>
        <td><code>${o(c.skuId)}</code></td>
        <td>${o(c.qtySold)}</td>
        <td>${C(c.revenueCents)}</td>
        <td>${C(c.cogsCents)}</td>
        <td class="${c.grossMarginCents>=0?"ok-text":"warn-text"}">${C(c.grossMarginCents)}</td>
      </tr>`).join("");e.innerHTML=`
      <div class="dashboard-head">
        <div>
          <h3 class="dashboard-head-title">财务毛利</h3>
          <p class="dashboard-head-sub">营收减去商品采购成本 · 近 7 日明细</p>
        </div>
        ${ke("loadFinancePage()")}
      </div>
      <div class="stats">
        <div class="stat"><div class="label">今日营收</div><div class="value ok">${C(a.revenueTodayCents)}</div></div>
        <div class="stat"><div class="label">今日销售成本</div><div class="value">${C(a.cogsTodayCents)}</div></div>
        <div class="stat"><div class="label">今日毛利</div><div class="value ok">${C(a.grossMarginTodayCents)}</div></div>
        <div class="stat"><div class="label">今日报损</div><div class="value ${a.writeOffTodayCents>0?"warn":""}">${C(a.writeOffTodayCents)}</div></div>
        <div class="stat"><div class="label">累计营收</div><div class="value">${C(a.revenueTotalCents)}</div></div>
        <div class="stat"><div class="label">累计销售成本</div><div class="value">${C(a.cogsTotalCents)}</div></div>
        <div class="stat"><div class="label">累计毛利</div><div class="value ok">${C(a.grossMarginTotalCents)}</div></div>
      </div>
      <div class="card">
        <h3 style="margin-top:0">近 7 日趋势</h3>
        <table class="data-table">
          <thead><tr><th>日期</th><th>营收</th><th>销售成本</th><th>毛利</th><th>报损</th></tr></thead>
          <tbody>${n||'<tr><td colspan="5" class="meta">暂无数据</td></tr>'}</tbody>
        </table>
      </div>
      <div class="card">
        <h3 style="margin-top:0">商品毛利排行（近 7 日 Top 20）</h3>
        <p class="meta">请在「商品管理」填写各商品的采购成本，系统会在订单结算时自动计算毛利</p>
        <table class="data-table">
          <thead><tr><th>商品</th><th>商品编号</th><th>销量</th><th>营收</th><th>销售成本</th><th>毛利</th></tr></thead>
          <tbody>${i||'<tr><td colspan="6" class="meta">暂无销售</td></tr>'}</tbody>
        </table>
      </div>`}catch(s){if(q!==t)return;J(e,s)}}async function Hs(e,t){const s=await fetch(Ge+e,{headers:{Authorization:"Bearer "+ee}});if(s.status===401||s.status===403)throw b({status:s.status,message:"登录已失效"}),new Error("登录已失效");if(!s.ok)throw new Error("导出失败");const a=await s.blob(),n=URL.createObjectURL(a),i=document.createElement("a");i.href=n,i.download=t,i.click(),URL.revokeObjectURL(n)}async function Ne(){const e=document.getElementById("pageContent"),t="devices";N("devices");try{const s=await $("/api/v2/ops/admin/devices","GET");if(q!==t)return;e.innerHTML=`
      <div class="card list-page-card">
        ${F({onSearch:"searchDevices()",onReset:"resetDeviceFilters()",refreshFn:"loadDevices()",extraHtml:`${ae("device.create","注册新设备","showDeviceForm()","btn-primary btn-sm")}${H("devices")}`,fieldsHtml:R("关键词",`<input id="devKeyword" value="${r(it.keyword)}" placeholder="设备编号 / 名称 / 商户">`)})}
      </div>
      ${s.length?`
      <div class="card list-page-card" style="padding-top:0">
        ${j("devices",`<table class="data-table">
          <thead><tr>
            ${V("devices")}
            <th>设备编号</th><th>名称</th><th>运行状态</th><th>当前开门</th><th>最近在线</th><th>商户</th><th>类型</th><th class="col-actions">操作</th>
          </tr></thead>
          <tbody>${Wn(s).map(a=>`
            ${z("devices",a.deviceId)}
            ${W("devices",a.deviceId)}
            <td><code>${o(a.deviceId)}</code></td>
            <td>${o(a.deviceName||"-")}</td>
            <td title="${r(et(a).hint)}">${Sa(a)}</td>
            <td>${a.activeSessionId?`${Os(a.activeSessionState)}`:'<span class="meta">无</span>'}</td>
            <td>${Y(a.updatedAt)}</td>
            <td>${o(a.merchantName||a.merchantId||"-")}</td>
            <td>${o(Wa(a.deviceType))}</td>
            <td class="col-actions" onclick="event.stopPropagation()"><div class="row-actions">${S("ops:device:edit")?`<button type="button" class="btn-ghost btn-sm" onclick='showDeviceForm(${JSON.stringify(a)})'>编辑</button>`:""}
              <button type="button" class="btn-ghost btn-sm" onclick="viewDeviceDetail('${r(a.deviceId)}')">详情</button></div></td>
          </tr>`).join("")}</tbody>
        </table>`)}
      </div>`:`<div class="card">${P("暂无设备","点击「注册新设备」添加第一台柜机","loadDevices()")}</div>`}`,A("devices"),ce(),na()}catch(s){if(q!==t)return;J(e,s)}}function Wn(e){const t=(it.keyword||"").trim().toLowerCase();return t?e.filter(s=>[s.deviceId,s.deviceName,s.merchantName,s.merchantId].some(a=>String(a||"").toLowerCase().includes(t))):e}function zn(){var e;it.keyword=((e=document.getElementById("devKeyword"))==null?void 0:e.value.trim())||"",Ne()}function Kn(){it.keyword="",Ne()}async function Qn(e){const t=!!e;let s='<option value="">未绑定</option>';try{const a=await $("/api/v2/ops/admin/merchants","GET");s+=(a||[]).map(n=>`<option value="${r(n.merchantId)}" ${t&&e.merchantId===n.merchantId?"selected":""}>${o(n.merchantName)} (${o(n.merchantId)})</option>`).join("")}catch{}ge(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>${t?"编辑设备":"注册新设备"}</h3>
        <label>设备编号</label>
        <input id="dfId" value="${t?r(e.deviceId):""}" ${t?"disabled":""} placeholder="CAB-002">
        <label>设备名称</label>
        <input id="dfName" value="${t?r(e.deviceName||""):""}" placeholder="1号柜">
        <label>所属商户</label>
        <select id="dfMerchant">${s}</select>
        <label>设备类型</label>
        <input id="dfType" value="${t?r(e.deviceType||"AI_CABINET_V1"):"AI_CABINET_V1"}">
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveDevice(event, ${t})">保存</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`)}function Yn(e){return{FULL:"满",OK:"正常",LOW:"低库存",OOS:"缺货",DISABLED:"未启用"}[e]||e||"-"}function Jn(e){return`<span class="badge ${e==="FULL"||e==="OK"?"badge-active":e==="LOW"?"badge-warn":e==="OOS"?"badge-danger":"badge-muted"}">${o(Yn(e))}</span>`}async function Zn(e){if(S("ops:device:edit"))try{const t=await $("/api/v2/ops/admin/devices/"+encodeURIComponent(e)+"/slots/apply-template","POST");d(`已套用模板，新增 ${t} 个货道`,"ok"),Oe(e)}catch(t){b(t)||d("套用模板失败: "+t.message,"err")}}async function Oe(e){var t,s,a;try{const[n,i,c]=await Promise.all([$("/api/v2/ops/admin/devices/"+encodeURIComponent(e)+"/detail","GET"),$("/api/v2/ops/admin/slots/discrepancies?deviceId="+encodeURIComponent(e),"GET").catch(()=>[]),$("/api/v2/ops/admin/replenishment/suggest?deviceId="+encodeURIComponent(e),"GET").catch(()=>[])]),l=n.metrics||{},p=n.slots||[],u=p.reduce((g,E)=>Math.max(g,E.rowNo||0),0),m=p.reduce((g,E)=>Math.max(g,E.colNo||0),0),k=S("ops:device:edit"),w=S("ops:replenishment:edit"),T=[];for(let g=1;g<=Math.max(u,1);g++)for(let E=1;E<=Math.max(m,1);E++){const f=p.find(M=>M.rowNo===g&&M.colNo===E);if(!f)continue;const L=f.hasDiscrepancy?`<div class="slot-diff">账${f.bookQty} / 实${f.lastPhysicalQty} (${f.qtyDiff>0?"+":""}${f.qtyDiff})</div>`:"",I=k?`onclick="showSlotEditor('${r(e)}', '${r(f.slotCode)}')"`:w?`onclick="promptSlotStocktakeFor('${r(e)}','${r(f.slotCode)}',${f.bookQty})"`:"";T.push(`
          <div class="slot-cell slot-${r((f.stockStatus||"disabled").toLowerCase())}${f.hasDiscrepancy?" slot-mismatch":""} ${I?"slot-clickable":""}" ${I} title="${o(f.assignedSkuName||f.assignedSkuId||"未配置")}">
            <div class="slot-code">${o(f.slotCode)}</div>
            <div class="slot-sku">${o(f.assignedSkuName||f.assignedSkuId||"-")}</div>
            <div class="slot-qty">${f.bookQty}/${f.parLevel||"-"}</div>
            <div class="slot-meta">${Jn(f.stockStatus)} ${f.fillRatePct}%</div>
            ${L}
          </div>`)}const O=(c||[]).map(g=>`
      <tr>
        <td>${o(g.skuId)}</td>
        <td>${o(g.currentQty)}</td>
        <td>${o(g.inTransitQty??0)}</td>
        <td>${o(g.suggestQty)}</td>
        <td>${o(g.soldQty7d??0)}</td>
        <td>${o(g.ropPoint??0)}</td>
        <td><span class="badge badge-active">${o(g.suggestReason||"PAR")}</span></td>
      </tr>`).join(""),h=(i||[]).map(g=>`
      <tr>
        <td><code>${o(g.slotCode)}</code></td>
        <td>${o(g.assignedSkuName||g.assignedSkuId||"-")}</td>
        <td>${g.bookQty}</td>
        <td>${g.physicalQty}</td>
        <td class="${g.qtyDiff!==0?"warn-text":""}">${g.qtyDiff>0?"+":""}${g.qtyDiff}</td>
        <td>${Y(g.lastPhysicalAt)}</td>
        <td>${w?`<button class="btn-ghost btn-sm" onclick="promptSlotStocktakeFor('${r(e)}','${r(g.slotCode)}',${g.bookQty})">重盘</button>`:"-"}</td>
      </tr>`).join(""),y=(n.skuInventory||[]).map(g=>`
      <tr><td>${o(g.skuId)}</td><td>${g.quantity}/${g.capacity}</td><td>${o(g.lowThreshold)}</td></tr>`).join("");ge(`
      <div class="modal-backdrop device-detail-backdrop" onclick="closeModal(event)">
        <div class="modal modal-wide" onclick="event.stopPropagation()">
          <div class="device-detail-head">
            <div>
              <h3>${o(((t=n.device)==null?void 0:t.deviceName)||e)}</h3>
              <p class="meta"><code>${o(e)}</code> · ${xs((s=n.device)==null?void 0:s.onlineStatus)} · 最近在线 ${Y((a=n.device)==null?void 0:a.updatedAt)}</p>
            </div>
            <button type="button" class="btn-ghost btn-sm" data-modal-cancel onclick="closeModal()">关闭</button>
          </div>
          <div class="device-kpi-grid">
            <div class="kpi-card"><div class="kpi-label">补货率</div><div class="kpi-value">${l.fillRatePct??0}%</div></div>
            <div class="kpi-card"><div class="kpi-label">缺货率</div><div class="kpi-value">${l.oosRatePct??0}%</div></div>
            <div class="kpi-card"><div class="kpi-label">缺货通道</div><div class="kpi-value">${l.oosSlotCount??0}</div></div>
            <div class="kpi-card"><div class="kpi-label">低库存通道</div><div class="kpi-value">${l.lowStockSlotCount??0}</div></div>
            <div class="kpi-card"><div class="kpi-label">库存准确率</div><div class="kpi-value">${l.inventoryAccuracyPct??100}%</div></div>
            <div class="kpi-card"><div class="kpi-label">上次补货</div><div class="kpi-value kpi-sm">${l.lastRestockAt?Y(l.lastRestockAt):"暂无"}</div></div>
          </div>
          <div class="pane-head">
            <h4 style="margin:0">陈列图（货道）</h4>
            <div>
              ${k&&!p.length?`<button type="button" class="btn-ghost btn-sm" onclick="applyPlanogramTemplate('${r(e)}')">套用默认模板</button>`:""}
              ${k?`<button type="button" class="btn-primary btn-sm" onclick="showSlotEditor('${r(e)}', null)">添加货道</button>`:""}
            </div>
          </div>
          <p class="meta">${k?"点击货道可编辑配置；":""}${w?"可盘点更新实测数量":""}</p>
          <div class="slot-grid">${T.length?T.join(""):'<p class="meta">暂无货道配置</p>'}</div>
          ${h?`
          <h4 style="margin-top:16px;color:var(--warn)">账实差异告警 (${i.length})</h4>
          <table class="table"><thead><tr><th>货道</th><th>商品</th><th>账面</th><th>实测</th><th>差异</th><th>盘点时间</th><th>操作</th></tr></thead><tbody>${h}</tbody></table>`:""}
          <h4 style="margin-top:16px">商品库存汇总</h4>
          ${y?`<table class="table"><thead><tr><th>商品</th><th>数量/容量</th><th>低库存线</th></tr></thead><tbody>${y}</tbody></table>`:'<p class="meta">暂无</p>'}
          ${(c||[]).length?`
          <h4 style="margin-top:16px">动销 ROP 补货建议</h4>
          <table class="table"><thead><tr><th>商品</th><th>账面</th><th>在途</th><th>建议量</th><th>7日销量</th><th>补货点</th><th>策略</th></tr></thead><tbody>${O}</tbody></table>`:""}
          <div class="filters" style="margin-top:12px">
            ${w?`<button type="button" class="btn-ghost btn-sm" onclick="promptSlotStocktake('${r(e)}')">通道盘点</button>`:""}
            ${w?`<button type="button" class="btn-ghost btn-sm" onclick="closeModal();navigate('replenishment')">去补货管理</button>`:""}
          </div>
        </div>
      </div>`)}catch(n){b(n)||d("加载设备详情失败: "+n.message,"err")}}async function Xn(){try{const t=(await $("/api/v2/ops/admin/slots/discrepancies","GET")||[]).map(s=>`
      <tr>
        <td><button class="btn-link" onclick="closeModal();viewDeviceDetail('${r(s.deviceId)}')">${o(s.deviceName||s.deviceId)}</button></td>
        <td><code>${o(s.deviceId)}</code></td>
        <td><code>${o(s.slotCode)}</code></td>
        <td>${o(s.assignedSkuName||s.assignedSkuId||"-")}</td>
        <td>${s.bookQty}</td>
        <td>${s.physicalQty}</td>
        <td class="warn-text">${s.qtyDiff>0?"+":""}${s.qtyDiff}</td>
        <td>${Y(s.lastPhysicalAt)}</td>
      </tr>`).join("");ge(`
      <div class="modal-backdrop" onclick="closeModal(event)">
        <div class="modal modal-wide" onclick="event.stopPropagation()">
          <div class="device-detail-head">
            <h3>账实差异货道</h3>
            <button type="button" class="btn-ghost btn-sm" data-modal-cancel onclick="closeModal()">关闭</button>
          </div>
          ${t?`<table class="table"><thead><tr><th>设备</th><th>ID</th><th>货道</th><th>SKU</th><th>账面</th><th>实测</th><th>差异</th><th>盘点时间</th></tr></thead><tbody>${t}</tbody></table>`:P("暂无账实差异","完成通道盘点后将在此显示账面与实测不一致的货道","closeModal()")}
        </div>
      </div>`)}catch(e){b(e)||d("加载差异告警失败: "+e.message,"err")}}async function eo(e,t){let s=null;t&&(s=(await $("/api/v2/ops/admin/devices/"+encodeURIComponent(e)+"/slots","GET")||[]).find(p=>p.slotCode===t)||null);let a='<option value="">未绑定</option>';try{const l=await $("/api/v2/ops/admin/skus","GET");a+=(l||[]).map(p=>`<option value="${r(p.skuId)}" ${s&&s.assignedSkuId===p.skuId?"selected":""}>${o(p.skuName)} (${o(p.skuId)})</option>`).join("")}catch{}const n=!!s,i=(s==null?void 0:s.rowNo)||1,c=(s==null?void 0:s.colNo)||1;ge(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>${n?"编辑货道":"添加货道"} · ${o(e)}</h3>
        <div class="form-grid">
          <div><label>货道编号</label>
            <input id="seCode" value="${r((s==null?void 0:s.slotCode)||"")}" ${n?"disabled":""} placeholder="A1"></div>
          <div><label>行</label><input id="seRow" type="number" min="1" value="${i}"></div>
          <div><label>列</label><input id="seCol" type="number" min="1" value="${c}"></div>
          <div><label>类型</label>
            <select id="seType">
              <option value="SHELF" ${!s||s.slotType==="SHELF"?"selected":""}>层架 SHELF</option>
              <option value="HOOK" ${(s==null?void 0:s.slotType)==="HOOK"?"selected":""}>挂钩 HOOK</option>
              <option value="BASKET" ${(s==null?void 0:s.slotType)==="BASKET"?"selected":""}>篮筐 BASKET</option>
            </select></div>
          <div style="grid-column:1/-1"><label>绑定商品</label><select id="seSku">${a}</select></div>
          <div><label>标准容量 (PAR)</label><input id="sePar" type="number" min="0" value="${(s==null?void 0:s.parLevel)??8}"></div>
          <div><label>补货线 (MIN)</label><input id="seMin" type="number" min="0" value="${(s==null?void 0:s.minLevel)??2}"></div>
          <div><label>最大容量</label><input id="seMax" type="number" min="0" value="${(s==null?void 0:s.maxLevel)??(s==null?void 0:s.parLevel)??8}"></div>
          <div><label>启用</label>
            <select id="seEnabled">
              <option value="true" ${!s||s.enabled!==!1?"selected":""}>是</option>
              <option value="false" ${s&&s.enabled===!1?"selected":""}>否</option>
            </select></div>
        </div>
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveSlotConfig(event, '${r(e)}', ${n})">保存</button>
          ${n&&S("ops:device:edit")?`<button type="button" class="btn-ghost" onclick="deleteSlotConfig('${r(e)}','${r(s.slotCode)}')">删除货道</button>`:""}
          ${S("ops:replenishment:edit")&&n?`<button type="button" class="btn-ghost" onclick="promptSlotStocktakeFor('${r(e)}','${r(s.slotCode)}',${s.bookQty??0})">盘点此货道</button>`:""}
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal();viewDeviceDetail('${r(e)}')">返回详情</button>
        </div>
      </div>
    </div>`)}async function to(e,t,s){await _(e,async()=>{var u;const a=(((u=document.getElementById("seCode"))==null?void 0:u.value)||"").trim().toUpperCase(),n=parseInt(document.getElementById("seRow").value,10),i=parseInt(document.getElementById("seCol").value,10),c=parseInt(document.getElementById("sePar").value,10),l=parseInt(document.getElementById("seMin").value,10),p=parseInt(document.getElementById("seMax").value,10);if(!a||Number.isNaN(n)||Number.isNaN(i)||Number.isNaN(c)){d("请填写货道编号、行列与标准容量","err");return}try{await $("/api/v2/ops/admin/devices/"+encodeURIComponent(t)+"/slots","PUT",[{slotCode:a,rowNo:n,colNo:i,slotType:document.getElementById("seType").value,assignedSkuId:document.getElementById("seSku").value||null,parLevel:c,minLevel:l,maxLevel:Number.isNaN(p)?c:p,enabled:document.getElementById("seEnabled").value==="true"}]),d("货道已保存","ok"),de(),Oe(t)}catch(m){b(m)||d("保存失败: "+m.message,"err")}})}async function so(e,t){if(await ie(`确认删除货道 ${t}？仅无账面库存时可删除。`,{title:"删除货道"}))try{await $("/api/v2/ops/admin/devices/"+encodeURIComponent(e)+"/slots/"+encodeURIComponent(t),"DELETE"),d("货道已删除","ok"),de(),Oe(e)}catch(s){b(s)||d("删除失败: "+s.message,"err")}}async function ao(e,t,s){const a=prompt(`货道 ${t} 实测数量
当前账面：${s}`,String(s));if(a==null||a==="")return;const n=parseInt(a,10);if(Number.isNaN(n)||n<0){d("请输入有效数量","err");return}try{await $("/api/v2/ops/admin/devices/"+encodeURIComponent(e)+"/slots/stocktake","POST",{slotCode:t.trim(),physicalQty:n}),d("盘点已记录","ok"),de(),Oe(e)}catch(i){b(i)||d("盘点失败: "+i.message,"err")}}async function no(e){const t=prompt("货道编号（如 A1）");if(!t)return;const s=prompt("实测数量");if(s==null||s==="")return;const a=parseInt(s,10);if(Number.isNaN(a)||a<0){d("请输入有效数量","err");return}try{await $("/api/v2/ops/admin/devices/"+encodeURIComponent(e)+"/slots/stocktake","POST",{slotCode:t.trim(),physicalQty:a}),d("盘点已记录","ok"),Oe(e)}catch(n){b(n)||d("盘点失败: "+n.message,"err")}}async function oo(e,t){await _(e,async()=>{var c;const s=document.getElementById("dfId").value.trim(),a=document.getElementById("dfName").value.trim(),n=document.getElementById("dfType").value.trim(),i=((c=document.getElementById("dfMerchant"))==null?void 0:c.value)||"";try{t?await $("/api/v2/ops/admin/devices/"+encodeURIComponent(s),"PATCH",{deviceName:a,deviceType:n,merchantId:i}):await $("/api/v2/ops/admin/devices","POST",{deviceId:s,deviceName:a,deviceType:n,merchantId:i||null}),de(),d("保存成功","ok"),Ne()}catch(l){b(l)||d("保存失败: "+l.message,"err")}})}function _s(){N("sessions"),document.getElementById("pageContent").innerHTML=`
    <div class="card list-page-card">
      ${F({onSearch:"searchSessions()",onReset:"resetSessionFilters()",refreshFn:"fetchSessions()",extraHtml:`<button type="button" class="btn-ghost btn-sm" onclick="exportSessionsCsv()">导出 CSV</button>${H("sessions",`<button type="button" class="btn-ghost btn-sm" onclick="selClear('sessions')">清除选择</button>`)}`,fieldsHtml:`
          ${R("设备编号",`<input id="sfDevice" value="${r(G.deviceId)}" placeholder="CAB-001">`)}
          ${R("状态",`<select id="sfState">
            <option value="">全部</option>
            ${["CREATED","OPENING","SHOPPING","RECOGNIZING","WAITING_UPLOAD","SETTLING","COMPLETED","DISPUTED","FAILED","CANCELLED"].map(e=>`<option value="${e}" ${G.state===e?"selected":""}>${o(Rt(e))}</option>`).join("")}
          </select>`)}`})}
      <div id="sessionTable"></div>
    </div>`,Q(document.getElementById("sessionTable"),7,6),We()}function io(){G.deviceId="",G.state="",G.page=0,_s()}async function qs(){G.deviceId=document.getElementById("sfDevice").value.trim(),G.state=document.getElementById("sfState").value,G.page=0,We()}function co(e){const t=[];return e.orderId&&S("ops:order:list")&&t.push(`<button type="button" class="btn-ghost btn-sm" onclick="showOrderDetail('${r(e.orderId)}')">订单</button>`),e.state==="DISPUTED"&&S("ops:dispute")&&t.push(`<button type="button" class="btn-ghost btn-sm" onclick="openDisputeForSession('${r(e.sessionId)}')">争议</button>`),!["COMPLETED","CANCELLED"].includes(e.state)&&S("ops:session:cancel")&&t.push(`<button type="button" class="btn-danger btn-sm" onclick="cancelSession('${r(e.sessionId)}')">取消</button>`),t.length?`<div class="row-actions">${t.join("")}</div>`:'<span class="meta">-</span>'}function lo(e){x.sessionId=e,x.page=0,Me("disputes")}async function We(){const e=document.getElementById("sessionTable");if(e){Q(e,7,6);try{const t=new URLSearchParams({page:G.page,size:G.size,...G.deviceId?{deviceId:G.deviceId}:{},...G.state?{state:G.state}:{}}),s=await $("/api/v2/ops/admin/sessions?"+t,"GET");if(!s.items.length){e.innerHTML=P("暂无会话","调整筛选条件或等待用户开门购物","fetchSessions()");return}const a=i=>!["COMPLETED","CANCELLED"].includes(i.state),n=Ot(s.items,X.sessions.field,X.sessions.dir);e.innerHTML=j("sessions",`
      <table class="data-table table-sessions">
        <thead><tr>
          ${V("sessions")}
          <th>记录编号</th><th>用户</th><th>设备</th><th>状态</th><th>录像</th><th>订单</th><th>视频</th>
          ${Te("sessions","createdAt","创建时间",X.sessions)}<th class="col-actions">操作</th>
        </tr></thead>
        <tbody>${n.map(i=>`
          ${z("sessions",i.sessionId)}
          ${W("sessions",i.sessionId)}
          <td><code>${o(i.sessionId)}</code></td>
          <td>${o(i.userId)}</td>
          <td>${o(i.deviceId)}</td>
          <td>${Os(i.state)}</td>
          <td>${o(os(i.uploadStatus))}</td>
          <td>${i.orderId?`<code class="meta">${o(i.orderId)}</code>`:"-"}</td>
          <td onclick="event.stopPropagation()">${i.videoUri||i.videoPreviewUrl?`<button type="button" class="btn-ghost btn-sm" onclick="showSessionVideo('${r(i.sessionId)}', '${r(i.videoUri||"")}')">${At(i.videoUri)}</button>`:"-"}</td>
          <td>${Y(i.createdAt)}</td>
          <td class="col-actions" onclick="event.stopPropagation()">${co(i)}</td>
        </tr>`).join("")}</tbody>
      </table>`)+xe(s,"session"),A("sessions")}catch(t){J(e,t,!1)}}}async function ro(){try{const e=new URLSearchParams({...G.deviceId?{deviceId:G.deviceId}:{},...G.state?{state:G.state}:{}});await Hs("/api/v2/ops/admin/sessions/export?"+e,"sessions.csv")}catch(e){b(e)||d(e.message,"err")}}async function uo(e){if(await ie("确认取消会话 "+e+"？设备将可再次开门。",{title:"取消会话",danger:!0}))try{await $("/api/v2/ops/admin/sessions/"+e+"/cancel","POST"),d("会话已取消","ok"),We(),q==="devices"&&Ne()}catch(t){b(t)||d("失败: "+t.message,"err")}}function js(){N("orders"),document.getElementById("pageContent").innerHTML=`
    <div class="card list-page-card">
      ${F({onSearch:"searchOrders()",onReset:"resetOrderFilters()",refreshFn:"fetchOrders()",extraHtml:`<button type="button" class="btn-ghost btn-sm" onclick="exportOrdersCsv()">导出 CSV</button>${H("orders")}`,fieldsHtml:R("设备编号",`<input id="ofDevice" value="${r(oe.deviceId)}" placeholder="留空=全部">`)})}
      <div id="orderTable"></div>
    </div>`,Q(document.getElementById("orderTable"),8,6),lt()}function po(){oe.deviceId="",oe.page=0,js()}function Gs(){oe.deviceId=document.getElementById("ofDevice").value.trim(),oe.page=0,lt()}async function mo(){try{const e=new URLSearchParams(oe.deviceId?{deviceId:oe.deviceId}:{});await Hs("/api/v2/ops/admin/orders/export?"+e,"orders.csv")}catch(e){b(e)||d(e.message,"err")}}async function lt(){const e=document.getElementById("orderTable");if(e){Q(e,8,6);try{const t=new URLSearchParams({page:oe.page,size:oe.size,...oe.deviceId?{deviceId:oe.deviceId}:{}}),s=await $("/api/v2/ops/admin/orders?"+t,"GET");if(!s.items.length){e.innerHTML=P("暂无订单","完成购物后会在此展示订单记录","fetchOrders()");return}const a=Ot(s.items,X.orders.field,X.orders.dir);e.innerHTML=j("orders",`
      <table class="data-table">
        <thead><tr>
          ${V("orders")}
          <th>订单号</th><th>开门记录</th>${Te("orders","userId","用户",X.orders)}<th>设备</th>
          ${Te("orders","totalAmountCents","金额",X.orders)}<th>商品行</th>
          ${Te("orders","createdAt","时间",X.orders)}<th class="col-actions">操作</th>
        </tr></thead>
        <tbody>${a.map(n=>`
          ${z("orders",n.orderId)}
          ${W("orders",n.orderId)}
          <td><code>${o(n.orderId)}</code></td>
          <td>${o(n.sessionId)}</td>
          <td>${o(n.userId)}</td>
          <td>${o(n.deviceId)}</td>
          <td>${C(n.totalAmountCents)}</td>
          <td>${o(n.lineCount)}</td>
          <td>${Y(n.createdAt)}</td>
          <td class="col-actions" onclick="event.stopPropagation()"><div class="row-actions"><button class="btn-ghost btn-sm" onclick="showOrderDetail('${r(n.orderId)}')">详情</button></div></td>
        </tr>`).join("")}</tbody>
      </table>`)+xe(s,"order"),A("orders")}catch(t){J(e,t,!1)}}}function xe(e,t){return ot(e,t)}function ho(e,t){var a;const s=parseInt(t,10)||20;e==="session"?G.size=s:e==="user"?B.size=s:e==="audit"?tt.size=s:e==="recharge"?Z.size=s:e==="dispute"?x.size=s:e==="upload"?window.uploadQueueFilters&&(window.uploadQueueFilters.size=s):e==="merchantSplit"?window.merchantSplitFilters&&(window.merchantSplitFilters.size=s):e==="rbacOp"?(a=window._rbacState)!=null&&a.operatorFilters&&(window._rbacState.operatorFilters.size=s):oe.size=s,Ut(e,0)}function vo(e,t){const s=Math.max(1,parseInt(t,10)||1)-1;Ut(e,s)}function Ut(e,t){var s;e==="session"?(G.page=Math.max(0,t),We()):e==="user"?(B.page=Math.max(0,t),Ue()):e==="audit"?(tt.page=Math.max(0,t),Ht()):e==="recharge"?(Z.page=Math.max(0,t),dt()):e==="dispute"?(x.page=Math.max(0,t),De()):e==="upload"?(window.uploadQueueFilters&&(window.uploadQueueFilters.page=Math.max(0,t)),typeof fetchUploadQueue=="function"&&fetchUploadQueue()):e==="merchantSplit"?(window.merchantSplitFilters&&(window.merchantSplitFilters.page=Math.max(0,t)),typeof fetchMerchantSplits=="function"&&fetchMerchantSplits()):e==="rbacOp"?((s=window._rbacState)!=null&&s.operatorFilters&&(window._rbacState.operatorFilters.page=Math.max(0,t)),typeof fetchRbacOperators=="function"&&fetchRbacOperators()):(oe.page=Math.max(0,t),lt())}async function bo(e){try{const t=await $("/api/v2/ops/admin/orders/"+e,"GET"),s=(t.lines||[]).map(a=>`<tr><td>${o(a.skuName)}</td><td>${o(a.skuId)}</td><td>${o(a.quantity)}</td><td><code>${o(a.batchNo||"-")}</code></td><td>${C(a.unitPriceCents)}</td><td>${C(a.lineAmountCents)}</td></tr>`).join("");ge(`
      <div class="modal-backdrop" onclick="closeModal(event)">
        <div class="modal" onclick="event.stopPropagation()">
          <h3>订单 ${o(t.orderId)}</h3>
          <div class="meta">会话 ${o(t.sessionId)} · 设备 ${o(t.deviceId)} · 用户 ${o(t.userId)}</div>
          <table style="margin-top:12px">
            <thead><tr><th>商品</th><th>SKU</th><th>数量</th><th>批次</th><th>单价</th><th>小计</th></tr></thead>
            <tbody>${s}</tbody>
          </table>
          <p style="margin-top:12px;font-weight:700">合计 ${C(t.totalAmountCents)}</p>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">关闭</button>
        </div>
      </div>`)}catch(t){b(t)||d("加载失败: "+t.message,"err")}}function de(e){e&&e.target!==e.currentTarget||(Bt(),Mt(),document.getElementById("modalRoot").classList.add("hidden"),document.getElementById("modalRoot").innerHTML="")}function ge(e,t){document.getElementById("modalRoot").innerHTML=e,document.getElementById("modalRoot").classList.remove("hidden"),Nt(t||(()=>de()))}async function Vs(){try{ve=Ws(await $("/api/v2/ops/admin/skus","GET"))}catch{ve=[{skuId:"SKU-DEMO-001",skuName:"示例商品",priceCents:350,status:"ACTIVE",visionEnabled:!0}]}}function rt(){N("skus"),document.getElementById("pageContent").innerHTML=`
    <div class="card list-page-card">
      ${F({onSearch:"searchSkus()",onReset:"resetSkuFilters()",refreshFn:"fetchSkusTable()",extraHtml:`${ae("sku.edit","新增商品","showSkuForm()","btn-primary btn-sm")}${ae("sku.edit","编辑所选","editSelectedSku()","btn-ghost btn-sm")}${H("skus")}`,fieldsHtml:`
          ${R("商品名称",`<input id="skuFilterName" value="${r(re.name)}" placeholder="支持模糊搜索">`)}
          ${R("状态",`<select id="skuFilterStatus">
            <option value="">全部</option>
            <option value="ACTIVE" ${re.status==="ACTIVE"?"selected":""}>上架</option>
            <option value="INACTIVE" ${re.status==="INACTIVE"?"selected":""}>下架</option>
          </select>`)}`})}
      <div id="skuTable"></div>
    </div>`,Q(document.getElementById("skuTable"),10,5),Ks()}function fo(){var e,t;re.name=((e=document.getElementById("skuFilterName"))==null?void 0:e.value.trim())||"",re.status=((t=document.getElementById("skuFilterStatus"))==null?void 0:t.value)||"",Ks()}function go(){re.name="",re.status="",rt()}function yo(e){const t=(re.name||"").trim().toLowerCase(),s=re.status||"";return(e||[]).filter(a=>!(s&&a.status!==s||t&&!String(a.skuName||"").toLowerCase().includes(t)&&!String(a.skuId||"").toLowerCase().includes(t)))}function $o(){const e=Dt("skus");if(e.length!==1){d("请勾选恰好 1 个商品再编辑","err");return}zs(e[0])}function Io(e){return e==="INACTIVE"?"下架":"上架"}function ko(e,t){return e?`<img src="${r(e)}" alt="${r(t||"")}" class="sku-thumb" loading="lazy"
    referrerpolicy="no-referrer"
    onerror="this.replaceWith(Object.assign(document.createElement('span'),{className:'meta',textContent:'无图'}))">`:'<span class="meta">-</span>'}function Ws(e){return[...e||[]].sort((t,s)=>String(t.skuId).localeCompare(String(s.skuId),"zh-CN"))}function zs(e){const t=ve.find(s=>s.skuId===e);if(!t){d("商品不存在或列表未刷新","err");return}Qs(t)}async function Ks(){const e=document.getElementById("skuTable");if(e){Q(e,9,5);try{ve=await $("/api/v2/ops/admin/skus","GET");const t=Ws(yo(ve));if(!t.length){e.innerHTML=P("暂无商品",re.name||re.status?"调整筛选条件后重试":"添加商品后可在争议审核中选择","fetchSkusTable()");return}e.innerHTML=j("skus",`
      <table class="data-table table-sku">
        <thead><tr>
          ${V("skus")}
          <th>商品编号</th><th>名称</th><th>分类</th><th>价格</th><th>重量(g)</th><th>条码</th><th>状态</th><th>图片</th><th class="col-actions">操作</th>
        </tr></thead>
        <tbody>${t.map(s=>`
          ${z("skus",s.skuId)}
          ${W("skus",s.skuId)}
          <td><code>${o(s.skuId)}</code></td>
          <td>${o(s.skuName)}</td>
          <td>${o(s.category||"-")}</td>
          <td>${C(s.priceCents)}</td>
          <td>${s.weightGrams!=null?o(s.weightGrams):"-"}</td>
          <td>${o(s.barcode||"-")}</td>
          <td>${Io(s.status)}${s.visionEnabled===!1?" · 无视觉":""}</td>
          <td>${ko(s.imageUrl,s.skuName)}</td>
          <td class="col-actions" onclick="event.stopPropagation()"><div class="row-actions">${S("ops:sku:edit")?`<button type="button" class="btn-ghost btn-sm" onclick="showSkuFormById('${r(s.skuId)}')">编辑</button>`:'<span class="meta">-</span>'}</div></td>
        </tr>`).join("")}</tbody>
      </table>`),A("skus")}catch(t){J(e,t,!1)}}}function Qs(e){const t=!!e;ge(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" style="max-width:640px" onclick="event.stopPropagation()">
        <h3>${t?"编辑商品":"新增商品"}</h3>
        <label>商品编号</label>
        <input id="skuId" value="${t?r(e.skuId):""}" ${t?"disabled":""} placeholder="例如 SKU-COLA-001">
        <label>商品名称</label>
        <input id="skuName" value="${t?r(e.skuName):""}" placeholder="可乐 330ml">
        <div class="filters form-grid">
          <div><label>分类</label><input id="skuCategory" value="${t?r(e.category||""):""}" placeholder="饮料"></div>
          <div><label>条码</label><input id="skuBarcode" value="${t?r(e.barcode||""):""}" placeholder="6901234567890"></div>
          <div><label>销售价（元）</label><input id="skuPrice" type="number" min="0.01" step="0.01" value="${t?(e.priceCents/100).toFixed(2):"3.50"}"></div>
          <div><label>采购成本（元）</label><input id="skuCost" type="number" min="0" step="0.01" value="${t&&e.purchaseCostCents!=null?(e.purchaseCostCents/100).toFixed(2):""}" placeholder="2.80"></div>
          <div><label>重量（克）</label><input id="skuWeight" type="number" min="0" value="${t&&e.weightGrams!=null?e.weightGrams:""}" placeholder="330"></div>
        </div>
        <p class="meta">价格与成本以元为单位填写，用于售价展示与毛利计算</p>
        <label>商品描述</label>
        <textarea id="skuDescription" rows="3" placeholder="规格、口味、包装说明等">${t?o(e.description||""):""}</textarea>
        <div class="filters">
          <div><label>状态</label>
            <select id="skuStatus">
              <option value="ACTIVE" ${!t||e.status!=="INACTIVE"?"selected":""}>上架</option>
              <option value="INACTIVE" ${t&&e.status==="INACTIVE"?"selected":""}>下架</option>
            </select>
          </div>
          <div style="display:flex;align-items:flex-end;padding-bottom:8px">
            <label style="display:flex;align-items:center;gap:8px;margin:0">
              <input id="skuVisionEnabled" type="checkbox" ${!t||e.visionEnabled!==!1?"checked":""}>
              参与视觉识别
            </label>
          </div>
        </div>
        <h4 style="margin:12px 0 8px">保质期 / 效期</h4>
        <div class="filters form-grid">
          <div><label>保质期（天）</label>
            <input id="skuShelfLife" type="number" min="0" value="${t&&e.shelfLifeDays!=null?e.shelfLifeDays:""}" placeholder="180"></div>
          <div><label>临期提醒（天）</label>
            <input id="skuNearExpiry" type="number" min="1" value="${t?e.nearExpiryDays??7:7}"></div>
          <div><label>到期前禁售（天）</label>
            <input id="skuBlockSale" type="number" min="0" value="${t?e.blockSaleDaysBeforeExpiry??0:0}"></div>
          <div><label>存储类型</label>
            <select id="skuStorageType">
              <option value="AMBIENT" ${!t||e.storageType==="AMBIENT"||!e.storageType?"selected":""}>常温</option>
              <option value="CHILLED" ${t&&e.storageType==="CHILLED"?"selected":""}>冷藏</option>
              <option value="FROZEN" ${t&&e.storageType==="FROZEN"?"selected":""}>冷冻</option>
            </select>
          </div>
        </div>
        <label>图片 URL</label>
        <input id="skuImageUrl" value="${t?r(e.imageUrl||""):""}" placeholder="https://example.com/cola.jpg" oninput="previewSkuImage()">
        <div id="skuImagePreview" class="sku-preview-wrap">${t&&e.imageUrl?`<img src="${r(e.imageUrl)}" alt="预览" class="sku-preview" referrerpolicy="no-referrer">`:'<span class="meta">填写 URL 后显示预览</span>'}</div>
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveSku(event, ${t})">保存</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`)}function wo(){var s;const e=(s=document.getElementById("skuImageUrl"))==null?void 0:s.value.trim(),t=document.getElementById("skuImagePreview");if(t){if(!e){t.innerHTML='<span class="meta">填写 URL 后显示预览</span>';return}t.innerHTML=`<img src="${r(e)}" alt="预览" class="sku-preview"
    onerror="this.parentElement.innerHTML='<span class=\\'meta\\'>图片无法加载</span>'">`}}async function Eo(e,t){await _(e,async()=>{const s=document.getElementById("skuId").value.trim(),a=document.getElementById("skuName").value.trim(),n=parseFloat(document.getElementById("skuPrice").value),i=document.getElementById("skuCost").value.trim(),c=Math.round(n*100),l=i?Math.round(parseFloat(i)*100):null,p=document.getElementById("skuWeight").value.trim(),u=p?parseInt(p,10):null,m=document.getElementById("skuImageUrl").value.trim(),k=document.getElementById("skuCategory").value.trim(),w=document.getElementById("skuBarcode").value.trim(),T=document.getElementById("skuDescription").value.trim(),O=document.getElementById("skuStatus").value,h=document.getElementById("skuVisionEnabled").checked,y=document.getElementById("skuShelfLife").value.trim(),g=y?parseInt(y,10):null,E=parseInt(document.getElementById("skuNearExpiry").value,10)||7,f=parseInt(document.getElementById("skuBlockSale").value,10)||0,L=document.getElementById("skuStorageType").value||"AMBIENT";if(!s||!a||!c||Number.isNaN(n)||n<=0){d("请填写商品编号、名称和有效售价","err");return}try{const I={skuId:s,skuName:a,priceCents:c,status:O,visionEnabled:h,nearExpiryDays:E,blockSaleDaysBeforeExpiry:f,storageType:L,...g!=null&&!Number.isNaN(g)?{shelfLifeDays:g}:{},...u!=null&&!Number.isNaN(u)?{weightGrams:u}:{},...l!=null&&!Number.isNaN(l)?{purchaseCostCents:l}:{},...m?{imageUrl:m}:{},...k?{category:k}:{},...w?{barcode:w}:{},...T?{description:T}:{}};t?await $("/api/v2/ops/admin/skus/"+encodeURIComponent(s),"PUT",I):await $("/api/v2/ops/admin/skus","POST",I),de(),d("保存成功","ok"),rt()}catch(I){b(I)||d("保存失败: "+I.message,"err")}})}function Ys(){N("users"),document.getElementById("pageContent").innerHTML=`
    <div class="card list-page-card">
      ${F({onSearch:"searchUsers()",onReset:"resetUserFilters()",refreshFn:"fetchUsers()",extraHtml:H("users"),fieldsHtml:`
          ${R("手机号",`<input id="ufPhone" value="${r(B.phone)}" placeholder="支持模糊搜索">`)}
          ${R("姓名",`<input id="ufName" value="${r(B.name)}" placeholder="支持模糊搜索">`)}
          ${R("角色",`<select id="ufRole">
            <option value="">全部</option>
            <option value="CONSUMER" ${B.role==="CONSUMER"?"selected":""}>消费者</option>
            <option value="OPERATOR" ${B.role==="OPERATOR"?"selected":""}>运营</option>
          </select>`)}
          ${R("实名状态",`<select id="ufVerified">
            <option value="">全部</option>
            <option value="true" ${B.verified==="true"?"selected":""}>已实名</option>
            <option value="false" ${B.verified==="false"?"selected":""}>未实名</option>
          </select>`)}`})}
      <div id="userTable"></div>
    </div>`,Q(document.getElementById("userTable"),8,6),Ue()}function So(){var e,t,s,a;B.phone=((e=document.getElementById("ufPhone"))==null?void 0:e.value.trim())||"",B.name=((t=document.getElementById("ufName"))==null?void 0:t.value.trim())||"",B.role=((s=document.getElementById("ufRole"))==null?void 0:s.value)||"",B.verified=((a=document.getElementById("ufVerified"))==null?void 0:a.value)||""}function To(){B.phone="",B.name="",B.role="",B.verified="",B.page=0,Ys()}function Js(){So(),B.page=0,Ue()}async function Ue(){const e=document.getElementById("userTable");if(e){Q(e,8,6);try{const t=new URLSearchParams({page:B.page,size:B.size,...B.phone?{phone:B.phone}:{},...B.name?{name:B.name}:{},...B.role?{role:B.role}:{},...B.verified!==""?{verified:B.verified}:{}}),s=await $("/api/v2/ops/admin/users?"+t,"GET");if(!s.items.length){e.innerHTML=P("暂无用户","消费者通过小程序注册后会出现在此列表","fetchUsers()");return}const a=Ot(s.items,X.users.field,X.users.dir);e.innerHTML=j("users",`
      <table class="data-table">
        <thead><tr>
          ${V("users")}
          ${Te("users","userId","用户编号",X.users)}
          <th>手机号</th><th>姓名</th><th>角色</th><th>实名</th>
          ${Te("users","balanceCents","余额",X.users)}
          ${Te("users","createdAt","注册时间",X.users)}
          <th class="col-actions">操作</th>
        </tr></thead>
        <tbody>${a.map(n=>`
          ${z("users",n.userId)}
          ${W("users",n.userId)}
          <td>${o(n.userId)}</td>
          <td>${o(n.phoneNumber)}</td>
          <td>${o(n.name||"-")}</td>
          <td>${n.role==="OPERATOR"?'<span class="badge badge-active">运营</span>':'<span class="badge badge-done">消费者</span>'}</td>
          <td>${n.verified?'<span class="badge badge-done">已实名</span>':'<span class="meta">未实名</span>'}</td>
          <td>${C(n.balanceCents)}</td>
          <td>${Y(n.createdAt)}</td>
          <td class="col-actions" onclick="event.stopPropagation()"><div class="row-actions">${n.role==="OPERATOR"?S("ops:rbac:assign")?`<button class="btn-ghost btn-sm" onclick="showRbacAssignForUser(${n.userId})">分配角色</button>`:'<span class="meta">-</span>':`${S("ops:user:balance")?`<button class="btn-ghost btn-sm" onclick="showBalanceForm(${n.userId}, ${n.balanceCents})">调余额</button>`:""}
                ${S("ops:user:list")?n.verified?`<button class="btn-ghost btn-sm" onclick="showVerifyUserForm(${n.userId}, false, '${r(n.name||"")}')">取消实名</button>`:`<button class="btn-ghost btn-sm" onclick="showVerifyUserForm(${n.userId}, true, '')">标记实名</button>`:""}`}</div></td>
        </tr>`).join("")}</tbody>
      </table>`)+xe(s,"user"),A("users")}catch(t){J(e,t,!1)}}}function Lo(e,t){ge(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>调整余额</h3>
        <p class="meta">当前余额 ${C(t)}</p>
        <label>调整金额（元，正数充值 / 负数扣减）</label>
        <input id="deltaYuan" type="number" step="0.01" value="10.00" placeholder="10.00">
        <p class="meta">例：10 表示加 ¥10.00；-3.5 表示扣 ¥3.50</p>
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveBalance(event, ${e})">确认</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`)}async function Co(e,t){await _(e,async()=>{const s=parseFloat(document.getElementById("deltaYuan").value),a=Math.round(s*100);if(isNaN(s)||a===0){d("请输入有效金额","err");return}try{await $("/api/v2/ops/admin/users/"+t+"/balance","POST",{deltaCents:a}),de(),Ue(),d("余额已更新","ok")}catch(n){b(n)||d("失败: "+n.message,"err")}})}async function Ft(e,t,s){try{await $("/api/v2/ops/admin/users/"+e+"/verify","POST",{verified:t,...s?{realName:s}:{}}),Ue(),d("实名状态已更新","ok")}catch(a){b(a)||d("失败: "+a.message,"err")}}async function Ro(e,t,s){const a=t?`标记实名 · userId ${e}`:`取消实名 · userId ${e}`;if(!t)return await ie(`确认取消用户 ${e} 的实名状态？`,{title:"取消实名",danger:!0})?Ft(e,!1):void 0;ge(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>${o(a)}</h3>
        <label>真实姓名（可选）</label>
        <input id="verifyRealName" value="${r(s||"")}" placeholder="张三">
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveVerifyUser(event, ${e})">确认实名</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`)}async function Bo(e,t){await _(e,async()=>{const s=document.getElementById("verifyRealName").value.trim();de(),await Ft(t,!0,s||void 0)})}function Po(e){typeof openRbacUserAssign=="function"?openRbacUserAssign(e):Me("rbac")}async function Ao(){const e=document.getElementById("pageContent"),t="reports";N("reports");try{const s=await $("/api/v2/ops/admin/reports/devices","GET");if(q!==t)return;if(e.innerHTML=`
      <div class="card list-page-card">
        ${F({refreshFn:"loadReportsPage()",extraHtml:H("reports"),fieldsHtml:""})}
      </div>`,!s.length){e.innerHTML+=`<div class="card">${P("暂无设备报表","注册设备并产生订单后自动生成统计","loadReportsPage()")}</div>`;return}e.innerHTML+=`
      <div class="card list-page-card" style="padding-top:0">
        ${j("reports",`<table class="data-table">
          <thead><tr>
            ${V("reports")}
            <th>设备</th><th>状态</th><th>累计订单</th><th>累计营收</th>
            <th>今日订单</th><th>今日营收</th><th>累计会话</th><th>进行中</th>
          </tr></thead>
          <tbody>${s.map(a=>`
            ${z("reports",a.deviceId)}
            ${W("reports",a.deviceId)}
            <td><code>${o(a.deviceId)}</code><br><span class="meta">${o(a.deviceName||"-")}</span></td>
            <td>${xs(a.onlineStatus)}</td>
            <td>${a.orderTotal}</td>
            <td>${C(a.revenueTotalCents)}</td>
            <td>${a.orderToday}</td>
            <td>${C(a.revenueTodayCents)}</td>
            <td>${a.sessionTotal}</td>
            <td>${a.sessionActive?'<span class="badge badge-active">是</span>':"-"}</td>
          </tr>`).join("")}</tbody>
        </table>`)}
      </div>`,A("reports")}catch(s){if(q!==t)return;J(e,s)}}function Do(){N("audit"),document.getElementById("pageContent").innerHTML=`
    <div class="card list-page-card">
      ${F({refreshFn:"fetchAuditLogs()",extraHtml:H("audit"),fieldsHtml:""})}
      <div id="auditTable"></div>
    </div>`,Q(document.getElementById("auditTable"),5,6),Ht()}async function Ht(){const e=document.getElementById("auditTable");if(e){Q(e,5,6);try{const t=new URLSearchParams({page:tt.page,size:tt.size}),s=await $("/api/v2/ops/admin/audit-logs?"+t,"GET");if(!s.items.length){e.innerHTML=P("暂无操作记录","运营人员的敏感操作会记录在此","fetchAuditLogs()");return}e.innerHTML=(typeof renderAuditTableHtml=="function"?renderAuditTableHtml(s.items,"audit"):"")+xe(s,"audit"),A("audit")}catch(t){J(e,t,!1)}}}function _t(){N("recentAudit"),document.getElementById("pageContent").innerHTML=`
    <div class="card list-page-card">
      <div class="list-filter-bar">
        <div class="list-filter-fields">
          <button type="button" class="btn-ghost btn-sm ${je.mine?"":"active-tab"}" onclick="setRecentScope(false)">全部操作</button>
          <button type="button" class="btn-ghost btn-sm ${je.mine?"active-tab":""}" onclick="setRecentScope(true)">我的操作</button>
        </div>
        <div class="list-filter-actions">
          ${ke("fetchRecentLogs()")}
          ${H("recentAudit")}
          <button type="button" class="btn-ghost btn-sm" onclick="navigate('audit')">完整操作日志</button>
        </div>
      </div>
      <div id="recentTable"></div>
    </div>`,Zs()}function Mo(e){je.mine=e,_t()}async function Zs(){const e=document.getElementById("recentTable");if(e){e.innerHTML='<p class="sub">加载中…</p>';try{const t=new URLSearchParams({size:je.size,mine:je.mine?"true":"false"}),s=await $("/api/v2/ops/admin/audit-logs/recent?"+t,"GET");e.innerHTML=typeof renderAuditTableHtml=="function"?renderAuditTableHtml(s,"recentAudit"):P("暂无操作记录","运营后台的敏感操作会记录在此","fetchRecentLogs()"),A("recentAudit")}catch(t){J(e,t,!1)}}}async function Xs(){const e=document.getElementById("pageContent"),t="disputes";N("disputes"),await Vs(),q===t&&(e.innerHTML=`
    <div class="card list-page-card">
      ${F({onSearch:"searchDisputes()",onReset:"resetDisputeFilters()",refreshFn:"fetchDisputes()",extraHtml:`${H("disputes")}<label class="filter-check"><input type="checkbox" title="全选" onchange="selToggleAll('disputes', this.checked)"> 全选</label>`,fieldsHtml:`
          ${R("状态",`<select id="dfStatus">
            <option value="OPEN" ${x.status==="OPEN"?"selected":""}>待审核</option>
            <option value="RESOLVED" ${x.status==="RESOLVED"?"selected":""}>已结案</option>
            <option value="" ${x.status?"":"selected"}>全部</option>
          </select>`)}
          ${R("开门记录",`<input id="dfSession" value="${r(x.sessionId)}" placeholder="可选">`)}
          ${R("设备编号",`<input id="dfDevice" value="${r(x.deviceId)}" placeholder="CAB-001">`)}`})}
      <div id="disputeList"></div>
    </div>`,Q(document.getElementById("disputeList"),1,4),De())}function No(){x.status="OPEN",x.sessionId="",x.deviceId="",x.page=0,Xs()}function Oo(){x.status=document.getElementById("dfStatus").value,x.sessionId=document.getElementById("dfSession").value.trim(),x.deviceId=document.getElementById("dfDevice").value.trim(),x.page=0,De()}async function De(){const e=document.getElementById("disputeList");if(e){Q(e,1,4);try{await Vs();const t=new URLSearchParams({page:x.page,size:x.size,status:x.status||"ALL",...x.sessionId?{sessionId:x.sessionId}:{},...x.deviceId?{deviceId:x.deviceId}:{}}),s=await $("/api/v2/ops/disputes?"+t,"GET");if(!s.items.length){e.innerHTML=P("暂无争议工单","识别异常或用户申诉的工单会出现在此","fetchDisputes()");return}e.innerHTML=j("disputes",s.items.map(jo).join(""))+xe(s,"dispute"),A("disputes"),ce()}catch(t){J(e,t,!1)}}}const ea={};function xo(e){if(!e)return"-";const t=(Date.now()-new Date(e).getTime())/36e5;return t<1?"刚刚提交":t<24?`${Math.floor(t)} 小时前`:`${Math.floor(t/24)} 天前`}function Uo(e){return ve.length?ve.map(t=>`<option value="${r(t.skuId)}" ${t.skuId===e?"selected":""}>${o(t.skuName)} (${o(t.skuId)}) ${C(t.priceCents)}</option>`).join(""):'<option value="">暂无商品，请先在商品管理添加</option>'}function qt(e,t){var a;const s=e||((a=ve[0])==null?void 0:a.skuId)||"";return`<div class="dispute-line filters" style="margin-top:8px">
    <div class="dispute-sku-field"><label>商品</label><select class="sku-select">${Uo(s)}</select></div>
    <div class="dispute-qty-field"><label>数量</label><input type="number" class="qty-input" value="${t||1}" min="1"></div>
    <div class="dispute-action-field"><button type="button" class="btn-ghost btn-sm" onclick="removeDisputeLine(this)">移除</button></div>
  </div>`}function Fo(e){var a;const t=document.querySelector(`.ticket[data-ticket="${e}"]`);if(!t)return;t.querySelector(".dispute-lines").insertAdjacentHTML("beforeend",qt((a=ve[0])==null?void 0:a.skuId,1))}function Ho(e){const t=ea[e]||[],s=document.querySelector(`.ticket[data-ticket="${e}"]`);if(!s||!t.length){d("无识别建议可采纳","err");return}const a=s.querySelector(".dispute-lines");a.innerHTML=t.map(n=>qt(n.skuId,n.quantity)).join("")}function _o(e){if(e.closest(".ticket").querySelector(".dispute-lines").querySelectorAll(".dispute-line").length<=1){d("至少保留一行商品","err");return}e.closest(".dispute-line").remove()}function qo(e){return!e||!e.length?'<div class="meta">识别建议：无</div>':`<div class="meta">识别建议：${e.map(s=>{const a=s.batchNo?` @${o(s.batchNo)}`:"";return`${o(s.skuName||s.skuId)} × ${o(s.quantity)}${a}`}).join("；")}</div>`}function jo(e){var m,k,w;ea[e.ticketId]=e.suggestedItems||[];const t=e.status==="OPEN",s=xo(e.createdAt),a=t&&(e.slaOverdue||Date.now()-new Date(e.createdAt).getTime()>48*36e5),n=t&&e.slaDueAt?`<div class="meta">处理截止 ${Y(e.slaDueAt)}${e.slaHoursRemaining!=null?` · 剩余 ${e.slaHoursRemaining} 小时`:""}</div>`:"",i=e.sessionId&&(e.videoUri||e.videoPreviewUrl)?`<button type="button" class="btn-ghost btn-sm" onclick="showSessionVideo('${r(e.sessionId)}', '${r(e.videoUri||"")}')">${At(e.videoUri)}</button>`:e.sessionId?'<span class="meta">无视频</span>':"",c=!t&&e.resolutionItems&&e.resolutionItems.length?`<div class="meta">结案商品：${e.resolutionItems.map(T=>`${o(T.skuId)} × ${o(T.quantity)}`).join("；")}</div>`:"",l=e.suggestedItems&&((m=e.suggestedItems[0])==null?void 0:m.skuId)||((k=ve[0])==null?void 0:k.skuId),p=e.billedAmountCents!=null?`<div class="meta">已扣款 ¥${(e.billedAmountCents/100).toFixed(2)}${e.orderId?` · 订单 ${o(e.orderId)}`:""}</div>`:e.sessionState==="DISPUTED"?'<div class="meta">待扣款（识别待审核）</div>':"",u=t?`
    <div class="dispute-lines">${qt(l,e.suggestedItems&&((w=e.suggestedItems[0])==null?void 0:w.quantity)||1)}</div>
    <div class="filters" style="margin-top:8px">
      <button type="button" class="btn-ghost btn-sm" onclick="addDisputeLine('${r(e.ticketId)}')">添加商品</button>
      <button type="button" class="btn-ghost btn-sm" onclick="applyDisputeSuggestion('${r(e.ticketId)}')">采用识别建议</button>
      <button type="button" class="btn-ok btn-sm" onclick="resolveTicket('${r(e.ticketId)}', this, 'CONFIRM')">确认扣款</button>
      <button type="button" class="btn-danger btn-sm" onclick="resolveTicket('${r(e.ticketId)}', this, 'WAIVE')">免单退款</button>
    </div>`:"";return`${ln("disputes",e.ticketId,"ticket")}
    <div class="ticket-check" onclick="event.stopPropagation()">${ps("disputes",e.ticketId)}</div>
    <div>${Ca(e.status)}${a?' <span class="badge badge-fail">超时待审</span>':""}</div>
    <div class="meta">工单 ${o(e.ticketId)} · 设备 ${o(e.deviceId||"-")} · 会话 ${o(e.sessionId)}</div>
    <div class="meta">原因 ${o(e.reason||"-")} · 等待 ${o(s)} · 创建 ${Y(e.createdAt)}${e.resolvedAt?` · 结案 ${Y(e.resolvedAt)}`:""}</div>
    ${n}
    ${p}
    ${qo(e.suggestedItems)}
    ${c}
    <div class="filters" style="margin-top:8px">${i}</div>
    ${u}
  </div>`}function Go(e,t){return`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal modal-wide" onclick="event.stopPropagation()">
        <h3>${o(e||"购物录像")}</h3>
        ${t?`<p class="meta">${o(t)}</p>`:""}
        <p id="videoLoadHint" class="meta">正在加载…</p>
        <div id="sessionMediaHost" class="session-media-host">
          <video id="sessionVideoPlayer" controls autoplay muted playsinline preload="auto" class="session-media-video hidden"></video>
        </div>
        <p class="meta">若无法播放，请确认该开门记录已上传购物录像，或稍后重试。</p>
        <div class="modal-actions"><button type="button" class="btn-ghost" onclick="closeModal()">关闭</button></div>
      </div>
    </div>`}async function ta(e,t){if(!(localStorage.getItem("admin_token")||ee)){d("请先登录","err");return}Bt();const a=document.getElementById("modalRoot"),n=t||"",i=Pt(n)==="image",c=i?"购物截图":"购物视频",l=i?"该会话上传的是静态截图（非视频），可用于辅助审核。":"";a.innerHTML=Go(c,l),a.classList.remove("hidden");const p=a.querySelector("#videoLoadHint"),u=a.querySelector("#sessionMediaHost"),m=a.querySelector("#sessionVideoPlayer");try{const k=await Cn(e,n),w=URL.createObjectURL(k.blob);sn(w),p.classList.add("hidden"),k.kind==="image"?(m.classList.add("hidden"),u.innerHTML=`<img src="${r(w)}" alt="购物截图" class="session-media-image">`):(m.classList.remove("hidden"),m.src=w,m.load(),m.play().catch(()=>{}),m.addEventListener("error",()=>{p.className="err video-err",p.textContent="视频解码失败：文件可能已损坏或格式不受支持。",p.classList.remove("hidden")},{once:!0}))}catch(k){p.className="err video-err",p.textContent=he(k)||"加载失败：该记录可能没有录像，或录像尚未上传完成。"}}function Vo(e,t){return ta(e,t)}async function Wo(e,t,s="CONFIRM"){const a=t.closest(".ticket"),n=(s||"CONFIRM").toUpperCase();if(n==="WAIVE"){if(!await ie("确认免单？将退还该会话已扣款项（如有）。",{title:"免单结案",danger:!0}))return;await _({target:t},async()=>{try{const l=await $(`/api/v2/ops/disputes/${e}/resolve`,"POST",{items:[],resolutionType:"WAIVE"});d(l.message||"已免单","ok"),De()}catch(l){throw b(l)||d("失败: "+l.message,"err"),l}},"结案中…");return}const i=[];if(a.querySelectorAll(".dispute-line").forEach(l=>{const p=l.querySelector(".sku-select").value,u=parseInt(l.querySelector(".qty-input").value,10)||0;p&&u>0&&i.push({skuId:p,quantity:u})}),!i.length){d("请至少添加一件商品","err");return}const c=i.map(l=>`${l.skuId} × ${l.quantity}`).join("；");await ie(`确认按以下商品结算？
${c}`,{title:"确认扣款"})&&await _({target:t},async()=>{try{const l=await $(`/api/v2/ops/disputes/${e}/resolve`,"POST",{items:i,resolutionType:n});d(l.message||"已结案","ok"),De()}catch(l){throw b(l)||d("失败: "+l.message,"err"),l}},"结案中…")}function sa(){N("recharges"),document.getElementById("pageContent").innerHTML=`
    <div class="card list-page-card">
      ${F({onSearch:"searchRecharges()",onReset:"resetRechargeFilters()",refreshFn:"fetchRecharges()",extraHtml:H("recharges"),fieldsHtml:`
          ${R("状态",`<select id="rfStatus">
            <option value="">全部</option>
            ${["PENDING","PAID","REFUNDED","CANCELLED"].map(e=>`<option value="${e}" ${Z.status===e?"selected":""}>${un(e)}</option>`).join("")}
          </select>`)}
          ${R("用户编号",`<input id="rfUserId" value="${r(Z.userId)}" placeholder="留空=全部">`)}`})}
      <div id="rechargeTable"></div>
    </div>`,Q(document.getElementById("rechargeTable"),10,6),dt()}function zo(){Z.status="",Z.userId="",Z.page=0,sa()}function Ko(){Z.status=document.getElementById("rfStatus").value,Z.userId=document.getElementById("rfUserId").value.trim(),Z.page=0,dt()}async function dt(){const e=document.getElementById("rechargeTable");if(e){Q(e,10,6);try{const t=new URLSearchParams({page:Z.page,size:Z.size,...Z.status?{status:Z.status}:{},...Z.userId?{userId:Z.userId}:{}}),s=await $("/api/v2/ops/admin/recharges?"+t,"GET");if(!s.items.length){e.innerHTML=P("暂无充值订单","用户小程序充值成功后会出现在此列表","fetchRecharges()");return}const a=S("ops:user:balance");e.innerHTML=j("recharges",`
      <table class="data-table">
        <thead><tr>
          ${V("recharges")}
          <th>订单号</th><th>用户</th><th>金额</th><th>渠道</th><th>状态</th>
          <th>微信单号</th><th>创建</th><th>支付</th><th>退款</th><th class="col-actions">操作</th>
        </tr></thead>
        <tbody>${s.items.map(n=>`
          ${z("recharges",n.orderId)}
          ${W("recharges",n.orderId)}
          <td><code>${o(n.orderId)}</code></td>
          <td>${o(n.userId)}</td>
          <td>${C(n.amountCents)}</td>
          <td>${o(ls(n.channel))}</td>
          <td>${$a(n.status)}</td>
          <td class="meta">${o(n.wxTransactionId||"-")}</td>
          <td>${Y(n.createdAt)}</td>
          <td>${Y(n.paidAt)}</td>
          <td>${Y(n.refundedAt)}</td>
          <td class="col-actions" onclick="event.stopPropagation()"><div class="row-actions">${n.status==="PAID"&&a?`<button class="btn-danger btn-sm" onclick="refundRecharge('${r(n.orderId)}', ${n.amountCents})">退款</button>`:'<span class="meta">-</span>'}</div></td>
        </tr>`).join("")}</tbody>
      </table>`)+xe(s,"recharge"),A("recharges")}catch(t){J(e,t,!1)}}}function aa(e,t){ge(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>确认退款</h3>
        <p>订单 <code>${o(e)}</code>，金额 <strong>${C(t)}</strong></p>
        <label>退款原因（可选）</label>
        <textarea id="refundReason" rows="3" placeholder="用户申请、重复支付等"></textarea>
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-danger" onclick="confirmRefundRecharge(event, '${r(e)}')">确认退款</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`)}async function Qo(e,t){await _(e,async()=>{var a;const s=((a=document.getElementById("refundReason"))==null?void 0:a.value.trim())||"";try{await $("/api/v2/ops/admin/recharge/"+encodeURIComponent(t)+"/refund","POST",s?{reason:s}:{}),de(),d("退款成功","ok"),dt()}catch(n){b(n)||d("退款失败: "+n.message,"err")}},"退款中…")}function Yo(e,t){aa(e,t)}ws();Dn();Es(ye);Pn();Object.assign(Ee,{api:$,getCurrentPage:()=>q,fmtTime:Y,fmtMoney:C,closeModal:de});let Ze=null;function jt(){Ze&&(clearInterval(Ze),Ze=null)}function na(){jt(),Ze=setInterval(()=>{q==="devices"?Ne():q==="dashboard"&&Us()},3e4)}window.addEventListener("popstate",()=>{if(!ee||document.getElementById("appView").classList.contains("hidden"))return;Ae=Math.max(0,Ae-1),Ve=Ae>0,Tt();const e=Bs();Me(e,{fromPopstate:!0})});Object.assign(window,{sendCode:Mn,login:Ss,switchLoginMode:Es,logout:St,navigate:Me,navigateBack:Fn,closeVisitedTab:Un,handleRefreshClick:tn,loadDashboard:te,refreshDashboardDevicePanel:Us,showDeviceForm:Qn,saveDevice:oo,viewDeviceDetail:Oe,applyPlanogramTemplate:Zn,loadFinancePage:Fs,showSlotDiscrepancies:Xn,showSlotEditor:eo,saveSlotConfig:to,deleteSlotConfig:so,promptSlotStocktake:no,promptSlotStocktakeFor:ao,searchSessions:qs,exportSessionsCsv:ro,cancelSession:uo,openDisputeForSession:lo,searchOrders:Gs,exportOrdersCsv:mo,showOrderDetail:bo,changePage:Ut,changePageSize:ho,jumpToPage:vo,toggleSidebar:Ps,toggleNavSection:Hn,toggleTheme:ba,toggleTableSort:qn,setTableSort:Ns,debouncedSearchSessions:yn,debouncedSearchOrders:$n,debouncedSearchUsers:In,resetUserFilters:To,resetSessionFilters:io,resetOrderFilters:po,resetRechargeFilters:zo,resetDisputeFilters:No,searchUsers:Js,searchDevices:zn,resetDeviceFilters:Kn,searchSkus:fo,resetSkuFilters:go,closeModal:de,loadSkusPage:rt,showSkuForm:Qs,showSkuFormById:zs,editSelectedSku:$o,previewSkuImage:wo,saveSku:Eo,selToggle:us,selToggleAll:on,selRowClick:cn,selClear:N,selSync:A,showBalanceForm:Lo,saveBalance:Co,setUserVerified:Ft,showVerifyUserForm:Ro,saveVerifyUser:Bo,showRbacAssignForUser:Po,fetchAuditLogs:Ht,fetchRecentLogs:Zs,setRecentScope:Mo,loadRecentPage:_t,resolveTicket:Wo,addDisputeLine:Fo,applyDisputeSuggestion:Ho,removeDisputeLine:_o,searchDisputes:Oo,fetchDisputes:De,showSessionVideo:ta,showDisputeVideo:Vo,searchRecharges:Ko,refundRecharge:Yo,showRefundRechargeForm:aa,confirmRefundRecharge:Qo});const v=(...e)=>Ee.api(...e),ne=e=>Ee.getCurrentPage()===e,le=e=>Ee.fmtTime(e),pe=e=>Ee.fmtMoney(e),be=(...e)=>Ee.closeModal(...e);function ue(e,t){const s=document.getElementById("modalRoot");s.innerHTML=e,s.classList.remove("hidden"),Nt(t||(()=>be()))}function Se(e,t){J(e,t,!0)}async function oa(){const e=document.getElementById("pageContent"),t="sla";try{const s=await v("/api/v2/ops/admin/sla","GET");if(!ne(t))return;const a=s.realtime||{};e.innerHTML=`
      <div class="card list-page-card">
        ${F({refreshFn:"loadSlaPage()",fieldsHtml:""})}
      </div>
      <div class="cards">
        <div class="card"><div class="card-label">24h 开门成功率</div><div class="card-value">${Ke(a.doorSuccessRate24h)}</div></div>
        <div class="card"><div class="card-label">24h 平均识别耗时</div><div class="card-value">${((a.avgRecognizeMs24h||0)/1e3).toFixed(1)} 秒</div></div>
        <div class="card"><div class="card-label">当前设备在线率</div><div class="card-value">${Ke(a.deviceOnlineRateNow)}</div></div>
        <div class="card"><div class="card-label">待审争议</div><div class="card-value">${o(a.disputeOpen??0)}</div></div>
        <div class="card"><div class="card-label">超时未处理争议</div><div class="card-value ${a.disputeOverdue>0?"warn":""}">${o(a.disputeOverdue??0)}</div></div>
        <div class="card"><div class="card-label">24h 争议结案</div><div class="card-value">${o(a.disputeResolved24h??0)}</div></div>
        <div class="card"><div class="card-label">24h 争议及时处理率</div><div class="card-value">${Ke(a.disputeSlaCompliance24h??1)}</div></div>
      </div>
      <h3>日快照 ${o(s.snapshotDate||"-")}</h3>
      <table class="data-table"><thead><tr>
        <th>开门尝试</th><th>成功</th><th>成功率</th><th>识别均耗(秒)</th><th>P95(秒)</th><th>设备数</th><th>在线峰值</th>
      </tr></thead><tbody><tr>
        <td>${o(s.doorOpenAttempts??0)}</td><td>${o(s.doorOpenSuccess??0)}</td><td>${Ke(s.doorSuccessRate)}</td>
        <td>${((s.avgRecognizeMs??0)/1e3).toFixed(1)}</td><td>${((s.p95RecognizeMs??0)/1e3).toFixed(1)}</td>
        <td>${o(s.deviceTotal??0)}</td><td>${o(s.deviceOnlinePeak??0)}</td>
      </tr></tbody></table>`}catch(s){if(!ne(t))return;Se(e,s)}}async function Gt(){const e=document.getElementById("pageContent"),t="ota";N("ota");try{const s=await v("/api/v2/ops/admin/ota/releases","GET");if(!ne(t))return;const a=(s||[]).map(n=>`
      ${z("ota",n.releaseId)}
      ${W("ota",n.releaseId)}
      <td>${o(n.appVersion)}</td><td>${o(qa(n.channel))}</td><td>${n.mandatory?"是":"否"}</td>
      <td>${o(n.grayPercent??100)}%</td><td>${o(_a(n.status))}</td><td>${le(n.publishedAt)}</td>
      <td class="col-actions" onclick="event.stopPropagation()"><div class="row-actions">${n.downloadUrl?`<a class="btn-ghost btn-sm" href="${r(n.downloadUrl)}" target="_blank" rel="noopener">下载</a>`:`<span class="meta">${o(n.objectStorageUri||"-")}</span>`}</div></td>
    </tr>`).join("");e.innerHTML=`
      <div class="card list-page-card">
        ${F({refreshFn:"loadOtaPage()",extraHtml:`${ae("ota.publish","发布新版本","showOtaPublishForm()","btn-primary btn-sm")}${H("ota")}`,fieldsHtml:""})}
      </div>
      <div id="otaPublishForm" class="hidden card" style="margin:12px 0;padding:12px">
        <label>版本号</label><input id="otaVersion" placeholder="1.2.0">
        <label>渠道</label><input id="otaChannel" value="STABLE">
        <label>灰度比例 (0-100)</label><input id="otaGray" type="number" value="100" min="0" max="100">
        <label>固件包地址</label><input id="otaUri" placeholder="填写固件下载地址">
        <label>下载 URL（可选，无 URI 时填写）</label><input id="otaUrl" placeholder="https://...">
        <label><input type="checkbox" id="otaMandatory"> 强制升级</label>
        <button type="button" class="btn-primary btn-sm" onclick="publishOta(event)">提交发布</button>
      </div>
      ${s&&s.length?j("ota",`<table class="data-table"><thead><tr>
        ${V("ota")}
        <th>版本</th><th>渠道</th><th>强制</th><th>灰度</th><th>状态</th><th>发布时间</th><th class="col-actions">包</th>
      </tr></thead><tbody>${a}</tbody></table>`):P("暂无 OTA 发布","发布柜机 APK 后设备可检查更新","loadOtaPage()")}
      <p class="sub">柜机检查更新：GET /internal/v1/devices/{id}/ota/check?currentVersion=…</p>`,A("ota"),ce()}catch(s){if(!ne(t))return;Se(e,s)}}function Jo(){document.getElementById("otaPublishForm").classList.toggle("hidden")}async function Zo(e){await _(e,async()=>{const t={appVersion:document.getElementById("otaVersion").value.trim(),channel:document.getElementById("otaChannel").value.trim()||"STABLE",mandatory:document.getElementById("otaMandatory").checked,grayPercent:parseInt(document.getElementById("otaGray").value,10)||100,objectStorageUri:document.getElementById("otaUri").value.trim()||null,downloadUrl:document.getElementById("otaUrl").value.trim()||null,status:"PUBLISHED"};if(!t.appVersion){d("请填写版本号","err");return}try{await v("/api/v2/ops/admin/ota/releases","POST",t),d("已发布","ok"),Gt()}catch(s){b(s)||d("发布失败: "+s.message,"err")}},"发布中…")}async function ut(){const e=document.getElementById("pageContent"),t="risk";N("riskEvents"),N("blacklist");try{const[s,a]=await Promise.all([v("/api/v2/ops/admin/risk/events?page=0&size=20","GET"),v("/api/v2/ops/admin/risk/blacklist","GET")]);if(!ne(t))return;const n=(s.items||[]).map(c=>`
      ${z("riskEvents",c.eventId)}
      ${W("riskEvents",c.eventId)}
      <td>${le(c.createdAt)}</td><td>${o(Va(c.eventType))}</td><td>${o(Ga(c.severity))}</td>
      <td>${o(c.userId||"-")}</td><td>${o(c.deviceId||"-")}</td><td>${o(c.detail||"")}</td>
    </tr>`).join(""),i=(a||[]).map(c=>`
      ${z("blacklist",c.userId)}
      ${W("blacklist",c.userId)}
      <td>${o(c.userId)}</td><td>${o(c.reason)}</td><td>${o(c.source)}</td><td>${le(c.expiresAt)}</td>
      <td class="col-actions" onclick="event.stopPropagation()"><div class="row-actions">${S("ops:risk:blacklist")?`<button type="button" class="btn-ghost btn-sm btn-danger" onclick="removeBlacklist(${c.userId})">解除</button>`:'<span class="meta">-</span>'}</div></td>
    </tr>`).join("");e.innerHTML=`
      <div class="card list-page-card">
        ${F({refreshFn:"loadRiskPage()",extraHtml:`${ae("risk.blacklist","添加黑名单","showBlacklistForm()","btn-primary btn-sm")}${H("riskEvents")}`,fieldsHtml:""})}
      </div>
      <h3>风控事件</h3>
      ${(s.items||[]).length?j("riskEvents",`<table class="data-table"><thead><tr>
          ${V("riskEvents")}
          <th>时间</th><th>类型</th><th>级别</th><th>用户</th><th>设备</th><th>详情</th>
        </tr></thead><tbody>${n}</tbody></table>`):P("暂无风控事件","触发风控规则后会在此展示","loadRiskPage()")}
      <h3>黑名单 ${H("blacklist")}</h3>
      ${(a||[]).length?j("blacklist",`<table class="data-table"><thead><tr>
          ${V("blacklist")}
          <th>用户</th><th>原因</th><th>来源</th><th>过期</th><th class="col-actions">操作</th>
        </tr></thead><tbody>${i}</tbody></table>`):P("暂无黑名单用户","手动拉黑或自动风控命中后会出现在此","loadRiskPage()")}`,A("riskEvents"),A("blacklist"),ce()}catch(s){if(!ne(t))return;Se(e,s)}}function Xo(){ue(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>添加黑名单</h3>
        <label>用户 ID</label>
        <input id="blUserId" type="number" min="1" placeholder="10001">
        <label>原因</label>
        <input id="blReason" placeholder="恶意申诉 / 频繁异常">
        <label>过期时间（可选，ISO 格式留空=永久）</label>
        <input id="blExpires" placeholder="2026-12-31T23:59:59Z">
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveBlacklist(event)">确认拉黑</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`)}async function ei(e){await _(e,async()=>{const t=parseInt(document.getElementById("blUserId").value,10),s=document.getElementById("blReason").value.trim(),a=document.getElementById("blExpires").value.trim();if(!t||!s){d("请填写用户 ID 和原因","err");return}try{const n={userId:t,reason:s};a&&(n.expiresAt=a),await v("/api/v2/ops/admin/risk/blacklist","POST",n),be(),d("已加入黑名单","ok"),ut()}catch(n){b(n)||d("操作失败: "+n.message,"err")}},"提交中…")}const ti=nt(()=>da(),350);async function si(e){if(await ie(`确认解除用户 ${e} 的黑名单？`,{title:"解除黑名单",danger:!0}))try{await v("/api/v2/ops/admin/risk/blacklist/"+e,"DELETE"),d("已解除","ok"),ut()}catch(t){b(t)||d("操作失败: "+t.message,"err")}}async function Vt(){const e=document.getElementById("pageContent"),t=new Date().toISOString().slice(0,10),s=new Date(Date.now()-30*864e5).toISOString().slice(0,10);K.from||(K.from=s),K.to||(K.to=t),N("reconciliation"),e.innerHTML=`
    <div class="card list-page-card">
      ${F({onSearch:"fetchReconciliationList()",onReset:"resetReconciliationFilters()",refreshFn:"fetchReconciliationList()",extraHtml:`${H("reconciliation")}${ae("recon.run","执行对账","runReconToday(event)","btn-primary btn-sm")}`,fieldsHtml:`
          ${R("开始日期",`<input id="reconFrom" type="date" value="${r(K.from)}">`)}
          ${R("结束日期",`<input id="reconTo" type="date" value="${r(K.to)}">`)}
          ${R("渠道",`<select id="reconChannel">
            <option value="WECHAT" ${K.channel==="WECHAT"?"selected":""}>微信</option>
            <option value="ALIPAY" ${K.channel==="ALIPAY"?"selected":""}>支付宝</option>
            <option value="MOCK" ${K.channel==="MOCK"?"selected":""}>Mock</option>
          </select>`)}`})}
      <div id="reconTable"></div>
    </div>`,ce(),Q(document.getElementById("reconTable"),8,6),Wt()}function ai(){var e,t,s;K.from=((e=document.getElementById("reconFrom"))==null?void 0:e.value)||"",K.to=((t=document.getElementById("reconTo"))==null?void 0:t.value)||"",K.channel=((s=document.getElementById("reconChannel"))==null?void 0:s.value)||"WECHAT"}function ni(){const e=new Date().toISOString().slice(0,10),t=new Date(Date.now()-30*864e5).toISOString().slice(0,10);K.from=t,K.to=e,K.channel="WECHAT",Vt()}async function Wt(){const e=document.getElementById("reconTable");if(e){ai(),Q(e,8,6);try{const t=new URLSearchParams;K.from&&t.set("from",K.from),K.to&&t.set("to",K.to);const s=await v("/api/v2/ops/admin/reconciliation?"+t,"GET");if(!s||!s.length){e.innerHTML=P("暂无对账记录","选择日期范围后查询，或执行对账任务","fetchReconciliationList()");return}const a=(s||[]).map(n=>`
      ${z("reconciliation",n.reconId)}
      ${W("reconciliation",n.reconId)}
      <td>${o(n.reconDate)}</td><td>${o(ls(n.channel))}</td>
      <td>${pe(n.platformTotal)}</td><td>${pe(n.ledgerTotal)}</td>
      <td>${pe(n.diffCents)}</td>
      <td>${o(n.matchedCount??0)}/${o(n.unmatchedCount??0)}</td>
      <td>${o(rs(n.status))}</td><td>${le(n.completedAt)}</td>
      <td class="col-actions" onclick="event.stopPropagation()"><div class="row-actions"><button type="button" class="btn-ghost btn-sm" onclick="showReconDetail(${o(n.reconId)})">明细</button></div></td>
    </tr>`).join("");e.innerHTML=j("reconciliation",`
      <table class="data-table"><thead><tr>
        ${V("reconciliation")}
        <th>日期</th><th>渠道</th><th>平台总额</th><th>账本总额</th><th>差额</th>
        <th>匹配/未匹配</th><th>状态</th><th>完成时间</th><th class="col-actions">操作</th>
      </tr></thead><tbody>${a||'<tr><td colspan="10">暂无记录</td></tr>'}</tbody></table>`),A("reconciliation")}catch(t){Se(e,t)}}}async function oi(e){await _(e,async()=>{var a;const t=new Date().toISOString().slice(0,10),s=((a=document.getElementById("reconChannel"))==null?void 0:a.value)||"WECHAT";try{await v(`/api/v2/ops/admin/reconciliation/run?date=${t}&channel=${s}`,"POST"),d("对账任务已提交","ok"),Wt()}catch(n){b(n)||d("对账失败: "+n.message,"err")}},"执行中…")}async function ii(e){try{const t=await v("/api/v2/ops/admin/reconciliation/"+e,"GET"),s=t.summary,a=t.lines||[],n=a.filter(c=>!c.matched),i=a.slice(0,100).map(c=>`<tr class="${c.matched?"":"err"}">
      <td>${o(c.platformTradeNo)}</td><td>${o(c.merchantOrderNo||"-")}</td>
      <td>${pe(c.amountCents)}</td><td>${o(c.tradeType||"-")}</td>
      <td>${c.matched?"✓":"✗"}</td><td>${le(c.tradeTime)}</td>
    </tr>`).join("");ue(`
      <div class="modal-backdrop" onclick="closeModal(event)">
        <div class="modal modal-wide" onclick="event.stopPropagation()">
          <h3>对账明细 #${o(e)} · ${o(s.reconDate)} · ${o(s.channel)}</h3>
          <p class="meta">平台 ${pe(s.platformTotal)} / 账本 ${pe(s.ledgerTotal)} / 差额 ${pe(s.diffCents)} · ${o(rs(s.status))}</p>
          ${n.length?`<p class="err">未匹配 ${o(n.length)} 笔</p>`:""}
          <table class="table"><thead><tr>
            <th>平台流水</th><th>商户单号</th><th>金额</th><th>类型</th><th>匹配</th><th>时间</th>
          </tr></thead><tbody>${i||'<tr><td colspan="6">无明细</td></tr>'}</tbody></table>
          ${a.length>100?'<p class="sub">仅显示前 100 条</p>':""}
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">关闭</button>
        </div>
      </div>`)}catch(t){b(t)||d("加载失败: "+t.message,"err")}}async function fe(){const e=document.getElementById("pageContent"),t="replenishment";N("replenInventory");const s=mt.lowStockOnly;try{const a="/api/v2/ops/admin/inventory"+(s?"?lowStockOnly=true":""),[n,i,c,l]=await Promise.all([v("/api/v2/ops/admin/replenishment/routes","GET"),v(a,"GET"),v("/api/v2/ops/admin/skus","GET").catch(()=>[]),v("/api/v2/ops/admin/expiry/alerts","GET").catch(()=>[])]);if(!ne(t))return;const p=Object.fromEntries((c||[]).map(h=>[h.skuId,h])),u=h=>{const y=p[h];return y?`${o(y.skuName)} <code>${o(h)}</code>`:`<code>${o(h)}</code>`},m=(i||[]).filter(h=>h.quantity<=h.lowThreshold).length,k=(l||[]).map(h=>`<tr>
      <td>${o(h.deviceId)}</td><td>${u(h.skuId)}</td>
      <td><code>${o(h.batchNo||"-")}</code></td><td>${o(h.quantity)}</td>
      <td><span class="badge badge-active">${o(h.reason)}</span></td>
      <td>${le(h.createdAt)}</td>
    </tr>`).join(""),w=(n||[]).map(h=>{const y=(h.tasks||[]).map(E=>`<tr>
        <td>${o(E.deviceId)}</td><td>${o(Zt(E.status))}</td>
        <td>${E.completedAt?le(E.completedAt):"-"}</td>
        <td>${E.status!=="COMPLETED"&&S("ops:replenishment:edit")?`<button class="btn-ghost btn-sm" onclick="showReplenishmentLinesForm(${E.taskId},'${r(E.deviceId)}')">录入行</button>
             <button class="btn-ghost btn-sm" onclick="completeReplenishmentTask(${E.taskId})">完成</button>`:"-"}</td>
      </tr>`).join(""),g=y?`<table class="table sub-table"><thead><tr><th>设备</th><th>状态</th><th>完成时间</th><th>操作</th></tr></thead><tbody>${y}</tbody></table>`:'<span class="meta">无任务</span>';return`<tr><td colspan="5">
        <div><strong>${o(h.routeName)}</strong> · ${o(h.plannedDate)} · ${o(Zt(h.status))} · 负责人 ${o(h.assigneeUserId||"-")}</div>
        ${g}
      </td></tr>`}).join(""),T=[...new Set((i||[]).map(h=>h.deviceId))],O=(i||[]).map(h=>{const y=h.quantity<=h.lowThreshold,g=`${h.deviceId}:${h.skuId}`;return`
      ${z("replenInventory",g,y?"row-low-stock":"")}
      ${W("replenInventory",g)}
      <td>${o(h.deviceId)}</td><td>${u(h.skuId)}</td>
      <td>${o(h.quantity)}/${o(h.capacity)}${y?' <span class="badge badge-active">低库存</span>':""}</td>
      <td>${o(h.lowThreshold)}</td>
      <td class="col-actions" onclick="event.stopPropagation()"><div class="row-actions">
        ${S("ops:replenishment:edit")?`<button type="button" class="btn-ghost btn-sm" onclick='showInventoryForm(${JSON.stringify(h)})'>编辑</button>`:""}
        <button type="button" class="btn-ghost btn-sm" onclick="viewDeviceLots('${r(h.deviceId)}')">批次</button>
      </div></td>
    </tr>`}).join("");e.innerHTML=`
      <div class="card list-page-card">
        ${F({refreshFn:"loadReplenishmentPage()",extraHtml:`
            ${ae("replenish.plan","规划路线","showReplenishmentPlanForm()","btn-primary btn-sm")}
            ${ae("replenish.edit","录入库存","showInventoryForm()","btn-ghost btn-sm")}
            ${ae("replenish.edit","商品盘点","showSkuStocktakeForm()","btn-ghost btn-sm")}
            ${ae("replenish.edit","报损","showWriteOffForm()","btn-ghost btn-sm")}
            ${m>0&&S("ops:replenishment:edit")?`<button type="button" class="btn-ok btn-sm" onclick="planRouteFromLowStock()">从低库存生成路线 (${m})</button>`:""}
            <label class="filter-check"><input type="checkbox" id="replLowOnly" ${s?"checked":""} onchange="toggleReplenishmentLowStock()"> 仅低库存</label>
            ${H("replenInventory")}`,fieldsHtml:""})}
      </div>
      <p class="meta">${s?`当前显示 ${i.length} 条低库存记录`:`共 ${i.length} 条库存，其中 ${m} 条低库存`}</p>
      <h3>效期告警 / 待下架</h3>
      ${(l||[]).length?`<table class="data-table"><thead><tr><th>设备</th><th>商品</th><th>批次</th><th>数量</th><th>原因</th><th>创建时间</th></tr></thead><tbody>${k}</tbody></table>`:'<p class="meta">暂无待下架任务</p>'}
      <h3>补货路线</h3>
      ${(n||[]).length?`<table class="data-table"><tbody>${w}</tbody></table>`:P("暂无补货路线","点击「规划路线」创建补货任务","loadReplenishmentPage()")}
      <h3>柜内库存</h3>
      ${T.length?`<p class="meta">设备：${T.map(h=>`<button type="button" class="btn-ghost btn-sm" onclick="viewDeviceLots('${r(h)}')">${o(h)} 批次</button>`).join(" ")}</p>`:""}
      ${(i||[]).length?j("replenInventory",`<table class="data-table"><thead><tr>
          ${V("replenInventory")}
          <th>设备</th><th>商品</th><th>库存/容量</th><th>低库存阈值</th><th class="col-actions">操作</th>
        </tr></thead><tbody>${O}</tbody></table>`):P(s?"暂无低库存商品":"暂无库存数据",s?"所有商品库存充足":"点击「录入库存」添加柜内商品数量","loadReplenishmentPage()")}`,A("replenInventory"),ce()}catch(a){if(!ne(t))return;Se(e,a)}}function ci(){const e=document.getElementById("replLowOnly");mt.lowStockOnly=!!(e!=null&&e.checked),fe()}async function li(){if(S("ops:replenishment:edit"))try{const t=await v("/api/v2/ops/admin/inventory?lowStockOnly=true","GET")||[];if(!t.length){d("暂无低库存商品","err");return}const s=[...new Set(t.map(i=>i.deviceId))],a=new Date().toISOString().slice(0,10),n={};if(t.forEach(i=>{n[i.deviceId]=(n[i.deviceId]||[]).concat(`${i.skuId}×${i.quantity}`)}),!await ie(`将为 ${s.length} 台设备创建补货路线，涉及 ${t.length} 个低库存商品？`,{title:"创建补货路线"}))return;await v("/api/v2/ops/admin/replenishment/routes","POST",{routeName:`低库存补货-${a}`,plannedDate:a,assigneeUserId:parseInt(localStorage.getItem("admin_userId")||"100000001",10),tasks:s.map(i=>({deviceId:i,notes:"低库存: "+(n[i]||[]).join("; ")}))}),d("补货路线已创建","ok"),mt.lowStockOnly=!1,fe()}catch(e){b(e)||d("创建失败: "+e.message,"err")}}function ri(e){const t=!!e;ue(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>${t?"编辑库存":"录入库存"}</h3>
        <label>设备 ID</label>
        <input id="invDevice" value="${t?r(e.deviceId):"CAB-001"}" ${t?"disabled":""}>
        <label>商品编号</label>
        <input id="invSku" value="${t?r(e.skuId):"SKU-DEMO-001"}" ${t?"disabled":""}>
        <div class="filters">
          <div><label>当前数量</label><input id="invQty" type="number" min="0" value="${t?e.quantity:0}"></div>
          <div><label>容量</label><input id="invCap" type="number" min="1" value="${t?e.capacity:20}"></div>
        </div>
        <label>低库存阈值</label>
        <input id="invLow" type="number" min="0" value="${t?e.lowThreshold:3}">
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveInventory(event)">保存</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`)}async function di(e){await _(e,async()=>{const t=document.getElementById("invDevice").value.trim(),s=document.getElementById("invSku").value.trim(),a=parseInt(document.getElementById("invQty").value,10),n=parseInt(document.getElementById("invCap").value,10),i=parseInt(document.getElementById("invLow").value,10);if(!t||!s||Number.isNaN(a)||Number.isNaN(n)){d("请填写完整","err");return}try{await v("/api/v2/ops/admin/inventory","PUT",{deviceId:t,skuId:s,quantity:a,capacity:n,lowThreshold:i||0}),be(),d("库存已保存","ok"),fe()}catch(c){b(c)||d("保存失败: "+c.message,"err")}})}function ui(e){const t=!!e;ue(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>商品盘点调整</h3>
        <p class="meta">按商品汇总账面与实盘差异，并写入批次流水（优先扣减临期批次）。</p>
        <label>设备 ID</label>
        <input id="stkDevice" value="${t?r(e.deviceId):"CAB-001"}">
        <label>商品编号</label>
        <input id="stkSku" value="${t?r(e.skuId):"SKU-DEMO-001"}">
        <label>实盘数量</label>
        <input id="stkQty" type="number" min="0" value="${t?e.quantity:0}">
        <label>备注</label>
        <input id="stkNote" placeholder="可选">
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveSkuStocktake(event)">提交盘点</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`)}async function pi(e){await _(e,async()=>{const t=document.getElementById("stkDevice").value.trim(),s=document.getElementById("stkSku").value.trim(),a=parseInt(document.getElementById("stkQty").value,10),n=document.getElementById("stkNote").value.trim()||null;if(!t||!s||Number.isNaN(a)){d("请填写完整","err");return}try{await v("/api/v2/ops/admin/inventory/stocktake","POST",{deviceId:t,skuId:s,countedQuantity:a,note:n}),be(),d("盘点已提交","ok"),fe()}catch(i){b(i)||d("盘点失败: "+i.message,"err")}})}function mi(e){const t=e||{};ue(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>库存报损</h3>
        <label>设备 ID</label>
        <input id="woDevice" value="${r(t.deviceId||"CAB-001")}">
        <label>商品编号</label>
        <input id="woSku" value="${r(t.skuId||"SKU-DEMO-001")}">
        <label>批次号（可选，留空则按先到期先出规则）</label>
        <input id="woBatch" value="${r(t.batchNo||"")}">
        <label>数量</label>
        <input id="woQty" type="number" min="1" value="${t.quantity||1}">
        <label>原因</label>
        <select id="woReason">
          <option value="EXPIRED">过期 EXPIRED</option>
          <option value="DAMAGED">破损 DAMAGED</option>
          <option value="THEFT">盗损 THEFT</option>
          <option value="OTHER">其他 OTHER</option>
        </select>
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-warn" onclick="saveWriteOff(event)">确认报损</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`)}async function hi(e){await _(e,async()=>{const t=document.getElementById("woDevice").value.trim(),s=document.getElementById("woSku").value.trim(),a=document.getElementById("woBatch").value.trim()||null,n=parseInt(document.getElementById("woQty").value,10),i=document.getElementById("woReason").value;if(!t||!s||!n||n<1){d("请填写完整","err");return}if(await ie(`确认报损 ${s} × ${n}？`,{title:"报损确认"}))try{await v("/api/v2/ops/admin/inventory/write-off","POST",{deviceId:t,skuId:s,batchNo:a,quantity:n,reason:i}),be(),d("报损已记录","ok"),fe()}catch(c){b(c)||d("报损失败: "+c.message,"err")}})}async function vi(e){if(await ie(`确认完成任务 #${e}？将应用已录入的补货行并更新批次库存。`,{title:"完成任务"}))try{await v("/api/v2/ops/admin/replenishment/tasks/"+e+"/complete","POST"),d("任务已完成","ok"),fe()}catch(t){b(t)||d("操作失败: "+t.message,"err")}}async function bi(e,t){let s=[];try{s=await v("/api/v2/ops/admin/skus","GET")}catch(c){if(b(c))return}const a=(s||[]).map(c=>`<option value="${r(c.skuId)}">${o(c.skuName)} (${o(c.skuId)})</option>`).join(""),n=new Date().toISOString().slice(0,10),i=new Date(Date.now()+30*864e5).toISOString().slice(0,10);ue(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" style="max-width:720px" onclick="event.stopPropagation()">
        <h3>补货行项目 · 任务 #${e}</h3>
        <p class="meta">设备 <code>${o(t)}</code> · 完成前提交上架/下架明细，完成时将写入批次与库存流水</p>
        <div id="replLinesContainer"></div>
        <button type="button" class="btn-ghost btn-sm" onclick="addReplenishmentLineRow()">+ 添加一行</button>
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveReplenishmentLines(event, ${e})">保存行项目</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`),window._replLineSkuOptions=a,window._replLineDefaults={today:n,expiryDefault:i};try{const c=await v("/api/v2/ops/admin/replenishment/tasks/"+e+"/lines","GET");if(!document.getElementById("replLinesContainer"))return;c!=null&&c.length?c.forEach(p=>Xe(p)):Xe()}catch(c){Xe(),b(c)||d("加载已有行失败: "+c.message,"err")}}function Xe(e){const t=document.getElementById("replLinesContainer");if(!t)return;const s=window._replLineSkuOptions||"",a=window._replLineDefaults||{today:"",expiryDefault:""},n=(e==null?void 0:e.lineType)||"RESTOCK",i=(e==null?void 0:e.skuId)||"";t.children.length;const c=document.createElement("div");if(c.className="card",c.style.marginBottom="10px",c.innerHTML=`
    <div class="filters form-grid">
      <div><label>类型</label>
        <select class="repl-line-type">
          <option value="RESTOCK" ${n==="RESTOCK"?"selected":""}>上架 RESTOCK</option>
          <option value="PULL_OFF" ${n==="PULL_OFF"?"selected":""}>下架 PULL_OFF</option>
        </select>
      </div>
      <div><label>SKU</label>
        <select class="repl-line-sku"><option value="">选择商品</option>${s}</select>
      </div>
      <div><label>数量</label><input class="repl-line-qty" type="number" min="1" value="${(e==null?void 0:e.quantity)||1}"></div>
      <div><label>批次号</label><input class="repl-line-batch" value="${r((e==null?void 0:e.batchNo)||"")}" placeholder="B20260701-001"></div>
      <div><label>生产日期</label><input class="repl-line-prod" type="date" value="${(e==null?void 0:e.productionDate)||a.today}"></div>
      <div><label>到期日</label><input class="repl-line-exp" type="date" value="${(e==null?void 0:e.expiryDate)||a.expiryDefault}"></div>
      <div><label>货道</label><input class="repl-line-slot" value="${r((e==null?void 0:e.slotId)||"")}" placeholder="A1"></div>
    </div>
    <button type="button" class="btn-ghost btn-sm" onclick="this.closest('.card').remove()">删除此行</button>`,t.appendChild(c),i){const l=c.querySelector(".repl-line-sku");l&&(l.value=i)}}async function fi(e,t){e&&e.preventDefault();const s=document.getElementById("replLinesContainer");if(!s)return;const a=[];if(s.querySelectorAll(".card").forEach(n=>{var w,T,O,h,y,g,E,f,L,I;const i=((w=n.querySelector(".repl-line-type"))==null?void 0:w.value)||"RESTOCK",c=(O=(T=n.querySelector(".repl-line-sku"))==null?void 0:T.value)==null?void 0:O.trim(),l=parseInt((h=n.querySelector(".repl-line-qty"))==null?void 0:h.value,10),p=((g=(y=n.querySelector(".repl-line-batch"))==null?void 0:y.value)==null?void 0:g.trim())||null,u=((E=n.querySelector(".repl-line-prod"))==null?void 0:E.value)||null,m=((f=n.querySelector(".repl-line-exp"))==null?void 0:f.value)||null,k=((I=(L=n.querySelector(".repl-line-slot"))==null?void 0:L.value)==null?void 0:I.trim())||null;!c||!l||a.push({lineType:i,skuId:c,quantity:l,batchNo:p,productionDate:u,expiryDate:m,slotId:k})}),!a.length){d("请至少填写一行有效明细","err");return}try{await v("/api/v2/ops/admin/replenishment/tasks/"+t+"/lines","POST",{lines:a}),be(),d("补货行已保存","ok"),fe()}catch(n){b(n)||d("保存失败: "+n.message,"err")}}async function gi(e){try{const t=await v("/api/v2/ops/admin/devices/"+encodeURIComponent(e)+"/lots","GET"),s=(t||[]).map(a=>`<tr>
      <td><code>${o(a.batchNo)}</code></td><td>${$i(a.skuId)}</td>
      <td>${o(a.quantity)}</td><td>${o(a.expiryDate||"-")}</td>
      <td>${o(yi(a.status))}</td><td>${o(a.slotId||"-")}</td>
      <td>${S("ops:replenishment:edit")&&a.quantity>0?`<button class="btn-ghost btn-sm" onclick='showWriteOffForm(${JSON.stringify({deviceId:e,skuId:a.skuId,batchNo:a.batchNo,quantity:a.quantity})})'>报损</button>`:"-"}</td>
    </tr>`).join("");ue(`
      <div class="modal-backdrop" onclick="closeModal(event)">
        <div class="modal" style="max-width:800px" onclick="event.stopPropagation()">
          <h3>设备批次 · ${o(e)}</h3>
          ${(t||[]).length?`<table class="table"><thead><tr><th>批次</th><th>商品</th><th>数量</th><th>到期</th><th>状态</th><th>货道</th><th>操作</th></tr></thead><tbody>${s}</tbody></table>`:'<p class="meta">暂无批次记录（可通过补货行 RESTOCK 入库）</p>'}
          <div class="filters" style="margin-top:12px">
            <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">关闭</button>
          </div>
        </div>
      </div>`)}catch(t){b(t)||d("加载批次失败: "+t.message,"err")}}function yi(e){return{ON_SALE:"在售",NEAR_EXPIRY:"临期",BLOCKED:"禁售",DEPLETED:"售罄"}[e]||e||"-"}function $i(e){return`<code>${o(e)}</code>`}async function Ii(){const e=new Date().toISOString().slice(0,10);ue(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" style="max-width:560px" onclick="event.stopPropagation()">
        <h3>规划补货路线</h3>
        <label>路线名称</label>
        <input id="rpName" value="补货路线-${e}">
        <label>选择设备</label>
        <div id="rpDeviceList"><p class="meta">加载设备中…</p></div>
        <div class="filters">
          <div><label>负责人 userId</label>
            <input id="rpAssignee" type="number" value="${r(localStorage.getItem("admin_userId")||"100000001")}"></div>
          <div><label>计划日期</label><input id="rpDate" type="date" value="${e}"></div>
        </div>
        <div class="filters">
          <div><label>起点纬度</label><input id="rpLat" type="number" step="0.0001" value="31.23"></div>
          <div><label>起点经度</label><input id="rpLng" type="number" step="0.0001" value="121.47"></div>
        </div>
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveReplenishmentPlan(event)">创建路线</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`);try{const t=await v("/api/v2/ops/admin/devices","GET"),s=document.getElementById("rpDeviceList");if(!s)return;if(!(t!=null&&t.length)){s.innerHTML='<p class="meta">暂无设备，请先在设备管理注册</p>';return}const a=t.map(n=>`
      <label class="device-check">
        <input type="checkbox" class="rp-device-cb" value="${r(n.deviceId)}">
        <span class="device-check-main">${o(n.deviceId)} · ${o(n.deviceName||"-")}</span>
        <span class="meta">${o(n.merchantName||"未绑定商户")} · ${o(is(n.onlineStatus))}</span>
      </label>`).join("");s.innerHTML=`
      <div class="device-check-toolbar">
        <button type="button" class="btn-ghost btn-sm" onclick="toggleAllReplenishmentDevices(true)">全选</button>
        <button type="button" class="btn-ghost btn-sm" onclick="toggleAllReplenishmentDevices(false)">清空</button>
      </div>
      <div class="device-check-list">${a}</div>`}catch(t){const s=document.getElementById("rpDeviceList");s&&!b(t)&&(s.innerHTML=`<p class="meta">加载设备失败：${o(t.message)}</p>`)}}function ia(){return[...document.querySelectorAll(".rp-device-cb:checked")].map(e=>e.value)}function ki(e){document.querySelectorAll(".rp-device-cb").forEach(t=>{t.checked=e})}async function wi(e){await _(e,async()=>{const t=document.getElementById("rpName").value.trim(),s=ia(),a=parseInt(document.getElementById("rpAssignee").value,10),n=document.getElementById("rpDate").value,i=parseFloat(document.getElementById("rpLat").value),c=parseFloat(document.getElementById("rpLng").value);if(!t||!s.length||!n||Number.isNaN(a)){d("请填写路线名称、设备和负责人","err");return}try{await v("/api/v2/ops/admin/replenishment/plan","POST",{routeName:t,assigneeUserId:a,plannedDate:n,deviceIds:s,startLatitude:i,startLongitude:c}),be(),d("路线已规划","ok"),fe()}catch(l){b(l)||d("规划失败: "+l.message,"err")}},"创建中…")}async function ca(){var s,a,n,i,c,l;const e=document.getElementById("pageContent"),t="rbac";N("rbacRoles"),N("rbacOperators");try{const[p,u,m]=await Promise.all([v("/api/v2/ops/admin/rbac/roles","GET"),v("/api/v2/ops/admin/rbac/permissions","GET"),v("/api/v2/ops/admin/rbac/me","GET")]);if(!ne(t))return;window._rbacState={tab:((s=window._rbacState)==null?void 0:s.tab)||"roles",selectedRoleId:((a=window._rbacState)==null?void 0:a.selectedRoleId)||(((n=p[0])==null?void 0:n.roleId)??null),selectedUserId:((i=window._rbacState)==null?void 0:i.selectedUserId)||null,roles:p||[],perms:u||[],rolePermIds:new Set,operatorFilters:((c=window._rbacState)==null?void 0:c.operatorFilters)||{page:0,size:20,phone:""},recentScope:((l=window._rbacState)==null?void 0:l.recentScope)||"all"},window._rbacRoles=p||[];const k=((m==null?void 0:m.roleNames)||[]).join("、")||"未分配",w=(m==null?void 0:m.name)||(m==null?void 0:m.phoneNumber)||"运营账号",T=w.trim().charAt(0).toUpperCase();e.innerHTML=`
      <div class="card rbac-profile">
        <div class="rbac-profile-main">
          <span class="rbac-profile-avatar" aria-hidden="true">${o(T)}</span>
          <div class="rbac-profile-text">
            <strong>${o(w)}</strong>
            <span class="sub">${o((m==null?void 0:m.phoneNumber)||"")}</span>
          </div>
        </div>
        <div class="rbac-profile-meta">
          <span>角色：${o(k)}</span>
          <span>权限项：${o((m==null?void 0:m.permissionCount)??0)}</span>
        </div>
        <div class="list-filter-actions" style="margin-left:auto;padding-bottom:0">
          ${ke("loadRbacPage()")}
        </div>
      </div>
      <div class="tabs rbac-tabs">
        <button type="button" class="tab ${window._rbacState.tab==="roles"?"active":""}" onclick="switchRbacTab('roles')">角色权限</button>
        ${S("ops:rbac:assign")?`<button type="button" class="tab ${window._rbacState.tab==="users"?"active":""}" onclick="switchRbacTab('users')">用户授权</button>`:""}
      </div>
      <div id="rbacPanel"></div>`,ce(),await pt()}catch(p){if(!ne(t))return;Se(e,p)}}function Ei(e){window._rbacState&&(window._rbacState.tab=e,document.querySelectorAll(".rbac-tabs .tab").forEach(t=>{t.classList.toggle("active",t.textContent.includes(e==="roles"?"角色":e==="users"?"用户":"最近"))}),pt())}async function pt(){const e=document.getElementById("rbacPanel");if(!e||!window._rbacState)return;const{tab:t}=window._rbacState;t==="roles"?(e.innerHTML=`
      <div class="rbac-split">
        <div class="card rbac-pane">
          <h3 class="pane-title">角色列表 ${H("rbacRoles")}</h3>
          ${j("rbacRoles",`<table class="data-table rbac-role-table">
            <thead><tr>
              ${V("rbacRoles")}
              <th>角色</th><th>标识</th><th>权限</th>
            </tr></thead>
            <tbody>${(window._rbacState.roles||[]).map(s=>`
              ${z("rbacRoles",s.roleId,window._rbacState.selectedRoleId===s.roleId?"rbac-role-row selected":"rbac-role-row",`if (!event.ctrlKey && !event.metaKey) selectRbacRole(${s.roleId})`)}
              ${W("rbacRoles",s.roleId)}
              <td>${o(s.roleName)}</td>
              <td><code>${o(s.roleKey)}</code></td>
              <td class="meta">${o((s.permissions||[])[0]||"-")}</td>
            </tr>`).join("")}</tbody>
          </table>`)}
        </div>
        <div class="card rbac-pane" id="rbacPermPane">
          <p class="sub">选择左侧角色以配置菜单权限</p>
        </div>
      </div>`,A("rbacRoles"),window._rbacState.selectedRoleId&&await zt(window._rbacState.selectedRoleId)):t==="users"&&(e.innerHTML=`
      <div class="rbac-split">
        <div class="card rbac-pane list-page-card">
          <h3 class="pane-title">运营账号</h3>
          ${F({onSearch:"searchRbacOperators()",onReset:"resetRbacOperatorFilters()",refreshFn:"fetchRbacOperators()",extraHtml:H("rbacOperators"),fieldsHtml:R("手机号",`<input id="rbacOpPhone" placeholder="支持模糊搜索" value="${r(window._rbacState.operatorFilters.phone)}">`)})}
          <div id="rbacOperatorList"></div>
        </div>
        <div class="card rbac-pane" id="rbacUserRolePane">
          <p class="sub">选择左侧运营账号分配角色</p>
        </div>
      </div>`,await Kt())}function Si(e){const t=new Map,s=[];(e||[]).forEach(n=>t.set(n.permissionId,{...n,children:[]})),(e||[]).forEach(n=>{const i=t.get(n.permissionId);n.parentId&&n.parentId!==0&&t.has(n.parentId)?t.get(n.parentId).children.push(i):s.push(i)});const a=n=>{n.sort((i,c)=>(i.sortOrder||0)-(c.sortOrder||0)),n.forEach(i=>a(i.children))};return a(s),s}function Ti(e){return{M:"目录",C:"菜单",F:"按钮"}[e]||e}function la(e,t,s=0){return(e||[]).map(a=>{var c;const n=t.has(a.permissionId),i=(c=a.children)!=null&&c.length?`<div class="perm-children">${la(a.children,t,s+1)}</div>`:"";return`
      <div class="perm-tree-node" style="padding-left:${s*18}px">
        <label class="perm-tree-label">
          <input type="checkbox" class="perm-cb" data-id="${a.permissionId}" ${n?"checked":""}
            onchange="onPermCheckChange(this, ${a.permissionId})">
          <span class="perm-type perm-type-${r(a.permType)}">${o(Ti(a.permType))}</span>
          <span class="perm-name">${o(a.permName)}</span>
          <code class="perm-code">${o(a.permCode)}</code>
        </label>
      </div>${i}`}).join("")}function ra(e,t){const s=[e];return(t||[]).filter(a=>a.parentId===e).forEach(a=>{s.push(...ra(a.permissionId,t))}),s}function Li(e,t){const s=document.getElementById("rbacPermPane");if(!s)return;const a=e.checked;if(ra(t,window._rbacState.perms).forEach(i=>{const c=s.querySelector('.perm-cb[data-id="'+i+'"]');c&&(c.checked=a)}),a){let i=(window._rbacState.perms.find(c=>c.permissionId===t)||{}).parentId;for(;i&&i!==0;){const c=s.querySelector('.perm-cb[data-id="'+i+'"]');c&&(c.checked=!0),i=(window._rbacState.perms.find(l=>l.permissionId===i)||{}).parentId}}}async function Ci(e){window._rbacState.selectedRoleId=e,document.querySelectorAll(".rbac-role-row").forEach(t=>{var s;t.classList.toggle("selected",(s=t.getAttribute("onclick"))==null?void 0:s.includes("("+e+")"))}),await zt(e)}async function zt(e){const t=document.getElementById("rbacPermPane");if(t){t.innerHTML='<p class="sub">加载权限树…</p>';try{const s=await v("/api/v2/ops/admin/rbac/roles/"+e+"/permissions","GET"),a=(window._rbacState.roles||[]).find(l=>l.roleId===e),n=new Set(s.permissionIds||[]);window._rbacState.rolePermIds=n;const i=(a==null?void 0:a.roleKey)==="admin",c=Si(window._rbacState.perms);t.innerHTML=`
      <div class="pane-head">
        <h3 class="pane-title">${o((a==null?void 0:a.roleName)||s.roleName)} · 菜单权限</h3>
        ${i?'<span class="badge badge-done">超级管理员不可编辑</span>':ae("rbac.role.save","保存权限","saveRolePermissions(event)","btn-primary btn-sm")}
      </div>
      <div class="perm-tree">${la(c,n)}</div>`,ce()}catch(s){b(s)||(t.innerHTML='<p class="err">'+o(s.message)+"</p>")}}}async function Ri(e){await _(e,async()=>{var a;const t=(a=window._rbacState)==null?void 0:a.selectedRoleId;if(!t)return;const s=[...document.querySelectorAll("#rbacPermPane .perm-cb:checked")].map(n=>parseInt(n.dataset.id,10));try{await v("/api/v2/ops/admin/rbac/roles/"+t+"/permissions","PUT",s),d("角色权限已保存","ok"),zt(t)}catch(n){b(n)||d("保存失败: "+n.message,"err")}})}function Bi(){window._rbacState&&(window._rbacState.operatorFilters.phone="",window._rbacState.operatorFilters.page=0,pt())}function da(){var e;window._rbacState.operatorFilters.phone=(((e=document.getElementById("rbacOpPhone"))==null?void 0:e.value)||"").trim(),window._rbacState.operatorFilters.page=0,Kt()}async function Kt(){const e=document.getElementById("rbacOperatorList");if(e){e.innerHTML='<p class="sub">加载中…</p>';try{const t=window._rbacState.operatorFilters,s=new URLSearchParams({page:t.page,size:t.size});t.phone&&s.set("phone",t.phone);const a=await v("/api/v2/ops/admin/rbac/operators?"+s,"GET");if(!a.items.length){e.innerHTML=P("暂无运营账号","运营账号 userId ≥ 100000000","searchRbacOperators()");return}e.innerHTML=j("rbacOperators",`
      <table class="data-table">
        <thead><tr>
          ${V("rbacOperators")}
          <th>手机号</th><th>姓名</th><th>当前角色</th>
        </tr></thead>
        <tbody>${a.items.map(n=>`
          ${z("rbacOperators",n.userId,window._rbacState.selectedUserId===n.userId?"rbac-user-row selected":"rbac-user-row",`if (!event.ctrlKey && !event.metaKey) selectRbacUser(${n.userId})`)}
          ${W("rbacOperators",n.userId)}
          <td>${o(n.phoneNumber)}</td>
          <td>${o(n.name||"-")}</td>
          <td class="meta">${o((n.roleNames||[]).join("、")||"未分配")}</td>
        </tr>`).join("")}</tbody>
      </table>`)+Gi(a),A("rbacOperators")}catch(t){b(t)||(e.innerHTML='<p class="err">'+o(t.message)+"</p>")}}}async function Qt(e){window._rbacState.selectedUserId=e,document.querySelectorAll(".rbac-user-row").forEach(s=>{var a;s.classList.toggle("selected",(a=s.getAttribute("onclick"))==null?void 0:a.includes("("+e+")"))});const t=document.getElementById("rbacUserRolePane");if(t){t.innerHTML='<p class="sub">加载授权…</p>';try{const[s,a]=await Promise.all([v("/api/v2/ops/admin/rbac/users/"+e+"/roles","GET"),v("/api/v2/ops/admin/rbac/users/"+e+"/merchants","GET")]),n=new Set(s.roleIds||[]),i=new Set(a.merchantIds||[]),c=(window._rbacRoles||[]).map(u=>`<label class="role-check-item">
        <input type="checkbox" class="rbac-role-cb" value="${r(u.roleId)}" ${n.has(u.roleId)?"checked":""}>
        <span>${o(u.roleName)}</span>
        <code>${o(u.roleKey)}</code>
      </label>`).join("");let l=window._rbacMerchants||[];if(!l.length)try{l=await v("/api/v2/ops/admin/merchants","GET"),window._rbacMerchants=l}catch{}const p=l.map(u=>`<label class="role-check-item">
        <input type="checkbox" class="rbac-merchant-cb" value="${r(u.merchantId)}" ${i.has(u.merchantId)?"checked":""}>
        <span>${o(u.merchantName)}</span>
        <code>${o(u.merchantId)}</code>
      </label>`).join("");t.innerHTML=`
      <h3 class="pane-title">分配角色 · 用户 ${o(e)}</h3>
      <div class="role-check-list">${c||'<p class="sub">无可用角色</p>'}</div>
      ${ae("rbac.assign","保存角色","saveUserRoles(event)","btn-primary btn-sm")}
      <h3 class="pane-title" style="margin-top:20px">数据范围 · 商户</h3>
      <p class="sub">不勾选任何商户 = 全局可见；勾选后仅可见对应商户的设备/订单/分账。</p>
      <div class="role-check-list">${p||'<p class="sub">暂无商户，请先在商户分账页创建</p>'}</div>
      ${ae("rbac.assign","保存商户范围","saveUserMerchants(event)","btn-primary btn-sm")}`,ce()}catch(s){b(s)||(t.innerHTML='<p class="err">'+o(s.message)+"</p>")}}}async function Pi(e){await _(e,async()=>{var a;const t=(a=window._rbacState)==null?void 0:a.selectedUserId;if(!t){d("请先选择运营账号","err");return}const s=[...document.querySelectorAll(".rbac-merchant-cb:checked")].map(n=>n.value);try{await v("/api/v2/ops/admin/rbac/users/"+t+"/merchants","PUT",s),d("商户数据范围已保存","ok"),Qt(t)}catch(n){b(n)||d("保存失败: "+n.message,"err")}})}async function Ai(e){await _(e,async()=>{var a;const t=(a=window._rbacState)==null?void 0:a.selectedUserId;if(!t){d("请先选择运营账号","err");return}const s=[...document.querySelectorAll(".rbac-role-cb:checked")].map(n=>parseInt(n.value,10));try{await v("/api/v2/ops/admin/rbac/users/"+t+"/roles","PUT",s),d("用户授权已保存","ok"),Kt(),Qt(t)}catch(n){b(n)||d("保存失败: "+n.message,"err")}})}function Di(e){window._rbacState.recentScope=e,pt()}async function Mi(){const e=document.getElementById("rbacRecentTable");if(e){e.innerHTML='<p class="sub">加载中…</p>';try{const t=window._rbacState.recentScope==="mine",s=new URLSearchParams({size:15,mine:t?"true":"false"}),a=await v("/api/v2/ops/admin/audit-logs/recent?"+s,"GET");e.innerHTML=pa(a,"rbacRecent"),A("rbacRecent")}catch(t){b(t)||(e.innerHTML='<p class="err">'+o(t.message)+"</p>")}}}function ua(e){return e.operatorPhone||e.operatorName?`${o(e.operatorName||"-")}<br><span class="meta">${o(e.operatorPhone||e.operatorId)}</span>`:o(e.operatorId)}function pa(e,t="audit"){return!e||!e.length?P("暂无操作记录","运营后台的敏感操作会记录在此"):j(t,`
    <table class="data-table">
      <thead><tr>
        ${V(t)}
        <th>时间</th><th>操作人</th><th>动作</th><th>对象</th><th>详情</th>
      </tr></thead>
      <tbody>${e.map(s=>`
        ${z(t,s.logId)}
        ${W(t,s.logId)}
        <td>${le(s.createdAt)}</td>
        <td>${ua(s)}</td>
        <td>${o(Ka(s.action))}</td>
        <td>${o(Qa(s.targetType))} ${o(s.targetId||"")}</td>
        <td class="meta">${o(s.detail||"-")}</td>
      </tr>`).join("")}</tbody>
    </table>`)}function Ni(e){window._rbacState=window._rbacState||{},window._rbacState.tab="users",window._rbacState.selectedUserId=e,navigate("rbac")}function Ke(e){return e==null?"-":(e*100).toFixed(1)+"%"}const me={page:0,size:20,deviceId:""},mt={lowStockOnly:!1},K={from:"",to:"",channel:"WECHAT"},ma=2;function Oi(e){if(!e)return"-";const t=Date.now()-new Date(e).getTime();if(t<0)return"刚刚";const s=t/36e5;return s<1?`${Math.max(1,Math.floor(t/6e4))} 分钟`:s<24?`${Math.floor(s)} 小时`:`${Math.floor(s/24)} 天`}function ts(e,t=ma){return e?Date.now()-new Date(e).getTime()>t*36e5:!1}async function Fe(){const e=document.getElementById("pageContent"),t="vision-mappings";N("visionYolo"),N("visionAliyun");try{const[s,a]=await Promise.all([v("/api/v2/ops/admin/vision-mappings","GET"),v("/api/v2/ops/admin/skus","GET")]);if(!ne(t))return;const n=Object.fromEntries((a||[]).map(u=>[u.skuId,u])),i=u=>{const m=n[u];return m?`${o(m.skuName)} <code>${o(u)}</code>`:`<code>${o(u)}</code>`},c=(a||[]).map(u=>`<option value="${r(u.skuId)}">${o(u.skuName)} (${o(u.skuId)})</option>`).join(""),l=(s.yolo||[]).map(u=>`
      ${z("visionYolo",u.className)}
      ${W("visionYolo",u.className)}
      <td><code>${o(u.className)}</code></td>
      <td>${i(u.skuId)}</td>
      <td>${o(u.minConfidence)}</td>
      <td class="col-actions" onclick="event.stopPropagation()"><div class="row-actions">${S("ops:vision:edit")?`<button type="button" class="btn-danger btn-sm" onclick="deleteYoloMapping('${r(u.className)}')">删除</button>`:'<span class="meta">-</span>'}</div></td>
    </tr>`).join(""),p=(s.aliyun||[]).map(u=>`
      ${z("visionAliyun",u.categoryId)}
      ${W("visionAliyun",u.categoryId)}
      <td><code>${o(u.categoryId)}</code></td>
      <td>${o(u.categoryName||"-")}</td>
      <td>${i(u.skuId)}</td>
      <td>${o(u.minConfidence)}</td>
      <td class="col-actions" onclick="event.stopPropagation()"><div class="row-actions">${S("ops:vision:edit")?`<button type="button" class="btn-danger btn-sm" onclick="deleteAliyunMapping('${r(u.categoryId)}')">删除</button>`:'<span class="meta">-</span>'}</div></td>
    </tr>`).join("");e.innerHTML=`
      <div class="card list-page-card">
        ${F({refreshFn:"loadVisionMappingsPage()",fieldsHtml:""})}
      </div>
      <div class="card list-page-card">
        <h3 style="margin-top:0">视觉识别类名 → 商品 ${H("visionYolo")}</h3>
        ${S("ops:vision:edit")?F({fieldsHtml:`
            ${R("类名",'<input id="ymClass" placeholder="bottle">')}
            ${R("商品",`<select id="ymSku">${c}</select>`)}
            ${R("最低置信度",'<input id="ymConf" type="number" step="0.01" min="0" max="1" value="0.5">')}`,extraHtml:'<button type="button" class="btn-primary btn-sm" onclick="saveYoloMapping(event)">保存</button>'}):""}
        ${l?j("visionYolo",`<table class="data-table"><thead><tr>
          ${V("visionYolo")}
          <th>识别类名</th><th>商品</th><th>置信度</th><th class="col-actions">操作</th>
        </tr></thead><tbody>${l}</tbody></table>`):P("暂无识别映射","添加视觉识别类名与商品的对应关系","loadVisionMappingsPage()")}
      </div>
      <div class="card list-page-card">
        <h3 style="margin-top:0">云端类目 → 商品 ${H("visionAliyun")}</h3>
        ${S("ops:vision:edit")?F({fieldsHtml:`
            ${R("类目编号",'<input id="amCatId" placeholder="201234567">')}
            ${R("类目名称",'<input id="amCatName" placeholder="碳酸饮料">')}
            ${R("商品",`<select id="amSku">${c}</select>`)}
            ${R("最低置信度",'<input id="amConf" type="number" step="0.01" min="0" max="1" value="0.7">')}`,extraHtml:'<button type="button" class="btn-primary btn-sm" onclick="saveAliyunMapping(event)">保存</button>'}):""}
        ${p?j("visionAliyun",`<table class="data-table"><thead><tr>
          ${V("visionAliyun")}
          <th>类目编号</th><th>名称</th><th>商品</th><th>置信度</th><th class="col-actions">操作</th>
        </tr></thead><tbody>${p}</tbody></table>`):P("暂无云端映射","对接商品识别服务后，在此维护类目与商品的对应关系","loadVisionMappingsPage()")}
      </div>`,A("visionYolo"),A("visionAliyun"),ce()}catch(s){if(!ne(t))return;Se(e,s)}}async function xi(e){await _(e,async()=>{const t=document.getElementById("ymClass").value.trim(),s=document.getElementById("ymSku").value,a=parseFloat(document.getElementById("ymConf").value)||.5;if(!t){d("请填写类名","err");return}try{await v("/api/v2/ops/admin/vision-mappings/yolo","POST",{className:t,skuId:s,minConfidence:a}),d("已保存","ok"),Fe()}catch(n){b(n)||d("保存失败: "+n.message,"err")}})}async function Ui(e){if(await ie(`删除识别映射 ${e}？`,{title:"删除映射",danger:!0}))try{await v("/api/v2/ops/admin/vision-mappings/yolo/"+encodeURIComponent(e),"DELETE"),d("已删除","ok"),Fe()}catch(t){b(t)||d("删除失败: "+t.message,"err")}}async function Fi(e){await _(e,async()=>{const t=document.getElementById("amCatId").value.trim(),s=document.getElementById("amCatName").value.trim(),a=document.getElementById("amSku").value,n=parseFloat(document.getElementById("amConf").value)||.7;if(!t){d("请填写类目 ID","err");return}try{await v("/api/v2/ops/admin/vision-mappings/aliyun","POST",{categoryId:t,categoryName:s,skuId:a,minConfidence:n}),d("已保存","ok"),Fe()}catch(i){b(i)||d("保存失败: "+i.message,"err")}})}async function Hi(e){if(await ie(`删除阿里云映射 ${e}？`,{title:"删除映射",danger:!0}))try{await v("/api/v2/ops/admin/vision-mappings/aliyun/"+encodeURIComponent(e),"DELETE"),d("已删除","ok"),Fe()}catch(t){b(t)||d("删除失败: "+t.message,"err")}}async function Yt(){const e=document.getElementById("pageContent");N("uploadQueue"),e.innerHTML=`
    <div class="card list-page-card">
      ${F({onSearch:"searchUploadQueue()",onReset:"resetUploadQueueFilters()",refreshFn:"fetchUploadQueue()",extraHtml:H("uploadQueue"),fieldsHtml:R("设备编号",`<input id="uqDevice" value="${r(me.deviceId)}" placeholder="留空=全部">`)})}
      <div id="uploadQueueTable"></div>
    </div>`,Q(document.getElementById("uploadQueueTable"),8,6),Jt()}function _i(){me.deviceId="",me.page=0,Yt()}function qi(){me.deviceId=document.getElementById("uqDevice").value.trim(),me.page=0,Jt()}async function Jt(){const e=document.getElementById("uploadQueueTable");if(e){Q(e,8,6);try{const t=new URLSearchParams({page:me.page,size:me.size,state:"WAITING_UPLOAD",...me.deviceId?{deviceId:me.deviceId}:{}}),s=await v("/api/v2/ops/admin/sessions?"+t,"GET");if(!s.items.length){e.innerHTML=P("暂无待上传会话","断网续传或视频未上传的会话会出现在此","fetchUploadQueue()");return}const a=s.items.filter(i=>ts(i.closeTime||i.updatedAt)).length,n=`<div class="stats stats-inline">
      <div class="stat"><div class="label">本页待上传</div><div class="value warn">${s.items.length}</div></div>
      <div class="stat"><div class="label">超时 (&gt;${ma}h)</div><div class="value ${a?"warn":"ok"}">${a}</div></div>
      <div class="stat"><div class="label">合计</div><div class="value">${s.total}</div></div>
    </div>`;e.innerHTML=n+j("uploadQueue",`
      <table class="data-table">
        <thead><tr>
          ${V("uploadQueue")}
          <th>记录编号</th><th>用户</th><th>设备</th><th>上传状态</th><th>等待时长</th><th>融合模式</th><th>视频</th><th>关门时间</th><th>更新时间</th>
        </tr></thead>
        <tbody>${s.items.map(i=>{const c=i.closeTime||i.updatedAt,l=ts(c);return`
          ${z("uploadQueue",i.sessionId,l?"row-overdue":"")}
          ${W("uploadQueue",i.sessionId)}
          <td><code>${o(i.sessionId)}</code>${l?' <span class="badge badge-fail">超时</span>':""}</td>
          <td>${o(i.userId)}</td>
          <td>${o(i.deviceId)}</td>
          <td>${o(os(i.uploadStatus))}</td>
          <td>${o(Oi(c))}</td>
          <td>${o(za(i.cameraFusionMode))}</td>
          <td class="col-actions" onclick="event.stopPropagation()"><div class="row-actions">${i.videoUri||i.videoPreviewUrl?`<button type="button" class="btn-ghost btn-sm" onclick="showSessionVideo('${r(i.sessionId)}', '${r(i.videoUri||"")}')">${At(i.videoUri)}</button>`:'<span class="meta">-</span>'}</div></td>
          <td>${le(i.closeTime)}</td>
          <td>${le(i.updatedAt)}</td>
        </tr>`}).join("")}</tbody>
      </table>`)+ji(s),A("uploadQueue")}catch(t){J(e,t,!1)}}}function ji(e){return ot(e,"upload")}function Gi(e){const t=window._rbacState.operatorFilters;return ot({page:t.page,size:t.size,total:e.total||0},"rbacOp")}function Vi(e){return ot(e,"merchantSplit")}const D={page:0,size:20,merchantId:"",status:"PENDING"};async function ht(){const e=document.getElementById("pageContent");N("merchants"),N("merchantSplits");let t="";if(S("ops:merchant:split"))try{const s=await v("/api/v2/ops/admin/merchants/profit-sharing/status","GET"),a=s.apiReady?"ok":"warn";t=`<div class="demo-banner" style="${s.apiReady?"background:#f6ffed;border-color:#b7eb8f;color:#389e0d":""}">
        分账：${s.enabled?"已启用":"未启用"} · 微信支付 ${o(s.wechatPayConfigured)} · API ${s.apiReady?"就绪":"未就绪"}
        · 重试 ${s.retryEnabled?"开":"关"}(${s.retryBatchSize}/批)
        <span class="meta"> — ${o(s.note||"")}</span>
      </div>`}catch{}e.innerHTML=`
    ${t}
    <div class="card list-page-card">
      <div class="list-filter-bar">
        <div class="list-filter-fields"></div>
        <div class="list-filter-actions">
          <button type="button" class="btn-primary btn-sm" data-perm="ops:merchant:edit" onclick="showMerchantForm()">新增商户</button>
          ${ke("loadMerchantsPage()")}
          ${H("merchants")}
        </div>
      </div>
      <div id="merchantTable" class="sub">加载中…</div>
    </div>
    <div class="card list-page-card">
      <h3 style="margin-top:0">分账明细</h3>
      ${F({onSearch:"searchMerchantSplits()",onReset:"resetMerchantSplitFilters()",refreshFn:"fetchMerchantSplits()",extraHtml:`${H("merchantSplits")}<button type="button" class="btn-ghost btn-sm" onclick="exportMerchantSplits()">导出 CSV</button>${S("ops:merchant:split")?'<button type="button" class="btn-ok btn-sm" id="batchSplitBtn" onclick="batchSubmitProfitSharing()" disabled>批量提交微信分账</button>':""}`,fieldsHtml:`
          ${R("商户编号",`<input id="msMerchant" value="${r(D.merchantId)}" placeholder="留空=全部">`)}
          ${R("状态",`<select id="msStatus">
            <option value="">全部</option>
            <option value="PENDING" ${D.status==="PENDING"?"selected":""}>待处理</option>
            <option value="ACCRUED" ${D.status==="ACCRUED"?"selected":""}>待分账</option>
            <option value="LEDGER_ONLY" ${D.status==="LEDGER_ONLY"?"selected":""}>仅记账</option>
            <option value="WECHAT_SUBMITTED" ${D.status==="WECHAT_SUBMITTED"?"selected":""}>已提交</option>
            <option value="WECHAT_FAILED" ${D.status==="WECHAT_FAILED"?"selected":""}>失败</option>
            <option value="SUBMITTED" ${D.status==="SUBMITTED"?"selected":""}>已提交(旧)</option>
            <option value="SUCCESS" ${D.status==="SUCCESS"?"selected":""}>成功</option>
            <option value="FAILED" ${D.status==="FAILED"?"selected":""}>失败(旧)</option>
          </select>`)}`})}
      <div id="merchantSplitTable"></div>
    </div>`,ce(),Wi(),He()}async function Wi(){const e=document.getElementById("merchantTable");if(e)try{const t=await v("/api/v2/ops/admin/merchants","GET");if(!t||!t.length){e.innerHTML=P("暂无商户","点击「新增商户」创建加盟商/直营主体","loadMerchantsPage()");return}e.innerHTML=j("merchants",`<table class="data-table"><thead><tr>
      ${V("merchants")}
      <th>商户编号</th><th>名称</th><th>平台抽成</th><th>设备数</th><th>状态</th><th class="col-actions">操作</th>
    </tr></thead><tbody>${t.map(s=>`
      ${z("merchants",s.merchantId)}
      ${W("merchants",s.merchantId)}
      <td><code>${o(s.merchantId)}</code></td>
      <td>${o(s.merchantName)}</td>
      <td>${(s.platformRateBps/100).toFixed(1)}%</td>
      <td>${o(s.deviceCount)}</td>
      <td>${o(ja(s.status))}</td>
      <td class="col-actions" onclick="event.stopPropagation()"><div class="row-actions">${S("ops:merchant:edit")?`<button type="button" class="btn-ghost btn-sm" onclick='showMerchantForm(${JSON.stringify(s)})'>编辑</button>`:'<span class="meta">-</span>'}</div></td>
    </tr>`).join("")}</tbody></table>`),A("merchants")}catch(t){J(e,t,!1)}}function zi(e){const t=!!e;ue(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>${t?"编辑商户":"新增商户"}</h3>
        <label>商户ID</label>
        <input id="mfId" value="${t?r(e.merchantId):""}" ${t?"disabled":""} placeholder="MCH-001">
        <label>商户名称</label>
        <input id="mfName" value="${t?r(e.merchantName):""}">
        <label>联系电话</label>
        <input id="mfPhone" value="${t?r(e.contactPhone||""):""}">
        <label>平台抽成（基点，1000=10%）</label>
        <input id="mfRate" type="number" min="0" max="10000" value="${t?r(e.platformRateBps):"1000"}">
        <label>微信分账接收方 ID（可选）</label>
        <input id="mfWx" value="${t?r(e.wechatReceiverId||""):""}">
        <label>备注</label>
        <input id="mfRemark" value="${t?r(e.remark||""):""}">
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveMerchant(event, ${t})">保存</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`)}async function Ki(e,t){await _(e,async()=>{const s={merchantId:document.getElementById("mfId").value.trim(),merchantName:document.getElementById("mfName").value.trim(),contactPhone:document.getElementById("mfPhone").value.trim(),platformRateBps:parseInt(document.getElementById("mfRate").value,10)||1e3,wechatReceiverId:document.getElementById("mfWx").value.trim(),remark:document.getElementById("mfRemark").value.trim(),status:"ACTIVE"};if(!s.merchantId||!s.merchantName){d("请填写商户 ID 和名称","err");return}try{await v("/api/v2/ops/admin/merchants","POST",s),be(),d("保存成功","ok"),ht()}catch(a){b(a)||d("保存失败: "+a.message,"err")}})}function Qi(){D.merchantId="",D.status="PENDING",D.page=0,N("merchantSplits"),ht()}function Yi(){D.merchantId=document.getElementById("msMerchant").value.trim(),D.status=document.getElementById("msStatus").value,D.page=0,N("merchantSplits"),He()}function Lt(){const e=document.getElementById("batchSplitBtn");if(!e)return;const t=Dt("merchantSplits").length;e.disabled=t===0,e.textContent=t?`批量提交微信分账 (${t})`:"批量提交微信分账"}async function He(){const e=document.getElementById("merchantSplitTable");if(e){Q(e,8,6);try{const t=new URLSearchParams({page:D.page,size:D.size,...D.merchantId?{merchantId:D.merchantId}:{},...D.status?{status:D.status}:{}}),s=await v("/api/v2/ops/admin/merchants/revenue-splits?"+t,"GET");if(!s.items.length){e.innerHTML=P("暂无分账记录",D.status==="PENDING"?"没有待处理的分账，订单结算后会自动记账":"订单结算后会按设备所属商户自动记账","fetchMerchantSplits()"),Lt();return}e.innerHTML=j("merchantSplits",`<table class="data-table"><thead><tr>
      ${V("merchantSplits")}
      <th>分账编号</th><th>订单</th><th>商户</th><th>设备</th><th>总额</th><th>平台</th><th>商户收入</th><th>状态</th><th>时间</th><th class="col-actions">操作</th>
    </tr></thead><tbody>${s.items.map(a=>{const n=Ji(a),i=Zi(a),c=[n?`<button type="button" class="btn-ghost btn-sm" onclick="showWeChatSubmitForm('${r(a.splitId)}', '${r(a.wechatTransactionId||"")}')">提交</button>`:"",i?`<button type="button" class="btn-ghost btn-sm" onclick="refreshWeChatProfitSharing('${r(a.splitId)}')">刷新</button>`:""].filter(Boolean);return`
      ${z("merchantSplits",a.splitId)}
      ${W("merchantSplits",a.splitId)}
      <td><code>${o(a.splitId)}</code></td>
      <td>${o(a.orderId)}</td>
      <td>${o(a.merchantName||a.merchantId)}</td>
      <td>${o(a.deviceId)}</td>
      <td>${pe(a.grossCents)}</td>
      <td>${pe(a.platformCents)}</td>
      <td>${pe(a.merchantCents)}</td>
      <td>${nn(a.status)}${a.failureReason?` <span class="meta" title="${r(a.failureReason)}">!</span>`:""}</td>
      <td>${le(a.createdAt)}</td>
      <td class="col-actions" onclick="event.stopPropagation()"><div class="row-actions">${c.length?c.join(""):'<span class="meta">-</span>'}</div></td>
    </tr>`}).join("")}</tbody></table>`)+Vi(s),A("merchantSplits"),Lt()}catch(t){J(e,t,!1)}}}function Ji(e){if(!S("ops:merchant:split"))return!1;const t=(e.status||"").toUpperCase();return t==="ACCRUED"||t==="LEDGER_ONLY"||t==="WECHAT_FAILED"||t==="FAILED"}function Zi(e){if(!S("ops:merchant:split"))return!1;const t=(e.status||"").toUpperCase();return(t==="WECHAT_SUBMITTED"||t==="WECHAT_FAILED")&&!!(e.wechatOutOrderNo&&e.wechatTransactionId)}function Xi(e,t){ue(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>提交微信分账</h3>
        <p class="meta">分账ID <code>${o(e)}</code></p>
        <label>微信交易单号 wxTransactionId</label>
        <input id="wxTxnId" value="${r(t||"")}" placeholder="余额支付订单需手动填写">
        <p class="meta">购物订单为余额支付时，需填写对应微信充值/支付流水号。</p>
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="submitWeChatProfitSharing(event, '${r(e)}')">提交</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`)}async function ec(e,t){await _(e,async()=>{var a;const s=((a=document.getElementById("wxTxnId"))==null?void 0:a.value.trim())||"";try{await v("/api/v2/ops/admin/merchants/revenue-splits/"+encodeURIComponent(t)+"/wechat-submit","POST",s?{wxTransactionId:s}:{}),be(),d("分账已提交","ok"),He()}catch(n){b(n)||d("提交失败: "+n.message,"err")}},"提交中…")}async function tc(e){try{await v("/api/v2/ops/admin/merchants/revenue-splits/"+encodeURIComponent(e)+"/wechat-refresh","POST",{}),d("状态已刷新","ok"),He()}catch(t){b(t)||d("刷新失败: "+t.message,"err")}}async function sc(){const e=Dt("merchantSplits");if(!e.length||!await ie(`确认批量提交 ${e.length} 笔微信分账？
已有 wxTransactionId 的记录将自动提交；缺少流水号的会跳过。`,{title:"批量分账"}))return;const t=document.getElementById("batchSplitBtn");t&&(t.disabled=!0);let s=0,a=0;const n=[];for(const c of e)try{await v("/api/v2/ops/admin/merchants/revenue-splits/"+encodeURIComponent(c)+"/wechat-submit","POST",{}),s+=1,us("merchantSplits",c,!1)}catch(l){if(b(l))break;const p=l.message||String(l);/wxTransactionId|流水|余额支付/i.test(p)?a+=1:n.push(`${c}: ${p}`)}const i=[`成功 ${s} 笔`];a&&i.push(`跳过 ${a} 笔（缺流水号）`),n.length&&i.push(`失败 ${n.length} 笔`),d(i.join("，"),n.length?"err":"ok"),n.length&&console.warn("批量分账失败:",n),He()}async function ac(){try{const e=({}.VITE_API_BASE||"").replace(/\/$/,"")||window.location.origin,t=new URLSearchParams({...D.merchantId?{merchantId:D.merchantId}:{},...D.status?{status:D.status}:{}}),s=await fetch(e+"/api/v2/ops/admin/merchants/revenue-splits/export?"+t,{headers:{Authorization:"Bearer "+localStorage.getItem("admin_token")}});if(!s.ok)throw new Error("导出失败");const a=await s.blob(),n=URL.createObjectURL(a),i=document.createElement("a");i.href=n,i.download="revenue-splits.csv",i.click(),URL.revokeObjectURL(n)}catch(e){d("导出失败: "+e.message,"err")}}async function Le(){const e=document.getElementById("pageContent"),t="warehouse",s=window._transitDeviceFilter||"";try{const a=s?"/api/v2/ops/admin/warehouse/in-transit?deviceId="+encodeURIComponent(s):"/api/v2/ops/admin/warehouse/in-transit",[n,i,c,l,p]=await Promise.all([v("/api/v2/ops/admin/warehouse/list","GET"),v("/api/v2/ops/admin/warehouse/inventory","GET"),v("/api/v2/ops/admin/warehouse/outbounds","GET"),v("/api/v2/ops/admin/skus","GET").catch(()=>[]),v(a,"GET").catch(()=>[])]);if(!ne(t))return;const u=Object.fromEntries((l||[]).map(y=>[y.skuId,y])),m=y=>{var g;return((g=u[y])==null?void 0:g.skuName)||y},k=(n||[])[0],w=(i||[]).map(y=>`<tr>
      <td><code>${o(y.batchNo)}</code></td>
      <td>${o(m(y.skuId))}</td>
      <td class="col-num">${o(y.quantity)}</td>
      <td>${o(y.expiryDate||"-")}</td>
    </tr>`).join(""),T=(c||[]).slice(0,10).map(y=>{const g=(y.lines||[]).map(E=>`${o(m(E.skuId))}×${E.quantity}@${o(E.batchNo)}→${o(E.deviceId||"-")}`).join("<br>");return`<tr>
        <td>#${y.outboundId}</td><td>${o(y.status)}</td><td>${y.routeId||"-"}</td>
        <td>${g||"-"}</td>
        <td class="col-actions" onclick="event.stopPropagation()"><div class="row-actions">${S("ops:replenishment:edit")&&y.status!=="SHIPPED"?`<button type="button" class="btn-ghost btn-sm" onclick="pickWarehouseOutbound(${y.outboundId})">拣货</button>
             <button type="button" class="btn-ok btn-sm" onclick="shipWarehouseOutbound(${y.outboundId})">出库</button>`:'<span class="meta">-</span>'}</div></td>
      </tr>`}).join(""),O=Date.now(),h=(p||[]).map(y=>{const E=(y.createdAt?Math.round((O-new Date(y.createdAt).getTime())/36e5):0)>=24;return`<tr class="${E?"warn-row":""}">
        <td><code>${o(y.deviceId)}</code></td>
        <td>${o(m(y.skuId))}</td>
        <td><code>${o(y.batchNo)}</code></td>
        <td class="col-num">${y.quantity}</td>
        <td>#${y.outboundId}</td>
        <td>${le(y.createdAt)}${E?' <span class="warn-text">超24h</span>':""}</td>
      </tr>`}).join("");e.innerHTML=`
      <div class="card list-page-card">
        ${F({refreshFn:"loadWarehousePage()",extraHtml:ae("replenish.edit","仓库入库","showWarehouseInboundForm()","btn-primary btn-sm"),fieldsHtml:`<p class="meta" style="margin:0;padding-bottom:2px">${k?"当前仓库："+o(k.warehouseName)+" ("+o(k.warehouseId)+")":"暂无仓库"}</p>`})}
      </div>
      ${ft("仓库批次库存",(i||[]).length?`<table class="data-table"><thead><tr><th>批次</th><th>商品</th><th class="col-num">数量</th><th>到期</th></tr></thead><tbody>${w}</tbody></table>`:"",P("仓库无库存","点击「仓库入库」添加批次"))}
      ${ft("出库单（先到期先出拣货）",(c||[]).length?`<table class="data-table"><thead><tr><th>编号</th><th>状态</th><th>路线</th><th>明细</th><th class="col-actions">操作</th></tr></thead><tbody>${T}</tbody></table>`:"",'<p class="meta" style="padding:0 4px">规划补货路线后自动生成出库单</p>')}
      ${ft("在途库存（发往柜机，未签收）",(p||[]).length?`<table class="data-table"><thead><tr><th>柜机</th><th>商品</th><th>批次</th><th class="col-num">数量</th><th>出库单</th><th>发运时间</th></tr></thead><tbody>${h}</tbody></table>`:"",P("无在途库存","出库发运后、补货签收前会显示在此"),F({onSearch:"filterInTransit()",onReset:"resetInTransitFilter()",fieldsHtml:R("柜机筛选",`<input id="transitDeviceFilter" value="${r(s)}" placeholder="留空=全部">`)}))}`,ce()}catch(a){if(!ne(t))return;Se(e,a)}}function ha(){var e,t;window._transitDeviceFilter=((t=(e=document.getElementById("transitDeviceFilter"))==null?void 0:e.value)==null?void 0:t.trim())||"",Le()}function nc(){window._transitDeviceFilter="",Le()}async function oc(){const t=(await v("/api/v2/ops/admin/skus","GET").catch(()=>[])||[]).filter(n=>n.status==="ACTIVE").map(n=>`<option value="${r(n.skuId)}">${o(n.skuName)}</option>`).join(""),s=new Date().toISOString().slice(0,10),a=new Date(Date.now()+30*864e5).toISOString().slice(0,10);ue(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" style="max-width:560px" onclick="event.stopPropagation()">
        <h3>仓库入库</h3>
        <label>SKU</label><select id="whInSku">${t}</select>
        <label>批次号</label><input id="whInBatch" placeholder="B-WH-001">
        <div class="filters form-grid">
          <div><label>数量</label><input id="whInQty" type="number" min="1" value="10"></div>
          <div><label>到期日</label><input id="whInExpiry" type="date" value="${a}"></div>
        </div>
        <label>生产日期</label><input id="whInProd" type="date" value="${s}">
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveWarehouseInbound(event)">确认入库</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`)}async function ic(e){var c,l,p,u,m,k;e&&e.preventDefault();const t=(c=document.getElementById("whInSku"))==null?void 0:c.value,s=(p=(l=document.getElementById("whInBatch"))==null?void 0:l.value)==null?void 0:p.trim(),a=parseInt((u=document.getElementById("whInQty"))==null?void 0:u.value,10),n=(m=document.getElementById("whInExpiry"))==null?void 0:m.value,i=(k=document.getElementById("whInProd"))==null?void 0:k.value;if(!t||!s||!a||!n){d("请填写完整","err");return}try{await v("/api/v2/ops/admin/warehouse/inbound","POST",{warehouseId:"WH-DEMO-001",refNo:"IN-"+Date.now(),lines:[{skuId:t,batchNo:s,quantity:a,expiryDate:n,productionDate:i}]}),be(),d("入库成功","ok"),Le()}catch(w){b(w)||d("入库失败: "+w.message,"err")}}async function cc(e){try{await v("/api/v2/ops/admin/warehouse/outbounds/"+e+"/pick","POST"),d("已标记拣货","ok"),Le()}catch(t){b(t)||d("操作失败: "+t.message,"err")}}async function lc(e){if(await ie("确认出库？将扣减仓库库存。",{title:"出库确认"}))try{await v("/api/v2/ops/admin/warehouse/outbounds/"+e+"/ship","POST"),d("出库完成","ok"),Le()}catch(t){b(t)||d("出库失败: "+t.message,"err")}}Ee.opsLoaders={sla:oa,ota:Gt,risk:ut,reconciliation:Vt,replenishment:fe,warehouse:Le,rbac:ca,visionMappings:Fe,uploadQueue:Yt,merchants:ht};Object.assign(window,{loadSlaPage:oa,loadOtaPage:Gt,loadRiskPage:ut,showBlacklistForm:Xo,saveBlacklist:ei,removeBlacklist:si,loadReconciliationPage:Vt,loadReplenishmentPage:fe,showInventoryForm:ri,showSkuStocktakeForm:ui,saveSkuStocktake:pi,showWriteOffForm:mi,saveWriteOff:hi,saveInventory:di,completeReplenishmentTask:vi,loadRbacPage:ca,showOtaPublishForm:Jo,publishOta:Zo,fetchReconciliationList:Wt,resetReconciliationFilters:ni,runReconToday:oi,showReconDetail:ii,showReplenishmentPlanForm:Ii,saveReplenishmentPlan:wi,getSelectedReplenishmentDevices:ia,toggleAllReplenishmentDevices:ki,switchRbacTab:Ei,selectRbacRole:Ci,saveRolePermissions:Ri,searchRbacOperators:da,resetRbacOperatorFilters:Bi,debouncedSearchRbacOperators:ti,selectRbacUser:Qt,saveUserRoles:Ai,saveUserMerchants:Pi,setRbacRecentScope:Di,fetchRbacRecent:Mi,onPermCheckChange:Li,openRbacUserAssign:Ni,renderAuditTableHtml:pa,formatOperatorCell:ua,loadVisionMappingsPage:Fe,saveYoloMapping:xi,deleteYoloMapping:Ui,saveAliyunMapping:Fi,deleteAliyunMapping:Hi,loadUploadQueuePage:Yt,searchUploadQueue:qi,resetUploadQueueFilters:_i,fetchUploadQueue:Jt,loadMerchantsPage:ht,showMerchantForm:zi,saveMerchant:Ki,searchMerchantSplits:Yi,resetMerchantSplitFilters:Qi,fetchMerchantSplits:He,exportMerchantSplits:ac,showWeChatSubmitForm:Xi,submitWeChatProfitSharing:ec,batchSubmitProfitSharing:sc,toggleReplenishmentLowStock:ci,planRouteFromLowStock:li,filterInTransit:ha,resetInTransitFilter:nc});document.addEventListener("selchange",e=>{var t;((t=e.detail)==null?void 0:t.scope)==="merchantSplits"&&Lt()});window.merchantSplitFilters=D;window.uploadQueueFilters=me;window.replenishmentFilters=mt;window.showReplenishmentLinesForm=bi;window.addReplenishmentLineRow=Xe;window.saveReplenishmentLines=fi;window.viewDeviceLots=gi;window.loadWarehousePage=Le;window.filterInTransit=ha;window.refreshWeChatProfitSharing=tc;window.showWarehouseInboundForm=oc;window.saveWarehouseInbound=ic;window.pickWarehouseOutbound=cc;window.shipWarehouseOutbound=lc;va();
