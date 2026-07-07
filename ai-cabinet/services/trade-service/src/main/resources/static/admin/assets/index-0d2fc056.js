(function(){const t=document.createElement("link").relList;if(t&&t.supports&&t.supports("modulepreload"))return;for(const n of document.querySelectorAll('link[rel="modulepreload"]'))s(n);new MutationObserver(n=>{for(const d of n)if(d.type==="childList")for(const o of d.addedNodes)o.tagName==="LINK"&&o.rel==="modulepreload"&&s(o)}).observe(document,{childList:!0,subtree:!0});function a(n){const d={};return n.integrity&&(d.integrity=n.integrity),n.referrerPolicy&&(d.referrerPolicy=n.referrerPolicy),n.crossOrigin==="use-credentials"?d.credentials="include":n.crossOrigin==="anonymous"?d.credentials="omit":d.credentials="same-origin",d}function s(n){if(n.ep)return;n.ep=!0;const d=a(n);fetch(n.href,d)}})();function i(e){return e==null?"":String(e).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;").replace(/'/g,"&#39;")}function b(e){return i(e).replace(/`/g,"&#96;")}function ie(e){if(!e)return"未知错误";const t=e.message&&String(e.message).trim()||"";if(t&&/[\u4e00-\u9fff]/.test(t))return t;const a=t.toLowerCase();return a.includes("missing token")||a.includes("invalid token")?"登录已失效，请重新登录":a.includes("permission denied")?"无权限执行此操作":a.includes("consumer")||a.includes("operator")?"请使用运营账号登录后台":t||"请求失败"}function ze(e){const t=ie(e);return e&&(e.status===401||e.status===403||/401|403|登录已失效|无权限|权限不足/i.test(t))}function u(e){return ze(e)?(typeof logout=="function"&&logout(),typeof showErr=="function"&&showErr("loginErr",e.status===403?"权限不足或登录已失效，请重新登录":"登录已失效，请重新登录"),!0):!1}function qe(e){const t=e||"-",s={PAID:"已支付",PENDING:"待支付",REFUNDED:"已退款",CANCELLED:"已取消"}[t]||t;return`<span class="badge ${t==="PAID"?"badge-done":t==="PENDING"?"badge-active":t==="REFUNDED"?"badge-fail":t==="CANCELLED"?"badge-offline":"badge-active"}">${i(s)}</span>`}const Ve={CREATED:"已创建",OPENING:"开门中",SHOPPING:"购物中",RECOGNIZING:"识别中",WAITING_UPLOAD:"等待上传",SETTLING:"结算中",COMPLETED:"已完成",DISPUTED:"待审核",FAILED:"失败",CANCELLED:"已取消"};function Ie(e){return Ve[e]||e||"-"}function Ke(e){return e?`<span class="badge ${["COMPLETED","CANCELLED"].includes(e)?"badge-done":["FAILED","DISPUTED"].includes(e)?"badge-fail":"badge-active"}">${i(Ie(e))}</span>`:"-"}function Je(e){const t=String(e||"UNKNOWN").toUpperCase(),a=t==="ONLINE";return`<span class="badge ${a?"badge-online":"badge-offline"}">${i(a?"在线":t==="OFFLINE"?"离线":e||"-")}</span>`}function r(e,t){const a=document.getElementById("toastRoot");if(!a){alert(e);return}const s=document.createElement("div");s.className="toast toast-"+(t||"info"),s.textContent=e,a.appendChild(s),setTimeout(()=>s.classList.add("show"),10),setTimeout(()=>{s.classList.remove("show"),setTimeout(()=>s.remove(),300)},3200)}function T(e,t,a){if(u(t)||!e)return;const s=a!==!1?"card err":"err";e.innerHTML=`<div class="${s}">${i(t.message||"加载失败")}</div>`}const We={dashboard:"stats",devices:"table",sessions:"filters-table",orders:"filters-table",recharges:"filters-table",skus:"filters-table",users:"filters-table",reports:"table",audit:"filters-table",recent:"filters-table",disputes:"table",sla:"stats",ota:"filters-table",risk:"table",reconciliation:"filters-table",replenishment:"table",rbac:"table"};function ae(e){return`<div class="skel-bar" style="width:${e}"></div>`}function Xe(e,t){let a="";for(let s=0;s<t;s++){a+='<tr class="skel-row">';for(let n=0;n<e;n++)a+='<td><div class="skel-bar skel-cell"></div></td>';a+="</tr>"}return a}function oe(e,t){e=e||6,t=t||5;let a="<tr>";for(let s=0;s<e;s++)a+='<th><div class="skel-bar skel-th"></div></th>';return a+="</tr>",`<div class="skeleton-table-wrap card" style="padding:0;overflow:hidden">
    <table class="skeleton-table"><thead>${a}</thead>
    <tbody>${Xe(e,t)}</tbody></table></div>`}function Ze(){return`<div class="card skeleton-filters">
    <div class="skel-filter-row">
      ${ae("120px")}${ae("160px")}${ae("72px")}
    </div>
  </div>`}function Ye(){return`<div class="page-loading">
    <div class="stats">${Array.from({length:8},()=>'<div class="stat skel-stat"><div class="skel-bar skel-label"></div><div class="skel-bar skel-value"></div></div>').join("")}</div>
    <div class="card skel-chart">
      <div class="skel-bar skel-title"></div>
      <div class="skel-bars">${Array.from({length:7},()=>'<div class="skel-chart-bar"></div>').join("")}</div>
    </div>
  </div>`}function Qe(e){return e==="stats"?Ye():e==="filters-table"?Ze()+oe(6,6):oe(6,8)}function et(e){const t=document.getElementById("pageContent");t&&(t.innerHTML=Qe(We[e]||"table"))}function E(e,t,a){e&&(e.innerHTML=oe(t,a))}function v(e,t){const a=b(e),s=i(t||"刷新");return`<button type="button" class="btn-ghost btn-sm" onclick="${a}">${s}</button>`}function f(e,t,a){const s=a?`<div class="empty-actions">${v(a)}</div>`:"";return`<div class="empty-state">
    <div class="empty-icon" aria-hidden="true"></div>
    <div class="empty-title">${i(e)}</div>
    ${t?`<div class="empty-hint">${i(t)}</div>`:""}
    ${s}
  </div>`}let D=new Set;const tt={dashboard:"ops:dashboard:view",devices:"ops:device:list",sessions:"ops:session:list",orders:"ops:order:list",recharges:"ops:order:list",skus:"ops:sku:list",users:"ops:user:list",reports:"ops:device:list",audit:"ops:audit:list",recent:"ops:audit:recent",disputes:"ops:dispute",sla:"ops:sla",ota:"ops:ota:list",risk:"ops:risk:list",reconciliation:"ops:reconciliation:list",replenishment:"ops:replenishment:list",rbac:"ops:rbac:role"},at={"device.create":"ops:device:edit","device.edit":"ops:device:edit","session.cancel":"ops:session:cancel","sku.edit":"ops:sku:edit","user.balance":"ops:user:balance","recharge.refund":"ops:user:balance","ota.publish":"ops:ota:publish","risk.blacklist":"ops:risk:blacklist","recon.run":"ops:reconciliation:run","replenish.edit":"ops:replenishment:edit","replenish.plan":"ops:replenishment:edit","rbac.assign":"ops:rbac:assign","rbac.role.save":"ops:rbac:role"};async function st(e){try{const t=await e("/api/v2/ops/admin/rbac/me/permissions","GET");D=new Set(t||[]),D.has("*")&&(D=new Set(["*"]))}catch(t){console.warn("load permissions failed, no permissions granted",t),D=new Set}A()}function k(e){return!e||D.has("*")?!0:D.has(e)}function nt(e){const t=tt[e];return!!(!t||k(t)||e==="audit"&&k("ops:dashboard:view"))}function A(){document.querySelectorAll(".nav-item[data-page]").forEach(e=>{const t=e.dataset.page;nt(t)?e.classList.remove("hidden"):e.classList.add("hidden")}),document.querySelectorAll("[data-perm]").forEach(e=>{const t=e.getAttribute("data-perm");k(t)?e.style.display="":e.style.display="none"}),document.querySelectorAll(".nav-section").forEach(e=>{const t=e.querySelectorAll(".nav-item[data-page]");if(!t.length)return;const a=[...t].some(s=>!s.classList.contains("hidden"));e.classList.toggle("hidden",!a)})}function O(e,t,a,s){const n=at[e];return n&&!k(n)?"":`<button class="${s||"btn-primary btn-sm"}" data-perm="${n||""}" onclick="${a}">${t}</button>`}const N={api:null,getCurrentPage:()=>"dashboard",fmtTime:e=>e||"-",fmtMoney:e=>String(e),closeModal:()=>{},opsLoaders:{}},Ee=({}.VITE_API_BASE||"").replace(/\/$/,"")||window.location.origin;let C=localStorage.getItem("admin_token")||"",J=[],$="dashboard";const y={page:0,size:20,deviceId:"",state:""},S={page:0,size:20,deviceId:""},w={page:0,size:20,status:"",userId:""},H={dashboard:"数据概览",devices:"设备管理",sessions:"购物会话",orders:"订单管理",recharges:"充值管理",skus:"商品管理",users:"用户管理",reports:"设备报表",audit:"操作日志",recent:"最近操作",disputes:"争议审核",sla:"SLA 监控",ota:"设备 OTA",risk:"风控",reconciliation:"对账",replenishment:"补货",rbac:"权限管理"},B={page:0,size:20,phone:""},de={page:0,size:20},j={size:20,mine:!1};function we(e){return(e||"").replace(/\s/g,"")}function it(e){return(e||"").trim()}function I(e){return"¥"+(e/100).toFixed(2)}function L(e){return e?new Date(e).toLocaleString("zh-CN"):"-"}async function c(e,t,a,s=!0){const n={"Content-Type":"application/json"};s&&C&&(n.Authorization="Bearer "+C);const d=await fetch(Ee+e,{method:t,headers:n,body:a?JSON.stringify(a):void 0}),o=await d.json().catch(()=>({}));if(d.status===401||d.status===403){const h=new Error(ie({message:o.message,status:d.status})||(d.status===403?"权限不足":"登录已失效，请重新登录"));throw h.status=d.status,u(h),h}if(!d.ok||o.code!==0){const h=new Error(ie({message:o.message||o.error})||JSON.stringify(o));throw h.status=d.status,h}return o.data}function x(e,t){const a=document.getElementById(e);a.textContent=t,a.classList.remove("hidden")}function ot(){if(!(location.hostname==="localhost"||location.hostname==="127.0.0.1"))return;const t=document.getElementById("phone"),a=document.getElementById("code");t&&(t.placeholder="本地测试运营号 13900000001"),a&&(a.placeholder="本地固定 123456")}function U(e,t){const a=document.getElementById("loginBtn"),s=document.getElementById("sendCodeBtn"),n=document.getElementById("phone"),d=document.getElementById("code"),o=!!e;t==="login"?(a&&(a.disabled=o,a.classList.toggle("btn-loading",o),a.textContent=o?"登录中…":"登录"),s&&(s.disabled=o),n&&(n.readOnly=o),d&&(d.readOnly=o)):t==="code"&&(s&&(s.disabled=o,s.classList.toggle("btn-loading",o),s.textContent=o?"发送中…":"获取验证码"),a&&(a.disabled=o),n&&(n.readOnly=o))}function dt(){const e=document.getElementById("loginForm");e&&e.addEventListener("submit",t=>{t.preventDefault(),Te()})}async function rt(){var a;const e=document.getElementById("sendCodeBtn");if(e!=null&&e.disabled)return;const t=we(document.getElementById("phone").value);if(!t){x("loginErr","请输入手机号");return}document.getElementById("loginErr").classList.add("hidden"),U(!0,"code");try{await c(`/api/v2/auth/sms-code?phoneNumber=${encodeURIComponent(t)}`,"POST",null,!1);const s=location.hostname==="localhost"||location.hostname==="127.0.0.1"?"（本地 dev 固定 123456，可直接登录）":"";r("验证码已发送"+s,"ok"),(a=document.getElementById("code"))==null||a.focus()}catch(s){x("loginErr",s.message)}finally{U(!1,"code")}}async function Te(){var s,n;const e=document.getElementById("loginBtn");if(e!=null&&e.disabled)return;const t=we(document.getElementById("phone").value),a=it(document.getElementById("code").value);if(!t){x("loginErr","请输入手机号"),(s=document.getElementById("phone"))==null||s.focus();return}if(!a){x("loginErr","请输入验证码"),(n=document.getElementById("code"))==null||n.focus();return}document.getElementById("loginErr").classList.add("hidden"),U(!0,"login");try{const d=await c("/api/v2/auth/admin-login","POST",{phoneNumber:t,code:a},!1);C=d.token,localStorage.setItem("admin_token",C),localStorage.setItem("admin_userId",d.userId),localStorage.setItem("admin_phone",t),Le()}catch(d){x("loginErr",d.message)}finally{U(!1,"login")}}function ke(){var e;be(),C="",localStorage.removeItem("admin_token"),localStorage.removeItem("admin_userId"),localStorage.removeItem("admin_phone"),document.getElementById("appView").classList.add("hidden"),document.getElementById("loginView").classList.remove("hidden"),U(!1,"login"),U(!1,"code"),(e=document.getElementById("phone"))==null||e.focus()}function Se(){const e=document.getElementById("userInfo");if(!e)return;const t=localStorage.getItem("admin_phone")||"";e.innerHTML=t?`<span class="user-name">运营账号</span><span class="user-detail">${i(t)} · 加载角色…</span>`:'<span class="user-name">运营账号</span><span class="user-detail">加载中…</span>'}async function ct(){const e=document.getElementById("userInfo");if(e)try{const t=await c("/api/v2/ops/admin/rbac/me","GET");localStorage.setItem("admin_userId",t.userId),t.phoneNumber&&localStorage.setItem("admin_phone",t.phoneNumber);const a=t.name||"运营账号",s=t.roleNames&&t.roleNames.length?t.roleNames.join("、"):"未分配角色",n=t.permissionCount>0?` · ${t.permissionCount} 项权限`:"";e.innerHTML=`<span class="user-name">${i(a)}</span><span class="user-detail">${i(t.phoneNumber||"-")} · ${i(s)}${i(n)}</span>`}catch(t){u(t)||Se()}}function Le(){document.getElementById("loginView").classList.add("hidden"),document.getElementById("appView").classList.remove("hidden"),Se(),ut(),Promise.all([ct(),st(c)]).then(()=>{const e=Re();z(e,{replaceHash:!0,init:!0})})}const lt=12;let m=[],W=!1;function ut(){try{const e=sessionStorage.getItem("admin_visited_tabs");m=e?JSON.parse(e):["dashboard"],(!Array.isArray(m)||!m.length)&&(m=["dashboard"]),m=m.filter(t=>H[t]),m.includes("dashboard")||m.unshift("dashboard")}catch{m=["dashboard"]}}function Pe(){sessionStorage.setItem("admin_visited_tabs",JSON.stringify(m))}function se(e){if(H[e]){for(m=m.filter(t=>t!==e),m.push(e);m.length>lt;){const t=m.findIndex(a=>a!=="dashboard");if(t>=0)m.splice(t,1);else break}Pe()}}function re(){const e=document.getElementById("tagsView");if(e){if(m.length<=1){e.classList.add("hidden"),e.innerHTML="";return}e.classList.remove("hidden"),e.innerHTML=m.map(t=>{const a=t===$,s=t!=="dashboard";return`<button type="button" class="tag-item ${a?"active":""}" onclick="navigate('${b(t)}')">
      <span>${i(H[t]||t)}</span>
      ${s?`<span class="tag-close" onclick="event.stopPropagation();closeVisitedTab('${b(t)}')" title="关闭">×</span>`:""}
    </button>`}).join("")}}function ht(e){e!=="dashboard"&&(m=m.filter(t=>t!==e),Pe(),$===e?z(m[m.length-1]||"dashboard"):re())}function ce(){const e=document.getElementById("navBackBtn");e&&(e.disabled=!W)}function mt(){W&&history.back()}function Re(){const e=location.hash.match(/^#\/([a-z]+)$/),t=e?e[1]:"dashboard";return H[t]?t:"dashboard"}function z(e,t={}){H[e]||(e="dashboard");const a=!!t.fromPopstate,s="#/"+e;if(e===$&&!a&&!t.force){se(e),re();return}!a&&!t.replaceHash?(location.hash!==s&&(history.pushState({page:e},"",s),F+=1,W=F>0,ce()),se(e)):t.replaceHash&&location.hash!==s&&(history.replaceState({page:e},"",s),t.init&&se(e)),$=e,e!=="devices"&&be(),document.getElementById("pageTitle").textContent=H[e]||e,document.querySelectorAll(".nav-item").forEach(o=>{o.classList.toggle("active",o.dataset.page===e)}),re(),ce(),et(e);const n=N.opsLoaders||{};({dashboard:R,devices:X,sessions:vt,orders:$t,recharges:_t,skus:he,users:Rt,reports:Ot,audit:Nt,recent:pe,disputes:Me,sla:n.sla||R,ota:n.ota||R,risk:n.risk||R,reconciliation:n.reconciliation||R,replenishment:n.replenishment||R,rbac:n.rbac||R}[e]||R)()}function le(e){return Ke(e)}function Be(e){return Je(e)}async function R(){const e=document.getElementById("pageContent"),t="dashboard";try{const[a,s,n]=await Promise.all([c("/api/v2/ops/admin/stats","GET"),c("/api/v2/ops/admin/trend","GET"),c("/api/v2/ops/admin/audit-logs/recent?size=5&mine=false","GET").catch(()=>[])]);if($!==t)return;const d=s.last7Days||[],o=Math.max(1,...d.map(l=>l.revenueCents)),h=d.length?`
      <div class="card">
        <h3 style="margin:0 0 4px;font-size:1rem">近7日营收</h3>
        <div class="chart">${d.map(l=>{const te=Math.max(4,Math.round(l.revenueCents/o*140)),g=l.date.slice(5);return`<div class="chart-col">
            <div class="chart-val">${I(l.revenueCents)}</div>
            <div class="chart-bar" style="height:${te}px" title="${l.orderCount} 单"></div>
            <div class="chart-label">${g}<br>${l.orderCount}单</div>
          </div>`}).join("")}</div>
      </div>`:"";e.innerHTML=`
      <div class="card"><div class="filters">${v("loadDashboard()")}</div></div>
      <div class="stats">
        <div class="stat"><div class="label">设备总数</div><div class="value">${a.deviceTotal}</div></div>
        <div class="stat"><div class="label">在线设备</div><div class="value ok">${a.deviceOnline}</div></div>
        <div class="stat"><div class="label">进行中会话</div><div class="value warn">${a.sessionActive}</div></div>
        <div class="stat"><div class="label">今日会话</div><div class="value">${a.sessionToday}</div></div>
        <div class="stat"><div class="label">今日订单</div><div class="value">${a.orderToday}</div></div>
        <div class="stat"><div class="label">今日营收</div><div class="value ok">${I(a.revenueTodayCents)}</div></div>
        <div class="stat"><div class="label">累计订单</div><div class="value">${a.orderTotal}</div></div>
        <div class="stat"><div class="label">累计营收</div><div class="value">${I(a.revenueTotalCents)}</div></div>
        <div class="stat"><div class="label">待审争议</div><div class="value warn">${a.disputeOpen}</div></div>
      </div>
      ${h}
      ${n&&n.length?`
      <div class="card">
        <div class="pane-head">
          <h3 style="margin:0;font-size:1rem;color:var(--text)">最新动态</h3>
          <button class="btn-ghost btn-sm" onclick="navigate('audit')">操作日志</button>
        </div>
        ${typeof renderAuditTableHtml=="function"?renderAuditTableHtml(n):""}
      </div>`:""}
      <div class="card">
        <p class="meta">设备在线：模拟器/工控机每 30 秒上报心跳，2 分钟无心跳自动标记离线。用户余额可在「用户」页调整。</p>
      </div>`}catch(a){if($!==t)return;T(e,a)}}async function Ce(e,t){const a=await fetch(Ee+e,{headers:{Authorization:"Bearer "+C}});if(a.status===401||a.status===403)throw u({status:a.status,message:"登录已失效"}),new Error("登录已失效");if(!a.ok)throw new Error("导出失败");const s=await a.blob(),n=URL.createObjectURL(s),d=document.createElement("a");d.href=n,d.download=t,d.click(),URL.revokeObjectURL(n)}async function X(){const e=document.getElementById("pageContent"),t="devices";try{const a=await c("/api/v2/ops/admin/devices","GET");if($!==t)return;e.innerHTML=`
      <div class="card">
        <div class="filters">
          ${O("device.create","注册新设备","showDeviceForm()","btn-primary")}
          ${v("loadDevices()")}
        </div>
      </div>
      ${a.length?`
      <div class="card" style="padding:0;overflow:hidden">
        <table>
          <thead><tr>
            <th>设备ID</th><th>名称</th><th>类型</th><th>状态</th><th>活跃会话</th><th>最后心跳</th><th>操作</th>
          </tr></thead>
          <tbody>${a.map(s=>`<tr>
            <td><code>${i(s.deviceId)}</code></td>
            <td>${i(s.deviceName||"-")}</td>
            <td>${i(s.deviceType||"-")}</td>
            <td>${Be(s.onlineStatus)}</td>
            <td>${s.activeSessionId?`${i(s.activeSessionId)}<br>${le(s.activeSessionState)}`:"-"}</td>
            <td>${L(s.updatedAt)}</td>
            <td>${k("ops:device:edit")?`<button class="btn-ghost btn-sm" onclick='showDeviceForm(${JSON.stringify(s)})'>编辑</button>`:"-"}</td>
          </tr>`).join("")}</tbody>
        </table>
      </div>`:`<div class="card">${f("暂无设备","点击「注册新设备」添加第一台柜机","loadDevices()")}</div>`}`,A(),Ft()}catch(a){if($!==t)return;T(e,a)}}function pt(e){const t=!!e;document.getElementById("modalRoot").innerHTML=`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>${t?"编辑设备":"注册新设备"}</h3>
        <label>设备ID</label>
        <input id="dfId" value="${t?b(e.deviceId):""}" ${t?"disabled":""} placeholder="CAB-002">
        <label>设备名称</label>
        <input id="dfName" value="${t?b(e.deviceName||""):""}" placeholder="1号柜">
        <label>设备类型</label>
        <input id="dfType" value="${t?b(e.deviceType||"AI_CABINET_V1"):"AI_CABINET_V1"}">
        <div class="filters" style="margin-top:12px">
          <button class="btn-primary" onclick="saveDevice(${t})">保存</button>
          <button class="btn-ghost" onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`,document.getElementById("modalRoot").classList.remove("hidden")}async function bt(e){const t=document.getElementById("dfId").value.trim(),a=document.getElementById("dfName").value.trim(),s=document.getElementById("dfType").value.trim();try{e?await c("/api/v2/ops/admin/devices/"+encodeURIComponent(t),"PATCH",{deviceName:a,deviceType:s}):await c("/api/v2/ops/admin/devices","POST",{deviceId:t,deviceName:a,deviceType:s}),V(),r("保存成功","ok"),X()}catch(n){u(n)||r("保存失败: "+n.message,"err")}}function vt(){document.getElementById("pageContent").innerHTML=`
    <div class="card">
      <div class="filters">
        <div><label>设备ID</label><input id="sfDevice" value="${y.deviceId}" placeholder="CAB-001"></div>
        <div><label>状态</label>
          <select id="sfState">
            <option value="">全部</option>
            ${["CREATED","OPENING","SHOPPING","RECOGNIZING","WAITING_UPLOAD","SETTLING","COMPLETED","DISPUTED","FAILED","CANCELLED"].map(e=>`<option value="${e}" ${y.state===e?"selected":""}>${i(Ie(e))}</option>`).join("")}
          </select>
        </div>
        <div><button class="btn-primary" onclick="searchSessions()">查询</button></div>
        <div>${v("fetchSessions()")}</div>
        <div><button class="btn-ghost" onclick="exportSessionsCsv()">导出 CSV</button></div>
      </div>
      <div id="sessionTable"></div>
    </div>`,E(document.getElementById("sessionTable"),7,6),Z()}async function ft(){y.deviceId=document.getElementById("sfDevice").value.trim(),y.state=document.getElementById("sfState").value,y.page=0,Z()}async function Z(){const e=document.getElementById("sessionTable");if(e){E(e,7,6);try{const t=new URLSearchParams({page:y.page,size:y.size,...y.deviceId?{deviceId:y.deviceId}:{},...y.state?{state:y.state}:{}}),a=await c("/api/v2/ops/admin/sessions?"+t,"GET");if(!a.items.length){e.innerHTML=f("暂无会话","调整筛选条件或等待用户开门购物","fetchSessions()");return}const s=n=>!["COMPLETED","CANCELLED"].includes(n.state);e.innerHTML=`
      <table>
        <thead><tr>
          <th>会话ID</th><th>用户</th><th>设备</th><th>状态</th><th>订单</th><th>创建时间</th><th>操作</th>
        </tr></thead>
        <tbody>${a.items.map(n=>`<tr>
          <td><code>${i(n.sessionId)}</code></td>
          <td>${i(n.userId)}</td>
          <td>${i(n.deviceId)}</td>
          <td>${le(n.state)}</td>
          <td>${i(n.orderId||"-")}</td>
          <td>${L(n.createdAt)}</td>
          <td>${s(n)&&k("ops:session:cancel")?`<button class="btn-danger btn-sm" onclick="cancelSession('${b(n.sessionId)}')">取消</button>`:"-"}</td>
        </tr>`).join("")}</tbody>
      </table>
      ${q(a,"session")}`}catch(t){T(e,t,!1)}}}async function gt(){try{const e=new URLSearchParams({...y.deviceId?{deviceId:y.deviceId}:{},...y.state?{state:y.state}:{}});await Ce("/api/v2/ops/admin/sessions/export?"+e,"sessions.csv")}catch(e){u(e)||r(e.message,"err")}}async function yt(e){if(confirm("确认取消会话 "+e+"？设备将可再次开门。"))try{await c("/api/v2/ops/admin/sessions/"+e+"/cancel","POST"),r("会话已取消","ok"),Z(),$==="devices"&&X()}catch(t){u(t)||r("失败: "+t.message,"err")}}function $t(){document.getElementById("pageContent").innerHTML=`
    <div class="card">
      <div class="filters">
        <div><label>设备ID</label><input id="ofDevice" value="${S.deviceId}" placeholder="可选"></div>
        <div><button class="btn-primary" onclick="searchOrders()">查询</button></div>
        <div>${v("fetchOrders()")}</div>
        <div><button class="btn-ghost" onclick="exportOrdersCsv()">导出 CSV</button></div>
      </div>
      <div id="orderTable"></div>
    </div>`,E(document.getElementById("orderTable"),8,6),ue()}function It(){S.deviceId=document.getElementById("ofDevice").value.trim(),S.page=0,ue()}async function Et(){try{const e=new URLSearchParams(S.deviceId?{deviceId:S.deviceId}:{});await Ce("/api/v2/ops/admin/orders/export?"+e,"orders.csv")}catch(e){u(e)||r(e.message,"err")}}async function ue(){const e=document.getElementById("orderTable");if(e){E(e,8,6);try{const t=new URLSearchParams({page:S.page,size:S.size,...S.deviceId?{deviceId:S.deviceId}:{}}),a=await c("/api/v2/ops/admin/orders?"+t,"GET");if(!a.items.length){e.innerHTML=f("暂无订单","完成购物后会在此展示订单记录","fetchOrders()");return}e.innerHTML=`
      <table>
        <thead><tr>
          <th>订单ID</th><th>会话</th><th>用户</th><th>设备</th><th>金额</th><th>商品行</th><th>时间</th><th>操作</th>
        </tr></thead>
        <tbody>${a.items.map(s=>`<tr>
          <td><code>${i(s.orderId)}</code></td>
          <td>${i(s.sessionId)}</td>
          <td>${i(s.userId)}</td>
          <td>${i(s.deviceId)}</td>
          <td>${I(s.totalAmountCents)}</td>
          <td>${i(s.lineCount)}</td>
          <td>${L(s.createdAt)}</td>
          <td><button class="btn-ghost btn-sm" onclick="showOrderDetail('${b(s.orderId)}')">详情</button></td>
        </tr>`).join("")}</tbody>
      </table>
      ${q(a,"order")}`}catch(t){T(e,t,!1)}}}function q(e,t){const a=Math.max(1,Math.ceil(e.total/e.size)),s=e.page+1;return`<div class="pagination">
    共 ${e.total} 条，第 ${s}/${a} 页
    <button class="btn-ghost btn-sm" ${e.page<=0?"disabled":""} onclick="changePage('${t}', ${e.page-1})">上一页</button>
    <button class="btn-ghost btn-sm" ${s>=a?"disabled":""} onclick="changePage('${t}', ${e.page+1})">下一页</button>
  </div>`}function wt(e,t){e==="session"?(y.page=Math.max(0,t),Z()):e==="user"?(B.page=Math.max(0,t),Y()):e==="audit"?(de.page=Math.max(0,t),me()):e==="recharge"?(w.page=Math.max(0,t),Q()):(S.page=Math.max(0,t),ue())}async function Tt(e){try{const t=await c("/api/v2/ops/admin/orders/"+e,"GET"),a=(t.lines||[]).map(s=>`<tr><td>${i(s.skuName)}</td><td>${i(s.skuId)}</td><td>${i(s.quantity)}</td><td>${I(s.unitPriceCents)}</td><td>${I(s.lineAmountCents)}</td></tr>`).join("");document.getElementById("modalRoot").innerHTML=`
      <div class="modal-backdrop" onclick="closeModal(event)">
        <div class="modal" onclick="event.stopPropagation()">
          <h3>订单 ${i(t.orderId)}</h3>
          <div class="meta">会话 ${i(t.sessionId)} · 设备 ${i(t.deviceId)} · 用户 ${i(t.userId)}</div>
          <table style="margin-top:12px">
            <thead><tr><th>商品</th><th>SKU</th><th>数量</th><th>单价</th><th>小计</th></tr></thead>
            <tbody>${a}</tbody>
          </table>
          <p style="margin-top:12px;font-weight:700">合计 ${I(t.totalAmountCents)}</p>
          <button class="btn-ghost" onclick="closeModal()">关闭</button>
        </div>
      </div>`,document.getElementById("modalRoot").classList.remove("hidden")}catch(t){u(t)||r("加载失败: "+t.message,"err")}}function V(e){e&&e.target!==e.currentTarget||(document.getElementById("modalRoot").classList.add("hidden"),document.getElementById("modalRoot").innerHTML="")}async function kt(){try{J=await c("/api/v2/ops/admin/skus","GET")}catch{J=[{skuId:"SKU-DEMO-001",skuName:"演示商品",priceCents:350}]}}function he(){document.getElementById("pageContent").innerHTML=`
    <div class="card">
      <div class="filters">
        ${O("sku.edit","新增商品","showSkuForm()","btn-primary")}
        ${v("loadSkusPage()")}
      </div>
      <div id="skuTable"></div>
    </div>`,E(document.getElementById("skuTable"),4,5),St()}async function St(){const e=document.getElementById("skuTable");if(e){E(e,4,5);try{const t=await c("/api/v2/ops/admin/skus","GET");if(J=t,!t.length){e.innerHTML=f("暂无商品","添加 SKU 后可在争议审核中选择商品","fetchSkusTable()");return}e.innerHTML=`
      <table>
        <thead><tr><th>SKU ID</th><th>名称</th><th>价格</th><th>操作</th></tr></thead>
        <tbody>${t.map(a=>`<tr>
          <td><code>${i(a.skuId)}</code></td>
          <td>${i(a.skuName)}</td>
          <td>${I(a.priceCents)}</td>
          <td>${k("ops:sku:edit")?`<button class="btn-ghost btn-sm" onclick='showSkuForm(${JSON.stringify(a)})'>编辑</button>`:"-"}</td>
        </tr>`).join("")}</tbody>
      </table>`}catch(t){T(e,t,!1)}}}function Lt(e){const t=!!e;document.getElementById("modalRoot").innerHTML=`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>${t?"编辑商品":"新增商品"}</h3>
        <label>SKU ID</label>
        <input id="skuId" value="${t?b(e.skuId):""}" ${t?"disabled":""} placeholder="SKU-XXX-001">
        <label>商品名称</label>
        <input id="skuName" value="${t?b(e.skuName):""}" placeholder="可乐 330ml">
        <label>价格（分）</label>
        <input id="skuPrice" type="number" min="1" value="${t?e.priceCents:350}">
        <p class="meta">价格单位：分（350 = ¥3.50）</p>
        <div class="filters" style="margin-top:12px">
          <button class="btn-primary" onclick="saveSku(${t})">保存</button>
          <button class="btn-ghost" onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`,document.getElementById("modalRoot").classList.remove("hidden")}async function Pt(e){const t=document.getElementById("skuId").value.trim(),a=document.getElementById("skuName").value.trim(),s=parseInt(document.getElementById("skuPrice").value,10);if(!t||!a||!s){r("请填写完整","err");return}try{const n={skuId:t,skuName:a,priceCents:s};e?await c("/api/v2/ops/admin/skus/"+encodeURIComponent(t),"PUT",n):await c("/api/v2/ops/admin/skus","POST",n),V(),r("保存成功","ok"),he()}catch(n){u(n)||r("保存失败: "+n.message,"err")}}function Rt(){document.getElementById("pageContent").innerHTML=`
    <div class="card">
      <div class="filters">
        <div><label>手机号</label><input id="ufPhone" value="${B.phone}" placeholder="138"></div>
        <div><button class="btn-primary" onclick="searchUsers()">查询</button></div>
        <div>${v("fetchUsers()")}</div>
      </div>
      <div id="userTable"></div>
    </div>`,E(document.getElementById("userTable"),8,6),Y()}function Bt(){B.phone=document.getElementById("ufPhone").value.trim(),B.page=0,Y()}async function Y(){const e=document.getElementById("userTable");if(e){E(e,8,6);try{const t=new URLSearchParams({page:B.page,size:B.size,...B.phone?{phone:B.phone}:{}}),a=await c("/api/v2/ops/admin/users?"+t,"GET");if(!a.items.length){e.innerHTML=f("暂无用户","消费者通过小程序注册后会出现在此列表","fetchUsers()");return}e.innerHTML=`
      <table>
        <thead><tr>
          <th>userId</th><th>手机号</th><th>姓名</th><th>角色</th><th>实名</th><th>余额</th><th>注册时间</th><th>操作</th>
        </tr></thead>
        <tbody>${a.items.map(s=>`<tr>
          <td>${i(s.userId)}</td>
          <td>${i(s.phoneNumber)}</td>
          <td>${i(s.name||"-")}</td>
          <td>${s.role==="OPERATOR"?'<span class="badge badge-active">运营</span>':"消费者"}</td>
          <td>${s.verified?"是":"否"}</td>
          <td>${I(s.balanceCents)}</td>
          <td>${L(s.createdAt)}</td>
          <td>${s.role==="OPERATOR"?k("ops:rbac:assign")?`<button class="btn-ghost btn-sm" onclick="showRbacAssignForUser(${s.userId})">分配角色</button>`:"-":k("ops:user:balance")?`<button class="btn-ghost btn-sm" onclick="showBalanceForm(${s.userId}, ${s.balanceCents})">调余额</button>`:"-"}</td>
        </tr>`).join("")}</tbody>
      </table>
      ${q(a,"user")}`}catch(t){T(e,t,!1)}}}function Ct(e,t){document.getElementById("modalRoot").innerHTML=`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>调整余额 · userId ${e}</h3>
        <p class="meta">当前余额 ${I(t)}</p>
        <label>变动金额（分，正数充值/负数扣减）</label>
        <input id="deltaCents" type="number" value="1000" placeholder="1000 = 加10元">
        <p class="meta">例：1000 表示加 ¥10.00；-350 表示扣 ¥3.50</p>
        <div class="filters" style="margin-top:12px">
          <button class="btn-primary" onclick="saveBalance(${e})">确认</button>
          <button class="btn-ghost" onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`,document.getElementById("modalRoot").classList.remove("hidden")}async function At(e){const t=parseInt(document.getElementById("deltaCents").value,10);if(isNaN(t)||t===0){r("请输入有效金额","err");return}try{await c("/api/v2/ops/admin/users/"+e+"/balance","POST",{deltaCents:t}),V(),Y(),r("余额已更新","ok")}catch(a){u(a)||r("失败: "+a.message,"err")}}function Mt(e){typeof openRbacUserAssign=="function"?openRbacUserAssign(e):z("rbac")}async function Ot(){const e=document.getElementById("pageContent"),t="reports";try{const a=await c("/api/v2/ops/admin/reports/devices","GET");if($!==t)return;if(e.innerHTML=`
      <div class="card">
        <div class="filters">${v("loadReportsPage()")}</div>
      </div>`,!a.length){e.innerHTML+=`<div class="card">${f("暂无设备报表","注册设备并产生订单后自动生成统计","loadReportsPage()")}</div>`;return}e.innerHTML+=`
      <div class="card" style="padding:0;overflow:hidden">
        <table>
          <thead><tr>
            <th>设备</th><th>状态</th><th>累计订单</th><th>累计营收</th>
            <th>今日订单</th><th>今日营收</th><th>累计会话</th><th>进行中</th>
          </tr></thead>
          <tbody>${a.map(s=>`<tr>
            <td><code>${i(s.deviceId)}</code><br><span class="meta">${i(s.deviceName||"-")}</span></td>
            <td>${Be(s.onlineStatus)}</td>
            <td>${s.orderTotal}</td>
            <td>${I(s.revenueTotalCents)}</td>
            <td>${s.orderToday}</td>
            <td>${I(s.revenueTodayCents)}</td>
            <td>${s.sessionTotal}</td>
            <td>${s.sessionActive?'<span class="badge badge-active">是</span>':"-"}</td>
          </tr>`).join("")}</tbody>
        </table>
      </div>`}catch(a){if($!==t)return;T(e,a)}}function Nt(){document.getElementById("pageContent").innerHTML=`
    <div class="card">
      <div class="filters">
        ${v("fetchAuditLogs()")}
      </div>
      <div id="auditTable"></div>
    </div>`,E(document.getElementById("auditTable"),5,6),me()}async function me(){const e=document.getElementById("auditTable");if(e){E(e,5,6);try{const t=new URLSearchParams({page:de.page,size:de.size}),a=await c("/api/v2/ops/admin/audit-logs?"+t,"GET");if(!a.items.length){e.innerHTML=f("暂无操作记录","运营人员的敏感操作会记录在此","fetchAuditLogs()");return}const s=a.items.map(n=>`<tr>
      <td>${L(n.createdAt)}</td>
      <td>${typeof formatOperatorCell=="function"?formatOperatorCell(n):i(n.operatorId)}</td>
      <td><code>${i(n.action)}</code></td>
      <td>${i(n.targetType||"-")} ${i(n.targetId||"")}</td>
      <td class="meta">${i(n.detail||"-")}</td>
    </tr>`).join("");e.innerHTML=`
      <table>
        <thead><tr>
          <th>时间</th><th>操作人</th><th>动作</th><th>对象</th><th>详情</th>
        </tr></thead>
        <tbody>${s}</tbody>
      </table>
      ${q(a,"audit")}`}catch(t){T(e,t,!1)}}}function pe(){document.getElementById("pageContent").innerHTML=`
    <div class="card">
      <div class="filters">
        <button class="btn-ghost btn-sm ${j.mine?"":"active-tab"}" onclick="setRecentScope(false)">全部操作</button>
        <button class="btn-ghost btn-sm ${j.mine?"active-tab":""}" onclick="setRecentScope(true)">我的操作</button>
        ${v("fetchRecentLogs()")}
        <button class="btn-ghost btn-sm" onclick="navigate('audit')">完整操作日志</button>
      </div>
      <div id="recentTable"></div>
    </div>`,Ae()}function Dt(e){j.mine=e,pe()}async function Ae(){const e=document.getElementById("recentTable");if(e){e.innerHTML='<p class="sub">加载中…</p>';try{const t=new URLSearchParams({size:j.size,mine:j.mine?"true":"false"}),a=await c("/api/v2/ops/admin/audit-logs/recent?"+t,"GET");e.innerHTML=typeof renderAuditTableHtml=="function"?renderAuditTableHtml(a):f("暂无操作记录","运营后台的敏感操作会记录在此","fetchRecentLogs()")}catch(t){T(e,t,!1)}}}async function Me(){const e=document.getElementById("pageContent"),t="disputes";if(await kt(),$===t)try{const a=await c("/api/v2/ops/disputes","GET");if($!==t)return;if(!a||!a.length){e.innerHTML=`
        <div class="card">
          <div class="filters">${v("loadDisputes()")}</div>
        </div>
        <div class="card">${f("暂无待审核工单","识别异常或用户申诉的工单会出现在此","loadDisputes()")}</div>`;return}e.innerHTML=`
      <div class="card">
        <div class="filters">${v("loadDisputes()")}</div>
      </div>
      <div class="card">${a.map(Ht).join("")}</div>`}catch(a){if($!==t)return;T(e,a)}}function Ht(e){const t=J.map(s=>`<option value="${b(s.skuId)}">${i(s.skuName)} (${i(s.skuId)}) ${I(s.priceCents)}</option>`).join(""),a=e.videoPreviewUrl?`<br><a href="${b(e.videoPreviewUrl)}" target="_blank" rel="noopener">预览购物视频</a>`:e.videoUri?`<br>视频 ${i(e.videoUri)}`:"";return`<div class="ticket">
    <div>${le(e.status)}</div>
    <div class="meta">工单 ${i(e.ticketId)}<br>会话 ${i(e.sessionId)}<br>原因 ${i(e.reason||"-")}<br>创建 ${L(e.createdAt)}${a}</div>
    <div class="filters" style="margin-top:12px">
      <div style="flex:2"><label>商品</label><select class="sku-select">${t}</select></div>
      <div><label>数量</label><input type="number" class="qty-input" value="1" min="1"></div>
      <div><button class="btn-ok" onclick="resolveTicket('${b(e.ticketId)}', this)">审核结案</button></div>
    </div>
  </div>`}async function Ut(e,t){const a=t.closest(".ticket"),s=a.querySelector(".sku-select").value,n=parseInt(a.querySelector(".qty-input").value,10)||1;if(confirm(`确认结案：${s} × ${n}？`)){t.disabled=!0;try{await c(`/api/v2/ops/disputes/${e}/resolve`,"POST",{items:[{skuId:s,quantity:n}]}),r("已结案","ok"),Me()}catch(d){u(d)||r("失败: "+d.message,"err"),t.disabled=!1}}}function _t(){document.getElementById("pageContent").innerHTML=`
    <div class="card">
      <div class="filters">
        <div><label>状态</label>
          <select id="rfStatus">
            <option value="">全部</option>
            ${["PENDING","PAID","REFUNDED","CANCELLED"].map(e=>`<option value="${e}" ${w.status===e?"selected":""}>${e}</option>`).join("")}
          </select>
        </div>
        <div><label>用户ID</label><input id="rfUserId" value="${b(w.userId)}" placeholder="可选"></div>
        <div><button class="btn-primary" onclick="searchRecharges()">查询</button></div>
        <div>${v("fetchRecharges()")}</div>
      </div>
      <div id="rechargeTable"></div>
    </div>`,E(document.getElementById("rechargeTable"),10,6),Q()}function Gt(){w.status=document.getElementById("rfStatus").value,w.userId=document.getElementById("rfUserId").value.trim(),w.page=0,Q()}async function Q(){const e=document.getElementById("rechargeTable");if(e){E(e,10,6);try{const t=new URLSearchParams({page:w.page,size:w.size,...w.status?{status:w.status}:{},...w.userId?{userId:w.userId}:{}}),a=await c("/api/v2/ops/admin/recharges?"+t,"GET");if(!a.items.length){e.innerHTML=f("暂无充值订单","用户小程序充值成功后会出现在此列表","fetchRecharges()");return}const s=k("ops:user:balance");e.innerHTML=`
      <table>
        <thead><tr>
          <th>订单号</th><th>用户</th><th>金额</th><th>渠道</th><th>状态</th>
          <th>微信单号</th><th>创建</th><th>支付</th><th>退款</th><th>操作</th>
        </tr></thead>
        <tbody>${a.items.map(n=>`<tr>
          <td><code>${i(n.orderId)}</code></td>
          <td>${i(n.userId)}</td>
          <td>${I(n.amountCents)}</td>
          <td>${i(n.channel||"-")}</td>
          <td>${qe(n.status)}</td>
          <td class="meta">${i(n.wxTransactionId||"-")}</td>
          <td>${L(n.createdAt)}</td>
          <td>${L(n.paidAt)}</td>
          <td>${L(n.refundedAt)}</td>
          <td>${n.status==="PAID"&&s?`<button class="btn-danger btn-sm" onclick="refundRecharge('${b(n.orderId)}', ${n.amountCents})">退款</button>`:"-"}</td>
        </tr>`).join("")}</tbody>
      </table>
      ${q(a,"recharge")}`}catch(t){T(e,t,!1)}}}async function xt(e,t){const a=prompt(`确认退款订单 ${e}（${I(t)}）？
可选填写退款原因：`,"");if(a!==null)try{await c("/api/v2/ops/admin/recharge/"+encodeURIComponent(e)+"/refund","POST",a.trim()?{reason:a.trim()}:{}),r("退款成功","ok"),Q()}catch(s){r("退款失败: "+s.message,"err")}}ot();dt();Object.assign(N,{api:c,getCurrentPage:()=>$,fmtTime:L,fmtMoney:I,closeModal:V});C&&c("/api/v2/ops/admin/rbac/me","GET").then(()=>Le()).catch(e=>{u(e)||ke()});let F=0,K=null;function be(){K&&(clearInterval(K),K=null)}function Ft(){be(),K=setInterval(()=>{$==="devices"&&X()},3e4)}window.addEventListener("popstate",()=>{if(!C||document.getElementById("appView").classList.contains("hidden"))return;F=Math.max(0,F-1),W=F>0,ce();const e=Re();z(e,{fromPopstate:!0})});Object.assign(window,{sendCode:rt,login:Te,logout:ke,navigate:z,navigateBack:mt,closeVisitedTab:ht,loadDashboard:R,showDeviceForm:pt,saveDevice:bt,searchSessions:ft,exportSessionsCsv:gt,cancelSession:yt,searchOrders:It,exportOrdersCsv:Et,showOrderDetail:Tt,changePage:wt,closeModal:V,loadSkusPage:he,showSkuForm:Lt,saveSku:Pt,searchUsers:Bt,showBalanceForm:Ct,saveBalance:At,showRbacAssignForUser:Mt,fetchAuditLogs:me,fetchRecentLogs:Ae,setRecentScope:Dt,loadRecentPage:pe,resolveTicket:Ut,searchRecharges:Gt,refundRecharge:xt});const p=(...e)=>N.api(...e),P=e=>N.getCurrentPage()===e,_=e=>N.fmtTime(e),M=e=>N.fmtMoney(e);function G(e,t){T(e,t,!0)}async function Oe(){const e=document.getElementById("pageContent"),t="sla";try{const a=await p("/api/v2/ops/admin/sla","GET");if(!P(t))return;const s=a.realtime||{};e.innerHTML=`
      <div class="card"><div class="filters">${v("loadSlaPage()")}</div></div>
      <div class="cards">
        <div class="card"><div class="card-label">24h 开门成功率</div><div class="card-value">${ne(s.doorSuccessRate24h)}</div></div>
        <div class="card"><div class="card-label">24h 平均识别耗时</div><div class="card-value">${i(s.avgRecognizeMs24h||0)} ms</div></div>
        <div class="card"><div class="card-label">当前设备在线率</div><div class="card-value">${ne(s.deviceOnlineRateNow)}</div></div>
      </div>
      <h3>日快照 ${i(a.snapshotDate||"-")}</h3>
      <table class="table"><thead><tr>
        <th>开门尝试</th><th>成功</th><th>成功率</th><th>识别均耗</th><th>P95</th><th>设备数</th><th>在线峰值</th>
      </tr></thead><tbody><tr>
        <td>${i(a.doorOpenAttempts??0)}</td><td>${i(a.doorOpenSuccess??0)}</td><td>${ne(a.doorSuccessRate)}</td>
        <td>${i(a.avgRecognizeMs??0)} ms</td><td>${i(a.p95RecognizeMs??0)} ms</td>
        <td>${i(a.deviceTotal??0)}</td><td>${i(a.deviceOnlinePeak??0)}</td>
      </tr></tbody></table>`}catch(a){if(!P(t))return;G(e,a)}}async function ve(){const e=document.getElementById("pageContent"),t="ota";try{const a=await p("/api/v2/ops/admin/ota/releases","GET");if(!P(t))return;const s=(a||[]).map(n=>`<tr>
      <td>${i(n.appVersion)}</td><td>${i(n.channel)}</td><td>${n.mandatory?"是":"否"}</td>
      <td>${i(n.grayPercent??100)}%</td><td>${i(n.status)}</td><td>${_(n.publishedAt)}</td>
      <td>${n.downloadUrl?`<a href="${b(n.downloadUrl)}" target="_blank" rel="noopener">下载</a>`:i(n.objectStorageUri||"-")}</td>
    </tr>`).join("");e.innerHTML=`
      <div class="filters">
        ${O("ota.publish","发布新版本","showOtaPublishForm()","btn-primary btn-sm")}
        ${v("loadOtaPage()")}
      </div>
      <div id="otaPublishForm" class="hidden card" style="margin:12px 0;padding:12px">
        <label>版本号</label><input id="otaVersion" placeholder="1.2.0">
        <label>渠道</label><input id="otaChannel" value="STABLE">
        <label>灰度比例 (0-100)</label><input id="otaGray" type="number" value="100" min="0" max="100">
        <label>对象存储 URI (MinIO/OSS)</label><input id="otaUri" placeholder="s3://cabinet-videos/ota/app-1.2.0.apk">
        <label>下载 URL（可选，无 URI 时填写）</label><input id="otaUrl" placeholder="https://...">
        <label><input type="checkbox" id="otaMandatory"> 强制升级</label>
        <button class="btn-primary btn-sm" onclick="publishOta()">提交发布</button>
      </div>
      ${a&&a.length?`<table class="table"><thead><tr>
        <th>版本</th><th>渠道</th><th>强制</th><th>灰度</th><th>状态</th><th>发布时间</th><th>包</th>
      </tr></thead><tbody>${s}</tbody></table>`:f("暂无 OTA 发布","发布柜机 APK 后设备可检查更新","loadOtaPage()")}
      <p class="sub">柜机检查更新：GET /internal/v1/devices/{id}/ota/check?currentVersion=…</p>`,A()}catch(a){if(!P(t))return;G(e,a)}}function jt(){document.getElementById("otaPublishForm").classList.toggle("hidden")}async function zt(){const e={appVersion:document.getElementById("otaVersion").value.trim(),channel:document.getElementById("otaChannel").value.trim()||"STABLE",mandatory:document.getElementById("otaMandatory").checked,grayPercent:parseInt(document.getElementById("otaGray").value,10)||100,objectStorageUri:document.getElementById("otaUri").value.trim()||null,downloadUrl:document.getElementById("otaUrl").value.trim()||null,status:"PUBLISHED"};if(!e.appVersion){r("请填写版本号","err");return}try{await p("/api/v2/ops/admin/ota/releases","POST",e),r("已发布","ok"),ve()}catch(t){u(t)||r("发布失败: "+t.message,"err")}}async function Ne(){const e=document.getElementById("pageContent"),t="risk";try{const[a,s]=await Promise.all([p("/api/v2/ops/admin/risk/events?page=0&size=20","GET"),p("/api/v2/ops/admin/risk/blacklist","GET")]);if(!P(t))return;const n=(a.items||[]).map(o=>`<tr>
      <td>${_(o.createdAt)}</td><td>${i(o.eventType)}</td><td>${i(o.severity)}</td>
      <td>${i(o.userId||"-")}</td><td>${i(o.deviceId||"-")}</td><td>${i(o.detail||"")}</td>
    </tr>`).join(""),d=(s||[]).map(o=>`<tr>
      <td>${i(o.userId)}</td><td>${i(o.reason)}</td><td>${i(o.source)}</td><td>${_(o.expiresAt)}</td>
    </tr>`).join("");e.innerHTML=`
      <div class="card"><div class="filters">${v("loadRiskPage()")}</div></div>
      <h3>风控事件</h3>
      ${(a.items||[]).length?`<table class="table"><thead><tr><th>时间</th><th>类型</th><th>级别</th><th>用户</th><th>设备</th><th>详情</th></tr></thead>
      <tbody>${n}</tbody></table>`:f("暂无风控事件","触发风控规则后会在此展示","loadRiskPage()")}
      <h3>黑名单</h3>
      ${(s||[]).length?`<table class="table"><thead><tr><th>用户</th><th>原因</th><th>来源</th><th>过期</th></tr></thead>
      <tbody>${d}</tbody></table>`:f("暂无黑名单用户","手动拉黑或自动风控命中后会出现在此","loadRiskPage()")}`}catch(a){if(!P(t))return;G(e,a)}}async function De(){const e=document.getElementById("pageContent"),t=new Date().toISOString().slice(0,10),a=new Date(Date.now()-30*864e5).toISOString().slice(0,10);e.innerHTML=`
    <div class="filters">
      <div><label>开始</label><input id="reconFrom" type="date" value="${a}"></div>
      <div><label>结束</label><input id="reconTo" type="date" value="${t}"></div>
      <div><label>渠道</label>
        <select id="reconChannel"><option value="WECHAT">微信</option><option value="ALIPAY">支付宝</option><option value="MOCK">Mock</option></select>
      </div>
      <div><button class="btn-ghost btn-sm" onclick="fetchReconciliationList()">查询</button></div>
      <div>${v("fetchReconciliationList()")}</div>
      ${O("recon.run","执行对账","runReconToday()","btn-primary btn-sm")}
    </div>
    <div id="reconTable"></div>`,A(),E(document.getElementById("reconTable"),8,6),fe()}async function fe(){var t,a;const e=document.getElementById("reconTable");if(e){E(e,8,6);try{const s=(t=document.getElementById("reconFrom"))==null?void 0:t.value,n=(a=document.getElementById("reconTo"))==null?void 0:a.value,d=new URLSearchParams;s&&d.set("from",s),n&&d.set("to",n);const o=await p("/api/v2/ops/admin/reconciliation?"+d,"GET");if(!o||!o.length){e.innerHTML=f("暂无对账记录","选择日期范围后查询，或执行对账任务","fetchReconciliationList()");return}const h=(o||[]).map(l=>`<tr style="cursor:pointer" onclick="showReconDetail(${i(l.reconId)})">
      <td>${i(l.reconDate)}</td><td>${i(l.channel)}</td>
      <td>${M(l.platformTotal)}</td><td>${M(l.ledgerTotal)}</td>
      <td>${M(l.diffCents)}</td>
      <td>${i(l.matchedCount??0)}/${i(l.unmatchedCount??0)}</td>
      <td>${i(l.status)}</td><td>${_(l.completedAt)}</td>
    </tr>`).join("");e.innerHTML=`
      <table class="table"><thead><tr>
        <th>日期</th><th>渠道</th><th>平台总额</th><th>账本总额</th><th>差额</th>
        <th>匹配/未匹配</th><th>状态</th><th>完成时间</th>
      </tr></thead><tbody>${h||'<tr><td colspan="8">暂无记录</td></tr>'}</tbody></table>
      <p class="sub">点击行查看明细</p>`}catch(s){G(e,s)}}}async function qt(){var a;const e=new Date().toISOString().slice(0,10),t=((a=document.getElementById("reconChannel"))==null?void 0:a.value)||"WECHAT";try{await p(`/api/v2/ops/admin/reconciliation/run?date=${e}&channel=${t}`,"POST"),r("对账任务已提交","ok"),fe()}catch(s){u(s)||r("对账失败: "+s.message,"err")}}async function Vt(e){try{const t=await p("/api/v2/ops/admin/reconciliation/"+e,"GET"),a=t.summary,s=t.lines||[],n=s.filter(o=>!o.matched),d=s.slice(0,100).map(o=>`<tr class="${o.matched?"":"err"}">
      <td>${i(o.platformTradeNo)}</td><td>${i(o.merchantOrderNo||"-")}</td>
      <td>${M(o.amountCents)}</td><td>${i(o.tradeType||"-")}</td>
      <td>${o.matched?"✓":"✗"}</td><td>${_(o.tradeTime)}</td>
    </tr>`).join("");document.getElementById("modalRoot").innerHTML=`
      <div class="modal-backdrop" onclick="closeModal(event)">
        <div class="modal modal-wide" onclick="event.stopPropagation()">
          <h3>对账明细 #${i(e)} · ${i(a.reconDate)} · ${i(a.channel)}</h3>
          <p class="meta">平台 ${M(a.platformTotal)} / 账本 ${M(a.ledgerTotal)} / 差额 ${M(a.diffCents)} · ${i(a.status)}</p>
          ${n.length?`<p class="err">未匹配 ${i(n.length)} 笔</p>`:""}
          <table class="table"><thead><tr>
            <th>平台流水</th><th>商户单号</th><th>金额</th><th>类型</th><th>匹配</th><th>时间</th>
          </tr></thead><tbody>${d||'<tr><td colspan="6">无明细</td></tr>'}</tbody></table>
          ${s.length>100?'<p class="sub">仅显示前 100 条</p>':""}
          <button class="btn-ghost" onclick="closeModal()">关闭</button>
        </div>
      </div>`,document.getElementById("modalRoot").classList.remove("hidden")}catch(t){u(t)||r("加载失败: "+t.message,"err")}}async function ge(){const e=document.getElementById("pageContent"),t="replenishment";try{const[a,s]=await Promise.all([p("/api/v2/ops/admin/replenishment/routes","GET"),p("/api/v2/ops/admin/inventory","GET")]);if(!P(t))return;const n=(a||[]).map(o=>`<tr>
      <td>${i(o.routeName)}</td><td>${i(o.plannedDate)}</td><td>${i(o.status)}</td>
      <td>${i((o.tasks||[]).length)}</td><td>${i(o.assigneeUserId||"-")}</td>
    </tr>`).join(""),d=(s||[]).map(o=>`<tr>
      <td>${i(o.deviceId)}</td><td>${i(o.skuId)}</td><td>${i(o.quantity)}/${i(o.capacity)}</td>
      <td>${i(o.lowThreshold)}</td>
    </tr>`).join("");e.innerHTML=`
      <div class="filters">
        ${O("replenish.plan","规划路线","planReplenishmentRoute()","btn-primary btn-sm")}
        ${v("loadReplenishmentPage()")}
      </div>
      <h3>补货路线</h3>
      ${(a||[]).length?`<table class="table"><thead><tr><th>名称</th><th>计划日</th><th>状态</th><th>任务数</th><th>负责人</th></tr></thead>
      <tbody>${n}</tbody></table>`:f("暂无补货路线","点击「规划路线」创建补货任务","loadReplenishmentPage()")}
      <h3>柜内库存</h3>
      ${(s||[]).length?`<table class="table"><thead><tr><th>设备</th><th>SKU</th><th>库存/容量</th><th>低库存阈值</th></tr></thead>
      <tbody>${d}</tbody></table>`:f("暂无库存数据","设备上报或运营录入库存后会显示","loadReplenishmentPage()")}
      <p class="sub">补货员 App：GET /api/v2/ops/admin/replenishment/my-tasks</p>`,A()}catch(a){if(!P(t))return;G(e,a)}}async function Kt(){const e=prompt("路线名称","补货路线-"+new Date().toISOString().slice(0,10));if(!e)return;const t=prompt("设备 ID 列表（逗号分隔）","CAB-001,CAB-002");if(!t)return;const a=t.split(",").map(h=>h.trim()).filter(Boolean),s=parseInt(prompt("负责人 userId",localStorage.getItem("admin_userId")||"100000001"),10),n=new Date().toISOString().slice(0,10),d=parseFloat(prompt("起点纬度（可选）","31.23")||"31.23"),o=parseFloat(prompt("起点经度（可选）","121.47")||"121.47");try{await p("/api/v2/ops/admin/replenishment/plan","POST",{routeName:e,assigneeUserId:s,plannedDate:n,deviceIds:a,startLatitude:d,startLongitude:o}),r("路线已规划","ok"),ge()}catch(h){u(h)||r("规划失败: "+h.message,"err")}}async function He(){var a,s,n,d,o,h;const e=document.getElementById("pageContent"),t="rbac";try{const[l,te,g]=await Promise.all([p("/api/v2/ops/admin/rbac/roles","GET"),p("/api/v2/ops/admin/rbac/permissions","GET"),p("/api/v2/ops/admin/rbac/me","GET")]);if(!P(t))return;window._rbacState={tab:((a=window._rbacState)==null?void 0:a.tab)||"roles",selectedRoleId:((s=window._rbacState)==null?void 0:s.selectedRoleId)||(((n=l[0])==null?void 0:n.roleId)??null),selectedUserId:((d=window._rbacState)==null?void 0:d.selectedUserId)||null,roles:l||[],perms:te||[],rolePermIds:new Set,operatorFilters:((o=window._rbacState)==null?void 0:o.operatorFilters)||{page:0,size:20,phone:""},recentScope:((h=window._rbacState)==null?void 0:h.recentScope)||"all"},window._rbacRoles=l||[];const je=((g==null?void 0:g.roleNames)||[]).join("、")||"未分配";e.innerHTML=`
      <div class="card rbac-profile">
        <div class="rbac-profile-main">
          <strong>${i((g==null?void 0:g.name)||(g==null?void 0:g.phoneNumber)||"运营账号")}</strong>
          <span class="sub">${i((g==null?void 0:g.phoneNumber)||"")}</span>
        </div>
        <div class="rbac-profile-meta">
          <span>角色：${i(je)}</span>
          <span>权限项：${i((g==null?void 0:g.permissionCount)??0)}</span>
        </div>
        <div class="filters">${v("loadRbacPage()")}</div>
      </div>
      <div class="tabs rbac-tabs">
        <button type="button" class="tab ${window._rbacState.tab==="roles"?"active":""}" onclick="switchRbacTab('roles')">角色权限</button>
        ${k("ops:rbac:assign")?`<button type="button" class="tab ${window._rbacState.tab==="users"?"active":""}" onclick="switchRbacTab('users')">用户授权</button>`:""}
      </div>
      <div id="rbacPanel"></div>`,A(),await ye()}catch(l){if(!P(t))return;G(e,l)}}function Jt(e){window._rbacState&&(window._rbacState.tab=e,document.querySelectorAll(".rbac-tabs .tab").forEach(t=>{t.classList.toggle("active",t.textContent.includes(e==="roles"?"角色":e==="users"?"用户":"最近"))}),ye())}async function ye(){const e=document.getElementById("rbacPanel");if(!e||!window._rbacState)return;const{tab:t}=window._rbacState;t==="roles"?(e.innerHTML=`
      <div class="rbac-split">
        <div class="card rbac-pane">
          <h3 class="pane-title">角色列表</h3>
          <table class="table rbac-role-table">
            <thead><tr><th>角色</th><th>标识</th><th>权限</th></tr></thead>
            <tbody>${(window._rbacState.roles||[]).map(a=>`
              <tr class="rbac-role-row ${window._rbacState.selectedRoleId===a.roleId?"selected":""}"
                  onclick="selectRbacRole(${a.roleId})">
                <td>${i(a.roleName)}</td>
                <td><code>${i(a.roleKey)}</code></td>
                <td class="meta">${i((a.permissions||[])[0]||"-")}</td>
              </tr>`).join("")}</tbody>
          </table>
        </div>
        <div class="card rbac-pane" id="rbacPermPane">
          <p class="sub">选择左侧角色以配置菜单权限</p>
        </div>
      </div>`,window._rbacState.selectedRoleId&&await $e(window._rbacState.selectedRoleId)):t==="users"&&(e.innerHTML=`
      <div class="rbac-split">
        <div class="card rbac-pane">
          <h3 class="pane-title">运营账号</h3>
          <div class="filters">
            <div><label>手机号</label>
              <input id="rbacOpPhone" placeholder="搜索手机号" value="${b(window._rbacState.operatorFilters.phone)}"></div>
            <button class="btn-primary btn-sm" onclick="searchRbacOperators()">搜索</button>
          </div>
          <div id="rbacOperatorList"></div>
        </div>
        <div class="card rbac-pane" id="rbacUserRolePane">
          <p class="sub">选择左侧运营账号分配角色</p>
        </div>
      </div>`,await ee())}function Wt(e){const t=new Map,a=[];(e||[]).forEach(n=>t.set(n.permissionId,{...n,children:[]})),(e||[]).forEach(n=>{const d=t.get(n.permissionId);n.parentId&&n.parentId!==0&&t.has(n.parentId)?t.get(n.parentId).children.push(d):a.push(d)});const s=n=>{n.sort((d,o)=>(d.sortOrder||0)-(o.sortOrder||0)),n.forEach(d=>s(d.children))};return s(a),a}function Xt(e){return{M:"目录",C:"菜单",F:"按钮"}[e]||e}function Ue(e,t,a=0){return(e||[]).map(s=>{var o;const n=t.has(s.permissionId),d=(o=s.children)!=null&&o.length?`<div class="perm-children">${Ue(s.children,t,a+1)}</div>`:"";return`
      <div class="perm-tree-node" style="padding-left:${a*18}px">
        <label class="perm-tree-label">
          <input type="checkbox" class="perm-cb" data-id="${s.permissionId}" ${n?"checked":""}
            onchange="onPermCheckChange(this, ${s.permissionId})">
          <span class="perm-type perm-type-${b(s.permType)}">${i(Xt(s.permType))}</span>
          <span class="perm-name">${i(s.permName)}</span>
          <code class="perm-code">${i(s.permCode)}</code>
        </label>
      </div>${d}`}).join("")}function _e(e,t){const a=[e];return(t||[]).filter(s=>s.parentId===e).forEach(s=>{a.push(..._e(s.permissionId,t))}),a}function Zt(e,t){const a=document.getElementById("rbacPermPane");if(!a)return;const s=e.checked;if(_e(t,window._rbacState.perms).forEach(d=>{const o=a.querySelector('.perm-cb[data-id="'+d+'"]');o&&(o.checked=s)}),s){let d=(window._rbacState.perms.find(o=>o.permissionId===t)||{}).parentId;for(;d&&d!==0;){const o=a.querySelector('.perm-cb[data-id="'+d+'"]');o&&(o.checked=!0),d=(window._rbacState.perms.find(h=>h.permissionId===d)||{}).parentId}}}async function Yt(e){window._rbacState.selectedRoleId=e,document.querySelectorAll(".rbac-role-row").forEach(t=>{var a;t.classList.toggle("selected",(a=t.getAttribute("onclick"))==null?void 0:a.includes("("+e+")"))}),await $e(e)}async function $e(e){const t=document.getElementById("rbacPermPane");if(t){t.innerHTML='<p class="sub">加载权限树…</p>';try{const a=await p("/api/v2/ops/admin/rbac/roles/"+e+"/permissions","GET"),s=(window._rbacState.roles||[]).find(h=>h.roleId===e),n=new Set(a.permissionIds||[]);window._rbacState.rolePermIds=n;const d=(s==null?void 0:s.roleKey)==="admin",o=Wt(window._rbacState.perms);t.innerHTML=`
      <div class="pane-head">
        <h3 class="pane-title">${i((s==null?void 0:s.roleName)||a.roleName)} · 菜单权限</h3>
        ${d?'<span class="badge badge-done">超级管理员不可编辑</span>':O("rbac.role.save","保存权限","saveRolePermissions()","btn-primary btn-sm")}
      </div>
      <div class="perm-tree">${Ue(o,n)}</div>`,A()}catch(a){u(a)||(t.innerHTML='<p class="err">'+i(a.message)+"</p>")}}}async function Qt(){var a;const e=(a=window._rbacState)==null?void 0:a.selectedRoleId;if(!e)return;const t=[...document.querySelectorAll("#rbacPermPane .perm-cb:checked")].map(s=>parseInt(s.dataset.id,10));try{await p("/api/v2/ops/admin/rbac/roles/"+e+"/permissions","PUT",t),r("角色权限已保存","ok"),$e(e)}catch(s){u(s)||r("保存失败: "+s.message,"err")}}function ea(){var e;window._rbacState.operatorFilters.phone=(((e=document.getElementById("rbacOpPhone"))==null?void 0:e.value)||"").trim(),window._rbacState.operatorFilters.page=0,ee()}async function ee(){const e=document.getElementById("rbacOperatorList");if(e){e.innerHTML='<p class="sub">加载中…</p>';try{const t=window._rbacState.operatorFilters,a=new URLSearchParams({page:t.page,size:t.size});t.phone&&a.set("phone",t.phone);const s=await p("/api/v2/ops/admin/rbac/operators?"+a,"GET");if(!s.items.length){e.innerHTML=f("暂无运营账号","运营账号 userId ≥ 100000000","searchRbacOperators()");return}e.innerHTML=`
      <table class="table">
        <thead><tr><th>手机号</th><th>姓名</th><th>当前角色</th></tr></thead>
        <tbody>${s.items.map(n=>`
          <tr class="rbac-user-row ${window._rbacState.selectedUserId===n.userId?"selected":""}"
              onclick="selectRbacUser(${n.userId})">
            <td>${i(n.phoneNumber)}</td>
            <td>${i(n.name||"-")}</td>
            <td class="meta">${i((n.roleNames||[]).join("、")||"未分配")}</td>
          </tr>`).join("")}</tbody>
      </table>
      ${ta(s)}`}catch(t){u(t)||(e.innerHTML='<p class="err">'+i(t.message)+"</p>")}}}function ta(e){const t=window._rbacState.operatorFilters,a=Math.max(1,Math.ceil((e.total||0)/t.size)),s=t.page+1;return`<div class="pagination">
    共 ${e.total||0} 条 · 第 ${s}/${a} 页
    <button class="btn-ghost btn-sm" ${t.page<=0?"disabled":""} onclick="changeRbacOperatorPage(${t.page-1})">上一页</button>
    <button class="btn-ghost btn-sm" ${s>=a?"disabled":""} onclick="changeRbacOperatorPage(${t.page+1})">下一页</button>
  </div>`}function aa(e){window._rbacState.operatorFilters.page=Math.max(0,e),ee()}async function Ge(e){window._rbacState.selectedUserId=e,document.querySelectorAll(".rbac-user-row").forEach(a=>{var s;a.classList.toggle("selected",(s=a.getAttribute("onclick"))==null?void 0:s.includes("("+e+")"))});const t=document.getElementById("rbacUserRolePane");if(t){t.innerHTML='<p class="sub">加载角色…</p>';try{const a=await p("/api/v2/ops/admin/rbac/users/"+e+"/roles","GET"),s=new Set(a.roleIds||[]),n=(window._rbacRoles||[]).map(d=>`<label class="role-check-item">
        <input type="checkbox" class="rbac-role-cb" value="${b(d.roleId)}" ${s.has(d.roleId)?"checked":""}>
        <span>${i(d.roleName)}</span>
        <code>${i(d.roleKey)}</code>
      </label>`).join("");t.innerHTML=`
      <h3 class="pane-title">分配角色 · 用户 ${i(e)}</h3>
      <div class="role-check-list">${n||'<p class="sub">无可用角色</p>'}</div>
      ${O("rbac.assign","保存授权","saveUserRoles()","btn-primary btn-sm")}`,A()}catch(a){u(a)||(t.innerHTML='<p class="err">'+i(a.message)+"</p>")}}}async function sa(){var a;const e=(a=window._rbacState)==null?void 0:a.selectedUserId;if(!e){r("请先选择运营账号","err");return}const t=[...document.querySelectorAll(".rbac-role-cb:checked")].map(s=>parseInt(s.value,10));try{await p("/api/v2/ops/admin/rbac/users/"+e+"/roles","PUT",t),r("用户授权已保存","ok"),ee(),Ge(e)}catch(s){u(s)||r("保存失败: "+s.message,"err")}}function na(e){window._rbacState.recentScope=e,ye()}async function ia(){const e=document.getElementById("rbacRecentTable");if(e){e.innerHTML='<p class="sub">加载中…</p>';try{const t=window._rbacState.recentScope==="mine",a=new URLSearchParams({size:15,mine:t?"true":"false"}),s=await p("/api/v2/ops/admin/audit-logs/recent?"+a,"GET");e.innerHTML=Fe(s)}catch(t){u(t)||(e.innerHTML='<p class="err">'+i(t.message)+"</p>")}}}function xe(e){return e.operatorPhone||e.operatorName?`${i(e.operatorName||"-")}<br><span class="meta">${i(e.operatorPhone||e.operatorId)}</span>`:i(e.operatorId)}function Fe(e){return!e||!e.length?f("暂无操作记录","运营后台的敏感操作会记录在此"):`
    <table class="table">
      <thead><tr>
        <th>时间</th><th>操作人</th><th>动作</th><th>对象</th><th>详情</th>
      </tr></thead>
      <tbody>${e.map(t=>`<tr>
        <td>${_(t.createdAt)}</td>
        <td>${xe(t)}</td>
        <td><code>${i(t.action)}</code></td>
        <td>${i(t.targetType||"-")} ${i(t.targetId||"")}</td>
        <td class="meta">${i(t.detail||"-")}</td>
      </tr>`).join("")}</tbody>
    </table>`}function oa(e){window._rbacState=window._rbacState||{},window._rbacState.tab="users",window._rbacState.selectedUserId=e,navigate("rbac")}function ne(e){return e==null?"-":(e*100).toFixed(1)+"%"}N.opsLoaders={sla:Oe,ota:ve,risk:Ne,reconciliation:De,replenishment:ge,rbac:He};Object.assign(window,{loadSlaPage:Oe,loadOtaPage:ve,loadRiskPage:Ne,loadReconciliationPage:De,loadReplenishmentPage:ge,loadRbacPage:He,showOtaPublishForm:jt,publishOta:zt,fetchReconciliationList:fe,runReconToday:qt,showReconDetail:Vt,planReplenishmentRoute:Kt,switchRbacTab:Jt,selectRbacRole:Yt,saveRolePermissions:Qt,searchRbacOperators:ea,changeRbacOperatorPage:aa,selectRbacUser:Ge,saveUserRoles:sa,setRbacRecentScope:na,fetchRbacRecent:ia,onPermCheckChange:Zt,openRbacUserAssign:oa,renderAuditTableHtml:Fe,formatOperatorCell:xe});
