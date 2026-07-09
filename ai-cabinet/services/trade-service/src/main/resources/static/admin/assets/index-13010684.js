(function(){const e=document.createElement("link").relList;if(e&&e.supports&&e.supports("modulepreload"))return;for(const s of document.querySelectorAll('link[rel="modulepreload"]'))a(s);new MutationObserver(s=>{for(const i of s)if(i.type==="childList")for(const c of i.addedNodes)c.tagName==="LINK"&&c.rel==="modulepreload"&&a(c)}).observe(document,{childList:!0,subtree:!0});function n(s){const i={};return s.integrity&&(i.integrity=s.integrity),s.referrerPolicy&&(i.referrerPolicy=s.referrerPolicy),s.crossOrigin==="use-credentials"?i.credentials="include":s.crossOrigin==="anonymous"?i.credentials="omit":i.credentials="same-origin",i}function a(s){if(s.ep)return;s.ep=!0;const i=n(s);fetch(s.href,i)}})();const ze="admin_theme";function Qe(){return localStorage.getItem(ze)||"dark"}function Ye(t){const e=t==="light"?"light":"dark";document.documentElement.setAttribute("data-theme",e),localStorage.setItem(ze,e);const n=document.getElementById("themeToggle");n&&(n.textContent=e==="dark"?"浅色":"深色",n.title=e==="dark"?"切换为浅色主题":"切换为深色主题",n.setAttribute("aria-label",n.title))}function Xn(){Ye(Qe())}function Zn(){Ye(Qe()==="dark"?"light":"dark")}function o(t){return t==null?"":String(t).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;").replace(/'/g,"&#39;")}function d(t){return o(t).replace(/`/g,"&#96;")}function fe(t){return(t||"").replace(/\D/g,"").slice(0,11)}function ta(t){return/^1\d{10}$/.test(fe(t))}function ea(){return"请输入11位有效手机号"}function ft(t){if(!t)return"未知错误";const e=t.message&&String(t.message).trim()||"";if(e&&/[\u4e00-\u9fff]/.test(e))return e;const n=e.toLowerCase();return n.includes("missing token")||n.includes("invalid token")?"登录已失效，请重新登录":n.includes("permission denied")?"无权限执行此操作":n.includes("consumer")||n.includes("operator")?"请使用运营账号登录后台":n.includes("device not found")||n.includes("device_not_found")?"设备不存在":n.includes("session_state")||n.includes("session state")?"会话状态异常，请刷新页面":n.includes("occupied")||n.includes("busy")?"设备使用中，请稍后再试":n.includes("balance")?"余额不足":n.includes("blacklist")?"账号受限":e||"请求失败"}function na(t){const e=ft(t);return t&&(t.status===401||t.status===403||/401|403|登录已失效|无权限|权限不足/i.test(e))}function g(t){return na(t)?(typeof logout=="function"&&logout(),typeof showErr=="function"&&showErr("loginErr",t.status===403?"权限不足或登录已失效，请重新登录":"登录已失效，请重新登录"),!0):!1}function aa(t){const e=t||"-",a={PAID:"已支付",PENDING:"待支付",REFUNDED:"已退款",CANCELLED:"已取消"}[e]||e;return`<span class="badge ${e==="PAID"?"badge-done":e==="PENDING"?"badge-active":e==="REFUNDED"?"badge-fail":e==="CANCELLED"?"badge-offline":"badge-active"}">${o(a)}</span>`}const sa={CREATED:"已创建",OPENING:"开门中",SHOPPING:"购物中",RECOGNIZING:"识别中",WAITING_UPLOAD:"等待上传",SETTLING:"结算中",COMPLETED:"已完成",DISPUTED:"待审核",FAILED:"失败",CANCELLED:"已取消"};function Je(t){return sa[t]||t||"-"}function oa(t){return t?`<span class="badge ${["COMPLETED","CANCELLED"].includes(t)?"badge-done":["FAILED","DISPUTED"].includes(t)?"badge-fail":"badge-active"}">${o(Je(t))}</span>`:"-"}const ia={NONE:"无需上传",LOCAL_QUEUED:"本地排队",UPLOADING:"上传中",UPLOADED:"已上传",FAILED:"上传失败"};function Xe(t){return ia[t]||t||"-"}function ca(t){const n=String(t||"UNKNOWN").toUpperCase()==="ONLINE",a=Ze(t);return`<span class="badge ${n?"badge-online":"badge-offline"}">${o(a)}</span>`}function Ze(t){const e=String(t||"UNKNOWN").toUpperCase();return e==="ONLINE"?"在线":e==="OFFLINE"?"离线":"未知"}const la={OPEN:"待审核",RESOLVED:"已结案",CLOSED:"已结案"};function da(t){const e=String(t||"").toUpperCase();return la[e]||"-"}function ra(t){const e=String(t||"").toUpperCase(),n=da(e);return`<span class="badge ${e==="OPEN"?"badge-active":e==="RESOLVED"||e==="CLOSED"?"badge-done":"badge-offline"}">${o(n)}</span>`}const ua={WECHAT:"微信",ALIPAY:"支付宝",MOCK:"模拟支付"},pa={PUBLISHED:"已发布",DRAFT:"草稿",REVOKED:"已撤回"},ma={STABLE:"稳定版",BETA:"测试版",GRAY:"灰度"},ha={PENDING:"待执行",RUNNING:"进行中",COMPLETED:"已完成",FAILED:"失败",MATCHED:"已对平",UNMATCHED:"有差异"},va={PENDING:"待处理",IN_PROGRESS:"进行中",COMPLETED:"已完成",CANCELLED:"已取消",OPEN:"待处理"},ba={ACTIVE:"正常",INACTIVE:"停用",PENDING:"待审核"},ga={LOW:"低",MEDIUM:"中",HIGH:"高",CRITICAL:"严重"},fa={DOOR_OPEN_FAIL:"开门失败",DISPUTE_SPIKE:"争议异常",LOW_BALANCE:"余额不足",BLACKLIST_HIT:"黑名单命中"},ya={AI_CABINET_V1:"AI智能柜 V1"},$a={SINGLE:"单摄",MULTI:"多摄融合"},Ia={DISPUTE_RESOLVE:"争议结案",USER_BALANCE:"调整余额",DEVICE_EDIT:"编辑设备",SKU_EDIT:"编辑商品",RBAC_ASSIGN:"分配角色",MERCHANT_EDIT:"编辑商户",REPLENISH_EDIT:"补货调整"},ka={SESSION:"会话",ORDER:"订单",USER:"用户",DEVICE:"设备",SKU:"商品",DISPUTE:"争议",MERCHANT:"商户"};function tn(t){return ua[String(t||"").toUpperCase()]||t||"-"}function wa(t){return pa[String(t||"").toUpperCase()]||t||"-"}function Sa(t){return ma[String(t||"").toUpperCase()]||t||"-"}function en(t){return ha[String(t||"").toUpperCase()]||t||"-"}function je(t){return va[String(t||"").toUpperCase()]||t||"-"}function Ea(t){return ba[String(t||"").toUpperCase()]||t||"-"}function Ta(t){return ga[String(t||"").toUpperCase()]||t||"-"}function La(t){return fa[String(t||"").toUpperCase()]||t||"-"}function Ca(t){return ya[String(t||"").toUpperCase()]||t||"-"}function Ba(t){return $a[String(t||"").toUpperCase()]||t||"-"}function Pa(t){return Ia[String(t||"").toUpperCase()]||t||"-"}function Ra(t){return ka[String(t||"").toUpperCase()]||t||"-"}function r(t,e){const n=document.getElementById("toastRoot");if(!n){alert(t);return}const a=document.createElement("div");a.className="toast toast-"+(e||"info"),a.textContent=t,n.appendChild(a),setTimeout(()=>a.classList.add("show"),10),setTimeout(()=>{a.classList.remove("show"),setTimeout(()=>a.remove(),300)},3200)}function W(t,e,n){if(g(e)||!t)return;const a=n!==!1?"card err":"err";t.innerHTML=`<div class="${a}">${o(e.message||"加载失败")}</div>`}const Aa={dashboard:"stats",devices:"table",sessions:"filters-table",orders:"filters-table",recharges:"filters-table",skus:"filters-table",users:"filters-table",reports:"table",audit:"filters-table",recent:"filters-table",disputes:"table",sla:"stats",ota:"filters-table",risk:"table",reconciliation:"filters-table",replenishment:"table",merchants:"filters-table",rbac:"table","vision-mappings":"filters-table","upload-queue":"filters-table"};function ie(t){return`<div class="skel-bar" style="width:${t}"></div>`}function Da(t,e){let n="";for(let a=0;a<e;a++){n+='<tr class="skel-row">';for(let s=0;s<t;s++)n+='<td><div class="skel-bar skel-cell"></div></td>';n+="</tr>"}return n}function me(t,e){t=t||6,e=e||5;let n="<tr>";for(let a=0;a<t;a++)n+='<th><div class="skel-bar skel-th"></div></th>';return n+="</tr>",`<div class="skeleton-table-wrap card" style="padding:0;overflow:hidden">
    <table class="skeleton-table"><thead>${n}</thead>
    <tbody>${Da(t,e)}</tbody></table></div>`}function Ma(){return`<div class="card skeleton-filters">
    <div class="skel-filter-row">
      ${ie("120px")}${ie("160px")}${ie("72px")}
    </div>
  </div>`}function Oa(){return`<div class="page-loading">
    <div class="stats">${Array.from({length:8},()=>'<div class="stat skel-stat"><div class="skel-bar skel-label"></div><div class="skel-bar skel-value"></div></div>').join("")}</div>
    <div class="card skel-chart">
      <div class="skel-bar skel-title"></div>
      <div class="skel-bars">${Array.from({length:7},()=>'<div class="skel-chart-bar"></div>').join("")}</div>
    </div>
  </div>`}function Na(t){return t==="stats"?Oa():t==="filters-table"?Ma()+me(6,6):me(6,8)}function nn(t){const e=document.getElementById("pageContent");e&&(e.innerHTML=Na(Aa[t]||"table"))}function j(t,e,n){t&&(t.innerHTML=me(e,n))}function D(t,e){const n=d(t),a=o(e||"刷新");return`<button type="button" class="btn-ghost btn-sm" onclick="${n}">${a}</button>`}function P(t,e,n){const a=n?`<div class="empty-actions">${D(n)}</div>`:"";return`<div class="empty-state">
    <div class="empty-icon" aria-hidden="true"></div>
    <div class="empty-title">${o(t)}</div>
    ${e?`<div class="empty-hint">${o(e)}</div>`:""}
    ${a}
  </div>`}let jt=null;function Ua(t){ye(),jt=t}function ye(){jt&&(URL.revokeObjectURL(jt),jt=null)}function $e(t){if(!t)return"unknown";const e=String(t).toLowerCase();return/\.(jpe?g|png|gif|webp|bmp)(\?|$)/.test(e)?"image":/\.(mp4|webm|mov|m4v)(\?|$)/.test(e)?"video":"unknown"}function Ie(t){return $e(t)==="image"?"查看截图":"播放视频"}const xa={PENDING:"待处理",LEDGER_ONLY:"仅记账",ACCRUED:"待分账",WECHAT_SUBMITTED:"已提交",WECHAT_FAILED:"失败",SUBMITTED:"已提交",SUCCESS:"成功",FAILED:"失败"};function _a(t){const e=(t||"").toUpperCase(),n=xa[e]||t||"-";return`<span class="badge ${e==="SUCCESS"||e==="SUBMITTED"||e==="WECHAT_SUBMITTED"?"badge-done":e==="FAILED"||e==="WECHAT_FAILED"?"badge-fail":e==="ACCRUED"?"badge-active":"badge-offline"}">${o(n)}</span>`}const ce=new Map;function yt(t){return ce.has(t)||ce.set(t,new Set),ce.get(t)}function M(t){yt(t).clear(),B(t)}function ke(t){return[...yt(t)]}function an(t,e,n){const a=String(e),s=yt(t);n?s.add(a):s.delete(a),B(t)}function Ha(t,e){const n=yt(t);n.clear(),e&&document.querySelectorAll(`[data-sel-scope="${t}"] .row-select-cb`).forEach(a=>n.add(a.value)),B(t)}function Fa(t,e,n){if(n.target.closest("button, input, a, label, select"))return;const a=String(e),s=yt(t);n.ctrlKey||n.metaKey?s.has(a)?s.delete(a):s.add(a):(s.clear(),s.add(a)),B(t)}function B(t){const e=yt(t);document.querySelectorAll(`[data-sel-scope="${t}"] .selectable-row`).forEach(a=>{a.classList.toggle("selected",e.has(a.dataset.rowId))}),document.querySelectorAll(`[data-sel-scope="${t}"] .row-select-cb`).forEach(a=>{a.checked=e.has(a.value)});const n=document.querySelector(`[data-sel-scope="${t}"] thead .col-check input[type="checkbox"]`);if(n){const a=[...document.querySelectorAll(`[data-sel-scope="${t}"] .row-select-cb`)];n.checked=a.length>0&&a.every(s=>s.checked),n.indeterminate=!n.checked&&e.size>0}document.querySelectorAll(`[data-sel-bar="${t}"]`).forEach(a=>{a.textContent=e.size?`已选 ${e.size} 项（Ctrl+点击可多选）`:""}),document.querySelectorAll(`[data-sel-actions="${t}"]`).forEach(a=>{a.classList.toggle("hidden",e.size===0)}),typeof document<"u"&&document.dispatchEvent(new CustomEvent("selchange",{detail:{scope:t}}))}function N(t,e=""){const n=e?`<span class="selection-actions hidden" data-sel-actions="${d(t)}">${e}</span>`:"";return`<span class="selection-bar meta" data-sel-bar="${d(t)}"></span>${n}`}function F(t){return`<th class="col-check"><input type="checkbox" title="全选" onchange="selToggleAll('${d(t)}', this.checked)"></th>`}function sn(t,e){const n=yt(t).has(String(e));return`<input type="checkbox" class="row-select-cb" value="${d(e)}" ${n?"checked":""}
    onclick="event.stopPropagation()" onchange="selToggle('${d(t)}', '${d(e)}', this.checked)">`}function q(t,e){return`<td class="col-check" onclick="event.stopPropagation()">${sn(t,e)}</td>`}function on(t,e,n,a="",s=""){const c=["selectable-row",yt(t).has(String(e))?"selected":"",a].filter(Boolean).join(" "),l=s?`;${s}`:"";return`<${n} class="${c}" data-row-id="${d(e)}"
    onclick="selRowClick('${d(t)}', '${d(e)}', event)${l}">`}function G(t,e,n="",a=""){return on(t,e,"tr",n,a)}function qa(t,e,n="",a=""){return on(t,e,"div",n,a)}function x(t,e){return`<div class="table-wrap" data-sel-scope="${d(t)}">${e}</div>`}function Xt(t,e=300){let n=null;return(...a)=>{clearTimeout(n),n=setTimeout(()=>t(...a),e)}}let Nt=null;function we(){Nt&&(document.removeEventListener("keydown",Nt),Nt=null),document.body.classList.remove("modal-open")}function Se(t){const e=document.getElementById("modalRoot");if(!e)return;const n=e.querySelector(".modal, .confirm-dialog");if(!n)return;n.setAttribute("role","dialog"),n.setAttribute("aria-modal","true");const a=n.querySelector("h3, .confirm-title");a&&!a.id&&(a.id="modalTitle_"+Date.now()),a&&n.setAttribute("aria-labelledby",a.id),we(),Nt=i=>{if(i.key==="Escape")if(typeof t=="function")t();else{const c=e.querySelector("[data-modal-cancel]");c?c.click():typeof window.closeModal=="function"&&window.closeModal()}},document.addEventListener("keydown",Nt),document.body.classList.add("modal-open");const s=n.querySelector("button, input, select, textarea");s==null||s.focus()}function et(t,e={}){const n=e.title||"请确认",a=e.confirmText||"确定",s=e.cancelText||"取消",i=e.danger?" btn-danger":"";return new Promise(c=>{const l=document.getElementById("modalRoot");if(!l){c(window.confirm(t));return}const u=p=>{l.classList.add("hidden"),l.innerHTML="",we(),c(p)};l.innerHTML=`
      <div class="modal-backdrop" data-modal-backdrop>
        <div class="modal confirm-dialog" onclick="event.stopPropagation()">
          <h3 class="confirm-title">${o(n)}</h3>
          <p class="confirm-msg">${o(t)}</p>
          <div class="modal-actions">
            <button type="button" class="btn-ghost" data-modal-cancel>${o(s)}</button>
            <button type="button" class="btn-primary${i}" data-modal-ok>${o(a)}</button>
          </div>
        </div>
      </div>`,l.classList.remove("hidden"),Se(()=>u(!1)),l.querySelector("[data-modal-cancel]").onclick=()=>u(!1),l.querySelector("[data-modal-ok]").onclick=()=>u(!0),l.querySelector("[data-modal-backdrop]").onclick=p=>{p.target===p.currentTarget&&u(!1)}})}async function U(t,e,n="保存中…"){const a=t&&t.target&&t.target.closest("button")||null;if(a!=null&&a.disabled)return;const s=a?a.textContent:"";a&&(a.disabled=!0,a.classList.add("btn-loading"),a.textContent=n);try{return await e()}finally{a&&(a.disabled=!1,a.classList.remove("btn-loading"),a.textContent=s)}}function Zt(t,e){const n=Math.max(1,Math.ceil(t.total/t.size)),a=t.page+1,i=[10,20,50,100].map(c=>`<option value="${c}" ${t.size===c?"selected":""}>${c} 条/页</option>`).join("");return`<div class="pagination">
    <span class="pagination-meta">共 ${t.total} 条，第 ${a}/${n} 页</span>
    <div class="pagination-controls">
      <button type="button" class="btn-ghost btn-sm" ${t.page<=0?"disabled":""} onclick="changePage('${d(e)}', 0)">首页</button>
      <button type="button" class="btn-ghost btn-sm" ${t.page<=0?"disabled":""} onclick="changePage('${d(e)}', ${t.page-1})">上一页</button>
      <span class="pagination-jump">第 <input type="number" class="page-jump-input" min="1" max="${n}" value="${a}"
        onkeydown="if(event.key==='Enter')jumpToPage('${d(e)}', this.value)"> 页
        <button type="button" class="btn-ghost btn-sm" onclick="jumpToPage('${d(e)}', this.previousElementSibling.value)">跳转</button></span>
      <button type="button" class="btn-ghost btn-sm" ${a>=n?"disabled":""} onclick="changePage('${d(e)}', ${t.page+1})">下一页</button>
      <button type="button" class="btn-ghost btn-sm" ${a>=n?"disabled":""} onclick="changePage('${d(e)}', ${n-1})">末页</button>
      <select class="page-size-select" onchange="changePageSize('${d(e)}', this.value)">${i}</select>
    </div>
  </div>`}function Ee(t,e,n){if(!t||!t.length||!e)return t||[];const a=n==="asc"?1:-1;return[...t].sort((s,i)=>{let c=s[e],l=i[e];return c==null&&(c=""),l==null&&(l=""),typeof c=="number"&&typeof l=="number"?(c-l)*a:String(c).localeCompare(String(l),"zh-CN")*a})}function kt(t,e,n){return`<th class="sortable-th" onclick="toggleTableSort('${d(t)}', '${d(e)}')">${o(n)}<span class="sort-indicator" data-sort-ind="${d(t)}-${d(e)}"></span></th>`}function Ga(t){return`<div class="forbidden-page card">
    <div class="forbidden-icon" aria-hidden="true">403</div>
    <h3>无权访问</h3>
    <p class="sub">您没有「${o(t||"该页面")}」的访问权限，请联系管理员分配角色。</p>
    <button type="button" class="btn-primary" onclick="navigate('dashboard')">返回概览</button>
  </div>`}const ja={PAID:"已支付",PENDING:"待支付",REFUNDED:"已退款",CANCELLED:"已取消"};function Ka(t){return ja[t]||t||"-"}function Y(t){return String(t??"").replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/"/g,"&quot;")}function cn(t){if(t<=0)return 1;const e=Math.pow(10,Math.floor(Math.log10(t))),n=t/e;return(n<=1?1:n<=2?2:n<=5?5:10)*e}function Ke(t){return"¥"+(t/100).toFixed(t>=1e4?0:2)}function le(t){return(Number(t)*100).toFixed(1)+"%"}function qt(t,e,n={}){if(e==null||t==null)return"";const a=e===0?t>0?100:0:(t-e)/Math.abs(e)*100,s=a>=0,c=n.invert?!s:s,l=Math.abs(a)<.05?"flat":c?"up":"down",u=a>=0?"+":"",p=n.suffix==="%"?`${u}${a.toFixed(1)}%`:`${u}${a.toFixed(1)}%`;return`<span class="delta-badge ${l}" title="较前一周期">${p}</span>`}function de({labels:t,series:e,height:n=220,formatY:a=s=>String(s)}){if(!(t!=null&&t.length)||!(e!=null&&e.length))return'<p class="meta chart-empty">暂无趋势数据</p>';const s=640,i=n,c=52,l=16,u=20,p=36,m=s-c-l,I=i-u-p;let k=0;e.forEach(b=>{(b.values||[]).forEach(E=>{E>k&&(k=E)})}),k=cn(k);const L=b=>c+(t.length<=1?m/2:b/(t.length-1)*m),_=b=>u+I-(k>0?b/k*I:0),h=[0,.25,.5,.75,1].map(b=>{const E=u+I*(1-b),w=k*b;return`<line x1="${c}" y1="${E}" x2="${s-l}" y2="${E}" class="chart-grid"/>
      <text x="${c-8}" y="${E+4}" class="chart-axis-y" text-anchor="end">${Y(a(w))}</text>`}).join(""),y=t.map((b,E)=>`<text x="${L(E)}" y="${i-8}" class="chart-axis-x" text-anchor="middle">${Y(b)}</text>`).join(""),f=e.map(b=>{const E=(b.values||[]).map((R,wt)=>`${L(wt)},${_(R)}`).join(" "),w=(b.values||[]).map((R,wt)=>`<circle cx="${L(wt)}" cy="${_(R)}" r="4" class="chart-dot" fill="${b.color||"var(--chart-1)"}">
        <title>${Y(b.name)} ${Y(t[wt])}: ${Y(a(R))}</title>
      </circle>`).join("");return`<polyline points="${E}" fill="none" stroke="${b.color||"var(--chart-1)"}" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>
      ${w}`}).join(""),S=e.map(b=>`<span class="chart-legend-item"><i style="background:${b.color||"var(--chart-1)"}"></i>${Y(b.name)}</span>`).join("");return`<div class="svg-chart-wrap">
    <svg class="svg-chart" viewBox="0 0 ${s} ${i}" preserveAspectRatio="xMidYMid meet" role="img" aria-label="折线图">
      ${h}
      ${f}
      ${y}
    </svg>
    <div class="chart-legend">${S}</div>
  </div>`}function Va({labels:t,values:e,height:n=200,formatY:a=i=>String(i),color:s="var(--chart-1)"}){if(!(t!=null&&t.length))return'<p class="meta chart-empty">暂无数据</p>';const i=640,c=n,l=48,u=12,p=16,m=32,I=i-l-u,k=c-p-m,L=cn(Math.max(...e,0)),_=Math.min(48,I/t.length*.55),h=I/t.length,y=e.map((b,E)=>{const w=L>0?b/L*k:0,R=l+E*h+(h-_)/2,wt=p+k-w;return`<rect x="${R}" y="${wt}" width="${_}" height="${Math.max(w,2)}" rx="4" fill="${s}" opacity="0.9">
      <title>${Y(t[E])}: ${Y(a(b))}</title>
    </rect>`}).join(""),f=t.map((b,E)=>`<text x="${l+E*h+h/2}" y="${c-8}" class="chart-axis-x" text-anchor="middle">${Y(b)}</text>`).join(""),S=[0,.5,1].map(b=>{const E=p+k*(1-b);return`<line x1="${l}" y1="${E}" x2="${i-u}" y2="${E}" class="chart-grid"/>
      <text x="${l-6}" y="${E+4}" class="chart-axis-y" text-anchor="end">${Y(a(L*b))}</text>`}).join("");return`<div class="svg-chart-wrap">
    <svg class="svg-chart" viewBox="0 0 ${i} ${c}" preserveAspectRatio="xMidYMid meet" role="img" aria-label="柱状图">
      ${S}${y}${f}
    </svg>
  </div>`}function Wa({segments:t,size:e=160}){const n=t.reduce((m,I)=>m+I.value,0)||1,a=e/2,s=e/2,i=e*.38,c=i*.58;let l=-Math.PI/2;const u=t.map(m=>{const I=m.value/n*Math.PI*2,k=a+i*Math.cos(l),L=s+i*Math.sin(l);l+=I;const _=a+i*Math.cos(l),h=s+i*Math.sin(l),y=a+c*Math.cos(l-I),f=s+c*Math.sin(l-I),S=a+c*Math.cos(l),b=s+c*Math.sin(l),E=I>Math.PI?1:0;return`<path d="${`M ${k} ${L} A ${i} ${i} 0 ${E} 1 ${_} ${h} L ${S} ${b} A ${c} ${c} 0 ${E} 0 ${y} ${f} Z`}" fill="${m.color}"><title>${Y(m.label)}: ${m.value}</title></path>`}).join(""),p=t.map(m=>`<span class="chart-legend-item"><i style="background:${m.color}"></i>${Y(m.label)} ${Math.round(m.value/n*100)}%</span>`).join("");return`<div class="donut-chart-wrap">
    <svg width="${e}" height="${e}" viewBox="0 0 ${e} ${e}" role="img" aria-label="环形图">${u}</svg>
    <div class="chart-legend donut-legend">${p}</div>
  </div>`}function za(t){if(!(t!=null&&t.length))return"";const e=Math.max(...t.map(n=>n.value),1);return`<div class="h-bar-list">${t.map(n=>{const a=Math.round(n.value/e*100);return`<div class="h-bar-row">
      <span class="h-bar-label">${Y(n.label)}</span>
      <div class="h-bar-track"><div class="h-bar-fill" style="width:${a}%;background:${n.color||"var(--chart-1)"}"></div></div>
      <span class="h-bar-val">${Y(n.display??n.value)}</span>
    </div>`}).join("")}</div>`}function Qa(t,e,n){const a=(e==null?void 0:e.last7Days)||[],s=(n==null?void 0:n.last7Days)||[],i=a.map(w=>w.date.slice(5)),c=a.map(w=>w.revenueCents),l=a.map(w=>w.orderCount),u=Object.fromEntries(s.map(w=>[w.date,w])),p=a.map(w=>{const R=u[w.date];return R?Math.round((R.recognitionRate||0)*1e3)/10:0}),m=a.map(w=>{const R=u[w.date];return R?Math.round((R.disputeRate||0)*1e3)/10:0}),I=a.map(w=>{const R=u[w.date];return R?(R.completedSessions||0)+(R.disputedSessions||0):0}),k=c.reduce((w,R)=>w+R,0),L=l.reduce((w,R)=>w+R,0),_=p.length?p.reduce((w,R)=>w+R,0)/p.length:0,h=c[c.length-1],y=c[c.length-2],f=l[l.length-1],S=l[l.length-2],b=t.deviceOnline||0,E=Math.max(0,(t.deviceTotal||0)-b);return`
    <div class="analytics-section">
      <div class="analytics-head">
        <h3 class="section-title">数据分析</h3>
        <span class="meta">近 7 日趋势 · 点击指标卡片可跳转详情</span>
      </div>
      <div class="analytics-kpi">
        <div class="kpi-card">
          <div class="kpi-label">7日总营收</div>
          <div class="kpi-value">${Ke(k)}</div>
          ${qt(h,y)}
        </div>
        <div class="kpi-card">
          <div class="kpi-label">7日订单量</div>
          <div class="kpi-value">${L}</div>
          ${qt(f,S)}
        </div>
        <div class="kpi-card">
          <div class="kpi-label">平均识别率</div>
          <div class="kpi-value ok">${_.toFixed(1)}%</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">设备在线率</div>
          <div class="kpi-value">${t.deviceTotal?Math.round(b/t.deviceTotal*100):0}%</div>
        </div>
      </div>
      <div class="analytics-grid">
        <div class="card chart-card">
          <div class="chart-card-head">
            <h4>营收趋势</h4>
            ${qt(h,y)}
          </div>
          ${de({labels:i,series:[{name:"营收",values:c,color:"var(--chart-1)"}],formatY:w=>Ke(w)})}
        </div>
        <div class="card chart-card">
          <div class="chart-card-head">
            <h4>订单量趋势</h4>
            ${qt(f,S)}
          </div>
          ${de({labels:i,series:[{name:"订单",values:l,color:"var(--chart-2)"}],formatY:w=>String(Math.round(w))})}
        </div>
        <div class="card chart-card">
          <div class="chart-card-head">
            <h4>识别质量</h4>
            <span class="meta">识别率 vs 争议率</span>
          </div>
          ${de({labels:i,series:[{name:"识别率",values:p,color:"var(--chart-3)"},{name:"争议率",values:m,color:"var(--chart-4)"}],formatY:w=>w+"%"})}
        </div>
        <div class="card chart-card">
          <div class="chart-card-head">
            <h4>关门会话量</h4>
          </div>
          ${Va({labels:i,values:I,color:"var(--chart-2)",formatY:w=>String(Math.round(w))})}
        </div>
        <div class="card chart-card chart-card-sm">
          <div class="chart-card-head"><h4>设备状态</h4></div>
          ${Wa({segments:[{label:"在线",value:b,color:"var(--chart-3)"},{label:"离线",value:E||(b?0:1),color:"var(--chart-muted)"}]})}
        </div>
        <div class="card chart-card chart-card-sm">
          <div class="chart-card-head"><h4>运营健康度</h4></div>
          ${za([{label:"24h 开门成功率",value:t.doorSuccessRate24h||0,display:le(t.doorSuccessRate24h),color:"var(--chart-3)"},{label:"24h 自动识别率",value:t.recognitionAutoRate24h||0,display:le(t.recognitionAutoRate24h),color:"var(--chart-1)"},{label:"24h 争议率",value:t.disputeRate24h||0,display:le(t.disputeRate24h),color:"var(--chart-4)"}])}
        </div>
      </div>
    </div>`}let St=new Set,re=!1;const Ya={dashboard:"ops:dashboard:view",devices:"ops:device:list",sessions:"ops:session:list",orders:"ops:order:list",recharges:"ops:order:list",skus:"ops:sku:list",users:"ops:user:list",reports:"ops:device:list",audit:"ops:audit:list",recent:"ops:audit:recent",disputes:"ops:dispute","vision-mappings":"ops:vision:list","upload-queue":"ops:session:upload",sla:"ops:sla",ota:"ops:ota:list",risk:"ops:risk:list",reconciliation:"ops:reconciliation:list",replenishment:"ops:replenishment:list",warehouse:"ops:replenishment:list",finance:"ops:replenishment:list",merchants:"ops:merchant:list",rbac:"ops:rbac:role"},Ja={"device.create":"ops:device:edit","device.edit":"ops:device:edit","session.cancel":"ops:session:cancel","sku.edit":"ops:sku:edit","user.balance":"ops:user:balance","recharge.refund":"ops:user:balance","ota.publish":"ops:ota:publish","risk.blacklist":"ops:risk:blacklist","recon.run":"ops:reconciliation:run","replenish.edit":"ops:replenishment:edit","replenish.plan":"ops:replenishment:edit","rbac.assign":"ops:rbac:assign","rbac.role.save":"ops:rbac:role","vision.edit":"ops:vision:edit","merchant.edit":"ops:merchant:edit","merchant.split":"ops:merchant:split","user.verify":"ops:user:list"};async function Xa(t){re=!1;try{const e=await t("/api/v2/ops/admin/rbac/me/permissions","GET");St=new Set(e||[]),St.has("*")&&(St=new Set(["*"]))}catch(e){console.warn("load permissions failed, no permissions granted",e),St=new Set,re=!0}return nt(),!re}function T(t){return!t||St.has("*")?!0:St.has(t)}function ln(t){const e=Ya[t];return!!(!e||T(e)||t==="audit"&&T("ops:dashboard:view"))}function nt(){document.querySelectorAll(".nav-item[data-page]").forEach(t=>{const e=t.dataset.page;ln(e)?t.classList.remove("hidden"):t.classList.add("hidden")}),document.querySelectorAll("[data-perm]").forEach(t=>{const e=t.getAttribute("data-perm");T(e)?t.style.display="":t.style.display="none"}),document.querySelectorAll(".nav-section").forEach(t=>{const e=t.querySelectorAll(".nav-item[data-page]");if(!e.length)return;const n=[...e].some(a=>!a.classList.contains("hidden"));t.classList.toggle("hidden",!n)})}function J(t,e,n,a){const s=Ja[t];return s&&!T(s)?"":`<button class="${a||"btn-primary btn-sm"}" data-perm="${s||""}" onclick="${n}">${e}</button>`}const $t={api:null,getCurrentPage:()=>"dashboard",fmtTime:t=>t||"-",fmtMoney:t=>String(t),closeModal:()=>{},opsLoaders:{}},xt=({}.VITE_API_BASE||"").replace(/\/$/,"")||window.location.origin;let z=localStorage.getItem("admin_token")||"",pt=[],K="";const V={page:0,size:20,deviceId:"",state:""},st={page:0,size:20,deviceId:""},tt={page:0,size:20,status:"",userId:""},vt={dashboard:"数据概览",devices:"设备管理",sessions:"购物会话",orders:"订单管理",recharges:"充值管理",skus:"商品管理",users:"用户管理",reports:"设备报表",audit:"操作日志",recent:"最近操作",disputes:"争议审核","vision-mappings":"视觉映射","upload-queue":"上传队列",sla:"SLA 监控",ota:"设备 OTA",risk:"风控",reconciliation:"对账",replenishment:"补货",warehouse:"仓库",finance:"财务 COGS",merchants:"商户分账",rbac:"权限管理"},ut={page:0,size:20,phone:""},Qt={page:0,size:20},Ut={size:20,mine:!1},H={page:0,size:20,status:"OPEN",sessionId:"",deviceId:""},lt={sessions:{field:"createdAt",dir:"desc"},orders:{field:"createdAt",dir:"desc"},users:{field:"userId",dir:"desc"}},Za=Xt(()=>Pn(),350),ts=Xt(()=>Rn(),350),es=Xt(()=>Nn(),350);function ue(t){return t==null||Number.isNaN(t)?"-":(Number(t)*100).toFixed(1)+"%"}function dn(t){const e=fe(t);return e?ta(e)?{ok:!0,phone:e}:{ok:!1,message:ea()}:{ok:!1,message:"请输入手机号"}}function ns(t){return(t||"").trim()}function C(t){return"¥"+(t/100).toFixed(2)}function Q(t){return t?new Date(t).toLocaleString("zh-CN"):"-"}const as=30*60*1e3,ss=8*60*1e3,os=5*60*1e3;let Yt=parseInt(localStorage.getItem("admin_token_expires")||"0",10)||0,he=as,rn=Date.now(),Kt=null,Ot=null;function te(){rn=Date.now()}function un(t,e){z=t.token,localStorage.setItem("admin_token",z),localStorage.setItem("admin_userId",t.userId),e&&localStorage.setItem("admin_phone",e),he=(t.expiresInSeconds||1800)*1e3,Yt=Date.now()+he,localStorage.setItem("admin_token_expires",String(Yt)),t.serverBootEpoch!=null&&vn(t.serverBootEpoch),te()}async function pn(){if(!z)return!1;if(Ot)return Ot;Ot=(async()=>{const t=await fetch(xt+"/api/v2/auth/refresh",{method:"POST",headers:{"Content-Type":"application/json",Authorization:"Bearer "+z}}),e=await t.json().catch(()=>({}));if(!t.ok||e.code!==0){const n=new Error(ft({message:e.message,status:t.status})||"登录已失效");throw n.status=t.status,n}return un(e.data),!0})();try{return await Ot}finally{Ot=null}}async function mn(){z&&(Date.now()-rn>he||Yt-Date.now()>ss||await pn())}function Jt(){te()}function is(){hn(),te(),document.addEventListener("click",Jt),document.addEventListener("keydown",Jt),Kt=setInterval(()=>{mn().catch(()=>{})},os)}function hn(){Kt&&(clearInterval(Kt),Kt=null),document.removeEventListener("click",Jt),document.removeEventListener("keydown",Jt)}async function $(t,e,n,a=!0,s=!1){if(a&&z){te();try{await mn()}catch{}}const i={"Content-Type":"application/json"};a&&z&&(i.Authorization="Bearer "+z);const c=await fetch(xt+t,{method:e,headers:i,body:n?JSON.stringify(n):void 0}),l=await c.json().catch(()=>({}));if(c.status===401&&a&&!s)try{return await pn(),$(t,e,n,a,!0)}catch{}if(c.status===401||c.status===403){const u=new Error(ft({message:l.message,status:c.status})||(c.status===403?"权限不足":"登录已失效，请重新登录"));throw u.status=c.status,g(u),u}if(!c.ok||l.code!==0){const u=new Error(ft({message:l.message||l.error})||JSON.stringify(l));throw u.status=c.status,u}return l.data}function cs(t,e,n){const a=(t||"").split(";")[0].trim().toLowerCase();if(a.startsWith("image/")||a.startsWith("video/"))return a;const s=String(e||"").toLowerCase();return s.endsWith(".png")?"image/png":/\.(jpe?g)$/.test(s)?"image/jpeg":s.endsWith(".webp")?"image/webp":s.endsWith(".webm")?"video/webm":s.endsWith(".mov")?"video/quicktime":n==="image"?"image/jpeg":"video/mp4"}async function ls(t,e){const n=localStorage.getItem("admin_token")||z;if(!n)throw new Error("请先登录");const a=await fetch(`${xt}/api/v2/ops/admin/sessions/${encodeURIComponent(t)}/video`,{headers:{Authorization:"Bearer "+n}});if(a.status===401||a.status===403){const p=await a.json().catch(()=>({})),m=new Error(ft({message:p.message,status:a.status})||(a.status===403?"权限不足":"登录已失效，请重新登录"));throw m.status=a.status,g(m),m}const s=a.headers.get("content-type")||"";if(!a.ok){if(s.includes("application/json")){const p=await a.json().catch(()=>({}));throw new Error(ft({message:p.message,status:a.status})||"视频加载失败")}throw new Error(`视频加载失败 (${a.status})`)}if(s.includes("application/json")){const p=await a.json().catch(()=>({}));throw new Error(ft({message:p.message,status:a.status})||"视频不存在")}const i=await a.blob();let c="video";if(s.startsWith("image/"))c="image";else if(s.startsWith("video/"))c="video";else{const p=$e(e);p!=="unknown"&&(c=p)}const l=cs(s,e,c);return l.startsWith("image/")&&(c="image"),l.startsWith("video/")&&(c="video"),{blob:!i.type||i.type==="application/octet-stream"?new Blob([i],{type:l}):i,kind:c,contentType:l}}function Et(t,e){const n=document.getElementById(t);n.textContent=e,n.classList.remove("hidden")}function vn(t){t!=null&&localStorage.setItem("admin_server_boot",String(t))}function ds(t){const e=localStorage.getItem("admin_server_boot");return e?String(t)!==e:!0}async function rs(){return $("/api/v2/auth/server-boot","GET",null,!1)}async function us(){var t,e;if(!z){(t=document.getElementById("loginView"))==null||t.classList.remove("hidden"),(e=document.getElementById("appView"))==null||e.classList.add("hidden");return}try{const n=await rs();if(ds(n)){ve(),r("服务已重启，请重新登录","warn");return}await fetch(xt+"/api/v2/ops/admin/rbac/me",{headers:{Authorization:"Bearer "+z}}).then(async a=>{if(a.status===401||a.status===403)throw Object.assign(new Error("登录已失效"),{status:a.status});const s=await a.json().catch(()=>({}));if(!a.ok||s.code!==0)throw Object.assign(new Error(s.message||"登录已失效"),{status:a.status})}),vn(n),$n()}catch(n){g(n)||ve()}}function bn(){const t=location.hostname==="localhost"||location.hostname==="127.0.0.1",e=document.getElementById("phone"),n=document.getElementById("code"),a=document.getElementById("password"),s=document.getElementById("loginDevHint");t&&(e&&!e.value&&(e.value=localStorage.getItem("admin_phone")||"13900000001"),n&&!n.value&&(n.placeholder="本地固定 123456"),a&&!a.value&&(a.placeholder="本地默认 123456"),s&&s.classList.remove("hidden")),e&&!e.value&&localStorage.getItem("admin_phone")&&(e.value=localStorage.getItem("admin_phone"))}let ht=localStorage.getItem("admin_login_mode")||"password";function gn(t){var a,s,i;ht=t==="sms"?"sms":"password",localStorage.setItem("admin_login_mode",ht),document.querySelectorAll(".login-tab").forEach(c=>{c.classList.toggle("active",c.dataset.mode===ht)});const e=document.getElementById("loginPasswordBlock"),n=document.getElementById("loginSmsBlock");e&&e.classList.toggle("hidden",ht!=="password"),n&&n.classList.toggle("hidden",ht!=="sms"),(a=document.getElementById("loginErr"))==null||a.classList.add("hidden"),ht==="password"?(s=document.getElementById("password"))==null||s.focus():(i=document.getElementById("code"))==null||i.focus()}let Tt=null;function ps(t=60){const e=document.getElementById("sendCodeBtn");if(!e)return;let n=t;e.disabled=!0;const a=()=>{if(n<=0){clearInterval(Tt),Tt=null,e.disabled=!1,e.textContent="获取验证码";return}e.textContent=`${n}s 后重发`,n-=1};a(),Tt=setInterval(a,1e3)}function bt(t,e){const n=document.getElementById("loginBtn"),a=document.getElementById("sendCodeBtn"),s=document.getElementById("phone"),i=document.getElementById("code"),c=document.getElementById("password"),l=!!t;e==="login"?(n&&(n.disabled=l,n.classList.toggle("btn-loading",l),n.textContent=l?"登录中…":"登录"),a&&(a.disabled=l),s&&(s.readOnly=l),i&&(i.readOnly=l),c&&(c.readOnly=l)):e==="code"&&(a&&(a.disabled=l,a.classList.toggle("btn-loading",l),a.textContent=l?"发送中…":"获取验证码"),n&&(n.disabled=l),s&&(s.readOnly=l))}function ms(){const t=document.getElementById("loginForm");if(!t)return;t.addEventListener("submit",n=>{n.preventDefault(),fn()});const e=document.getElementById("phone");e&&!e.dataset.bound&&(e.dataset.bound="1",e.maxLength=11,e.addEventListener("input",()=>{e.value=fe(e.value)}))}async function hs(){var a,s;const t=document.getElementById("sendCodeBtn");if(t!=null&&t.disabled)return;const e=dn(document.getElementById("phone").value);if(!e.ok){Et("loginErr",e.message),(a=document.getElementById("phone"))==null||a.focus();return}const n=e.phone;document.getElementById("loginErr").classList.add("hidden"),bt(!0,"code");try{await $(`/api/v2/auth/sms-code?phoneNumber=${encodeURIComponent(n)}`,"POST",null,!1),ps(60);const i=location.hostname==="localhost"||location.hostname==="127.0.0.1"?"（本地 dev 固定 123456，可直接登录）":"";r("验证码已发送"+i,"ok"),(s=document.getElementById("code"))==null||s.focus()}catch(i){Et("loginErr",i.message)}finally{bt(!1,"code")}}async function fn(){var a,s,i,c;const t=document.getElementById("loginBtn");if(t!=null&&t.disabled)return;const e=dn(document.getElementById("phone").value);if(!e.ok){Et("loginErr",e.message),(a=document.getElementById("phone"))==null||a.focus();return}const n=e.phone;document.getElementById("loginErr").classList.add("hidden"),bt(!0,"login");try{let l;if(ht==="password"){const u=((s=document.getElementById("password"))==null?void 0:s.value)||"";if(!u){bt(!1,"login"),Et("loginErr","请输入密码"),(i=document.getElementById("password"))==null||i.focus();return}l=await $("/api/v2/auth/admin-password-login","POST",{phoneNumber:n,password:u},!1)}else{const u=ns(document.getElementById("code").value);if(!u){bt(!1,"login"),Et("loginErr","请输入验证码"),(c=document.getElementById("code"))==null||c.focus();return}l=await $("/api/v2/auth/admin-login","POST",{phoneNumber:n,code:u},!1)}un(l,n),$n()}catch(l){Et("loginErr",l.message)}finally{bt(!1,"login")}}function ve(){var a;Oe(),hn(),Tt&&(clearInterval(Tt),Tt=null),z="",Yt=0,localStorage.removeItem("admin_token"),localStorage.removeItem("admin_userId"),localStorage.removeItem("admin_server_boot"),localStorage.removeItem("admin_token_expires"),sessionStorage.removeItem("admin_visited_tabs"),O=["dashboard"],Lt=0,_t=!1,K="";try{history.replaceState(null,"",location.pathname+location.search)}catch{}const t=document.getElementById("tagsView");t&&(t.classList.add("hidden"),t.innerHTML=""),document.getElementById("appView").classList.add("hidden"),document.getElementById("loginView").classList.remove("hidden");const e=document.getElementById("pageContent");e&&(e.innerHTML=""),bt(!1,"login"),bt(!1,"code");const n=document.getElementById("sendCodeBtn");n&&(n.disabled=!1,n.textContent="获取验证码"),bn(),(a=document.getElementById("phone"))==null||a.focus()}function yn(){const t=document.getElementById("userInfo");if(!t)return;const e=localStorage.getItem("admin_phone")||"";t.innerHTML=e?`<span class="user-name">运营账号</span><span class="user-detail">${o(e)} · 加载角色…</span>`:'<span class="user-name">运营账号</span><span class="user-detail">加载中…</span>'}async function vs(){const t=document.getElementById("userInfo");if(t)try{const e=await $("/api/v2/ops/admin/rbac/me","GET");localStorage.setItem("admin_userId",e.userId),e.phoneNumber&&localStorage.setItem("admin_phone",e.phoneNumber);const n=e.name||"运营账号",a=e.roleNames&&e.roleNames.length?e.roleNames.join("、"):"未分配角色",s=e.permissionCount>0?` · ${e.permissionCount} 项权限`:"";t.innerHTML=`<span class="user-name">${o(n)}</span><span class="user-detail">${o(e.phoneNumber||"-")} · ${o(a)}${o(s)}</span>`}catch(e){g(e)||yn()}}function $n(){document.getElementById("loginView").classList.add("hidden"),document.getElementById("appView").classList.remove("hidden"),is(),yn(),gs();const t=document.getElementById("pageContent");t&&nn("dashboard"),Promise.all([vs(),Xa($)]).then(([,e])=>{e||r("权限加载失败，部分功能不可用，请刷新页面重试","warn");const n=kn();Is(n),Bt(n,{replaceHash:!0,init:!0})}).catch(e=>{g(e)||W(t,e)})}const bs=12;let O=[],Lt=0,_t=!1;function gs(){try{const t=sessionStorage.getItem("admin_visited_tabs");O=t?JSON.parse(t):["dashboard"],(!Array.isArray(O)||!O.length)&&(O=["dashboard"]),O=O.filter(e=>vt[e]),O.includes("dashboard")||O.unshift("dashboard")}catch{O=["dashboard"]}}function In(){sessionStorage.setItem("admin_visited_tabs",JSON.stringify(O))}function pe(t){if(vt[t]){for(O=O.filter(e=>e!==t),O.push(t);O.length>bs;){const e=O.findIndex(n=>n!=="dashboard");if(e>=0)O.splice(e,1);else break}In()}}function Vt(){const t=document.getElementById("tagsView");if(t){if(O.length<=1){t.classList.add("hidden"),t.innerHTML="";return}t.classList.remove("hidden"),t.innerHTML=O.map(e=>{const n=e===K,a=e!=="dashboard";return`<button type="button" class="tag-item ${n?"active":""}" onclick="navigate('${d(e)}')">
      <span>${o(vt[e]||e)}</span>
      ${a?`<span class="tag-close" onclick="event.stopPropagation();closeVisitedTab('${d(e)}')" title="关闭">×</span>`:""}
    </button>`}).join("")}}function fs(t){t!=="dashboard"&&(O=O.filter(e=>e!==t),In(),K===t?Bt(O[O.length-1]||"dashboard"):Vt())}function be(){const t=document.getElementById("navBackBtn");t&&(t.disabled=!_t)}function ys(){_t&&history.back()}function kn(){const t=location.hash.match(/^#\/([a-z]+)$/),e=t?t[1]:"dashboard";return vt[e]?e:"dashboard"}function wn(t){const e=document.querySelector(".sidebar"),n=document.getElementById("sidebarBackdrop");if(!e)return;const a=t===void 0?!e.classList.contains("open"):!!t;e.classList.toggle("open",a),n==null||n.classList.toggle("hidden",!a)}const Sn="admin_nav_sections";function En(){try{return JSON.parse(localStorage.getItem(Sn)||"{}")}catch{return{}}}function Tn(t,e){const n=En();n[t]=e,localStorage.setItem(Sn,JSON.stringify(n))}function Te(t,e){const n=document.querySelector(`.nav-section[data-nav-group="${t}"]`);if(!n)return;n.classList.toggle("collapsed",!e);const a=n.querySelector(".nav-section-toggle");a&&a.setAttribute("aria-expanded",e?"true":"false")}function $s(t){const e=document.querySelector(`.nav-section[data-nav-group="${t}"]`);if(!e||e.classList.contains("hidden"))return;const n=e.classList.contains("collapsed");Te(t,n),Tn(t,n)}function Ve(t){document.querySelectorAll(".nav-section[data-nav-group]").forEach(e=>{if(e.classList.contains("hidden"))return;const n=e.dataset.navGroup;!!e.querySelector(`.nav-item[data-page="${t}"]:not(.hidden)`)&&(Te(n,!0),Tn(n,!0))})}function Is(t){const e=En();document.querySelectorAll(".nav-section[data-nav-group]").forEach(n=>{if(n.classList.contains("hidden"))return;const a=n.dataset.navGroup,s=!!n.querySelector(`.nav-item[data-page="${t}"]:not(.hidden)`);let i;s?i=!0:e[a]!=null?i=!!e[a]:i=a==="overview",Te(a,i)})}function Le(t){const e=lt[t];if(!e)return;document.querySelectorAll(`[data-sort-ind^="${t}-"]`).forEach(a=>{a.textContent=""});const n=document.querySelector(`[data-sort-ind="${t}-${e.field}"]`);n&&(n.textContent=e.dir==="asc"?" ↑":" ↓")}function ks(t,e){const n=lt[t]||{field:e,dir:"desc"};n.field===e?lt[t]={field:e,dir:n.dir==="asc"?"desc":"asc"}:lt[t]={field:e,dir:"desc"},t==="sessions"?Ht():t==="orders"?ne():t==="users"&&At()}function Bt(t,e={}){vt[t]||(t="dashboard");const n=!!e.fromPopstate,a="#/"+t;if(!ln(t)){K=t,document.getElementById("pageTitle").textContent=vt[t]||t,document.querySelectorAll(".nav-item").forEach(c=>{c.classList.toggle("active",c.dataset.page===t)}),Ve(t),Vt(),document.getElementById("pageContent").innerHTML=Ga(vt[t]),!n&&location.hash!==a&&history.pushState({page:t,forbidden:!0},"",a);return}if(t===K&&!n&&!e.force&&!e.init){pe(t),Vt();return}!n&&!e.replaceHash?(location.hash!==a&&(history.pushState({page:t},"",a),Lt+=1,_t=Lt>0,be()),pe(t)):e.replaceHash&&location.hash!==a&&(history.replaceState({page:t},"",a),e.init&&pe(t)),K=t,wn(!1),t!=="devices"&&Oe(),document.getElementById("pageTitle").textContent=vt[t]||t,document.querySelectorAll(".nav-item").forEach(c=>{c.classList.toggle("active",c.dataset.page===t)}),Ve(t),Vt(),be(),nn(t);const s=$t.opsLoaders||{};({dashboard:Z,devices:ee,sessions:Os,orders:Hs,recharges:yo,skus:Pe,users:Js,reports:ao,audit:so,recent:De,disputes:io,"vision-mappings":s.visionMappings||Z,"upload-queue":s.uploadQueue||Z,sla:s.sla||Z,ota:s.ota||Z,risk:s.risk||Z,reconciliation:s.reconciliation||Z,replenishment:s.replenishment||Z,warehouse:s.warehouse||Z,finance:Cn,merchants:s.merchants||Z,rbac:s.rbac||Z}[t]||Z)()}function Ln(t){return oa(t)}function Ce(t){return ca(t)}function ws(t){return t?`
    <div class="dash-alert">
      <div class="dash-alert-main">
        <span class="dash-alert-dot" aria-hidden="true"></span>
        <span>当前无在线设备，通常是因为未启动设备模拟器或心跳链路未连通</span>
        <button type="button" class="dash-alert-toggle" onclick="this.closest('.dash-alert').classList.toggle('expanded')">查看排查步骤</button>
      </div>
      <div class="dash-alert-detail">
        <ol>
          <li>启动 Docker 基础设施（EMQX 端口 <code>11883</code>）</li>
          <li>运行 <code>trade-service</code>（8080）与 <code>device-service</code>（8081）</li>
          <li>运行设备模拟器，启动类 <code>DeviceSimulator</code>，程序参数 <code>CAB-001</code></li>
        </ol>
        <p class="meta" style="margin:0">代码文件：<code>edge/device-simulator/src/main/java/com/aicabinet/simulator/DeviceSimulator.java</code> · 每 30 秒上报心跳，2 分钟无心跳标记离线</p>
      </div>
    </div>`:""}async function Z(){const t=document.getElementById("pageContent"),e="dashboard";try{const[n,a,s,i,c]=await Promise.all([$("/api/v2/ops/admin/stats","GET"),$("/api/v2/ops/admin/trend","GET"),$("/api/v2/ops/admin/trend/ops","GET"),$("/api/v2/ops/admin/audit-logs/recent?size=5&mine=false","GET").catch(()=>[]),$("/api/v2/ops/admin/finance/stats","GET").catch(()=>null)]);if(K!==e)return;const l=(u,p,m,I,k)=>{let L=`navigate('${I}')`;return k==="lowStock"?L="window.replenishmentFilters&&(window.replenishmentFilters.lowStockOnly=true);navigate('replenishment')":k==="pendingSplit"?L="window.merchantSplitFilters&&(window.merchantSplitFilters.status='PENDING');navigate('merchants')":k==="slotDiscrepancy"&&(L="showSlotDiscrepancies()"),`<div class="stat stat-click" role="button" tabindex="0" onclick="${L}" title="点击查看">
        <div class="label">${u}</div><div class="value ${m||""}">${p}</div>
      </div>`};t.innerHTML=`
      <div class="dashboard-head">
        <div>
          <h3 class="dashboard-head-title">核心指标</h3>
          <p class="dashboard-head-sub">今日运营数据 · 设备每 30 秒上报心跳</p>
        </div>
        ${D("loadDashboard()","刷新")}
      </div>
      ${ws(n.deviceTotal>0&&n.deviceOnline===0)}
      <div class="stats">
        <div class="stat"><div class="label">设备总数</div><div class="value">${n.deviceTotal}</div></div>
        <div class="stat"><div class="label">在线设备</div><div class="value ${n.deviceOnline===0?"warn":"ok"}">${n.deviceOnline}</div></div>
        <div class="stat"><div class="label">进行中会话</div><div class="value warn">${n.sessionActive}</div></div>
        <div class="stat"><div class="label">今日会话</div><div class="value">${n.sessionToday}</div></div>
        <div class="stat"><div class="label">今日订单</div><div class="value">${n.orderToday}</div></div>
        <div class="stat"><div class="label">今日营收</div><div class="value ok">${C(n.revenueTodayCents)}</div></div>
        ${c?`<div class="stat stat-click" role="button" tabindex="0" onclick="navigate('finance')" title="点击查看 COGS 报表"><div class="label">今日毛利</div><div class="value ok">${C(c.grossMarginTodayCents)}</div></div>`:""}
        ${c?`<div class="stat stat-click" role="button" tabindex="0" onclick="navigate('finance')"><div class="label">今日 COGS</div><div class="value">${C(c.cogsTodayCents)}</div></div>`:""}
        ${c?`<div class="stat"><div class="label">今日报损</div><div class="value ${c.writeOffTodayCents>0?"warn":""}">${C(c.writeOffTodayCents)}</div></div>`:""}
        ${l("待审争议",n.disputeOpen,"warn","disputes")}
        ${l("SLA超时争议",n.disputeOverdue??0,n.disputeOverdue>0?"warn":"","disputes")}
        ${l("SLA临期争议",n.disputeNearSla??0,n.disputeNearSla>0?"warn":"","disputes")}
        ${l("待上传会话",n.sessionWaitingUpload??0,"warn","upload-queue")}
        ${l("低库存 SKU",n.lowStockSkuCount??0,n.lowStockSkuCount>0?"warn":"","replenishment","lowStock")}
        ${l("临期批次",n.nearExpiryLotCount??0,n.nearExpiryLotCount>0?"warn":"","replenishment")}
        ${l("过期库存",n.expiredLotCount??0,n.expiredLotCount>0?"warn":"","replenishment")}
        ${l("待下架",n.pullOffOpenCount??0,n.pullOffOpenCount>0?"warn":"","replenishment")}
        ${l("账实差异货道",n.slotDiscrepancyCount??0,n.slotDiscrepancyCount>0?"warn":"","devices","slotDiscrepancy")}
        ${l("待分账",n.pendingSplitCount??0,n.pendingSplitCount>0?"warn":"","merchants","pendingSplit")}
        <div class="stat"><div class="label">24h 开门成功率</div><div class="value ok">${ue(n.doorSuccessRate24h)}</div></div>
        ${l("24h 争议率",ue(n.disputeRate24h),"","disputes")}
        <div class="stat"><div class="label">24h 自动识别率</div><div class="value ok">${ue(n.recognitionAutoRate24h)}</div></div>
      </div>
      ${Qa(n,a,s)}
      ${i&&i.length?`
      <div class="card">
        <div class="pane-head">
          <h3 style="margin:0;font-size:1rem;color:var(--text)">最新动态</h3>
          <button class="btn-ghost btn-sm" onclick="navigate('audit')">操作日志</button>
        </div>
        ${typeof renderAuditTableHtml=="function"?renderAuditTableHtml(i):""}
      </div>`:""}`}catch(n){if(K!==e)return;W(t,n)}}async function Cn(){const t=document.getElementById("pageContent"),e="finance";try{const n=await $("/api/v2/ops/admin/finance/report?days=7","GET");if(K!==e)return;const a=n.summary||{},s=(n.daily||[]).map(c=>`
      <tr>
        <td>${o(c.date)}</td>
        <td>${C(c.revenueCents)}</td>
        <td>${C(c.cogsCents)}</td>
        <td class="${c.grossMarginCents>=0?"ok-text":"warn-text"}">${C(c.grossMarginCents)}</td>
        <td>${C(c.writeOffCents)}</td>
      </tr>`).join(""),i=(n.topSkus||[]).map(c=>`
      <tr>
        <td>${o(c.skuName||c.skuId)}</td>
        <td><code>${o(c.skuId)}</code></td>
        <td>${o(c.qtySold)}</td>
        <td>${C(c.revenueCents)}</td>
        <td>${C(c.cogsCents)}</td>
        <td class="${c.grossMarginCents>=0?"ok-text":"warn-text"}">${C(c.grossMarginCents)}</td>
      </tr>`).join("");t.innerHTML=`
      <div class="dashboard-head">
        <div>
          <h3 class="dashboard-head-title">财务 / COGS</h3>
          <p class="dashboard-head-sub">基于订单行 unitCostCents（SKU 采购成本 purchase_cost_cents）· 近 7 日明细</p>
        </div>
        ${D("loadFinancePage()")}
      </div>
      <div class="stats">
        <div class="stat"><div class="label">今日营收</div><div class="value ok">${C(a.revenueTodayCents)}</div></div>
        <div class="stat"><div class="label">今日 COGS</div><div class="value">${C(a.cogsTodayCents)}</div></div>
        <div class="stat"><div class="label">今日毛利</div><div class="value ok">${C(a.grossMarginTodayCents)}</div></div>
        <div class="stat"><div class="label">今日报损</div><div class="value ${a.writeOffTodayCents>0?"warn":""}">${C(a.writeOffTodayCents)}</div></div>
        <div class="stat"><div class="label">累计营收</div><div class="value">${C(a.revenueTotalCents)}</div></div>
        <div class="stat"><div class="label">累计 COGS</div><div class="value">${C(a.cogsTotalCents)}</div></div>
        <div class="stat"><div class="label">累计毛利</div><div class="value ok">${C(a.grossMarginTotalCents)}</div></div>
      </div>
      <div class="card">
        <h3 style="margin-top:0">近 7 日趋势</h3>
        <table class="table">
          <thead><tr><th>日期</th><th>营收</th><th>COGS</th><th>毛利</th><th>报损</th></tr></thead>
          <tbody>${s||'<tr><td colspan="5" class="meta">暂无数据</td></tr>'}</tbody>
        </table>
      </div>
      <div class="card">
        <h3 style="margin-top:0">SKU 毛利 Top 20（近 7 日）</h3>
        <p class="meta">请在「商品管理」维护采购成本（purchase_cost_cents），新订单结算时自动写入 COGS</p>
        <table class="table">
          <thead><tr><th>商品</th><th>SKU</th><th>销量</th><th>营收</th><th>COGS</th><th>毛利</th></tr></thead>
          <tbody>${i||'<tr><td colspan="6" class="meta">暂无销售</td></tr>'}</tbody>
        </table>
      </div>`}catch(n){if(K!==e)return;W(t,n)}}async function Bn(t,e){const n=await fetch(xt+t,{headers:{Authorization:"Bearer "+z}});if(n.status===401||n.status===403)throw g({status:n.status,message:"登录已失效"}),new Error("登录已失效");if(!n.ok)throw new Error("导出失败");const a=await n.blob(),s=URL.createObjectURL(a),i=document.createElement("a");i.href=s,i.download=e,i.click(),URL.revokeObjectURL(s)}async function ee(){const t=document.getElementById("pageContent"),e="devices";M("devices");try{const n=await $("/api/v2/ops/admin/devices","GET");if(K!==e)return;t.innerHTML=`
      <div class="card">
        <div class="filters">
          ${J("device.create","注册新设备","showDeviceForm()","btn-primary")}
          ${N("devices")}
          ${D("loadDevices()")}
        </div>
      </div>
      ${n.length?`
      <div class="card" style="padding:0;overflow:hidden">
        ${x("devices",`<table>
          <thead><tr>
            ${F("devices")}
            <th>设备ID</th><th>名称</th><th>商户</th><th>类型</th><th>状态</th><th>活跃会话</th><th>最后心跳</th><th>操作</th>
          </tr></thead>
          <tbody>${n.map(a=>`
            ${G("devices",a.deviceId)}
            ${q("devices",a.deviceId)}
            <td><code>${o(a.deviceId)}</code></td>
            <td>${o(a.deviceName||"-")}</td>
            <td>${o(a.merchantName||a.merchantId||"-")}</td>
            <td>${o(Ca(a.deviceType))}</td>
            <td>${Ce(a.onlineStatus)}</td>
            <td>${a.activeSessionId?`${o(a.activeSessionId)}<br>${Ln(a.activeSessionState)}`:"-"}</td>
            <td>${Q(a.updatedAt)}</td>
            <td onclick="event.stopPropagation()">${T("ops:device:edit")?`<button class="btn-ghost btn-sm" onclick='showDeviceForm(${JSON.stringify(a)})'>编辑</button>`:""}
              <button class="btn-ghost btn-sm" onclick="viewDeviceDetail('${d(a.deviceId)}')">详情</button></td>
          </tr>`).join("")}</tbody>
        </table>`)}
      </div>`:`<div class="card">${P("暂无设备","点击「注册新设备」添加第一台柜机","loadDevices()")}</div>`}`,B("devices"),nt(),wo()}catch(n){if(K!==e)return;W(t,n)}}async function Ss(t){const e=!!t;let n='<option value="">未绑定</option>';try{const a=await $("/api/v2/ops/admin/merchants","GET");n+=(a||[]).map(s=>`<option value="${d(s.merchantId)}" ${e&&t.merchantId===s.merchantId?"selected":""}>${o(s.merchantName)} (${o(s.merchantId)})</option>`).join("")}catch{}mt(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>${e?"编辑设备":"注册新设备"}</h3>
        <label>设备ID</label>
        <input id="dfId" value="${e?d(t.deviceId):""}" ${e?"disabled":""} placeholder="CAB-002">
        <label>设备名称</label>
        <input id="dfName" value="${e?d(t.deviceName||""):""}" placeholder="1号柜">
        <label>所属商户</label>
        <select id="dfMerchant">${n}</select>
        <label>设备类型</label>
        <input id="dfType" value="${e?d(t.deviceType||"AI_CABINET_V1"):"AI_CABINET_V1"}">
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveDevice(event, ${e})">保存</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`)}function Es(t){return{FULL:"满",OK:"正常",LOW:"低库存",OOS:"缺货",DISABLED:"未启用"}[t]||t||"-"}function Ts(t){return`<span class="badge ${t==="FULL"||t==="OK"?"badge-active":t==="LOW"?"badge-warn":t==="OOS"?"badge-danger":"badge-muted"}">${o(Es(t))}</span>`}async function Ls(t){if(T("ops:device:edit"))try{const e=await $("/api/v2/ops/admin/devices/"+encodeURIComponent(t)+"/slots/apply-template","POST");r(`已套用模板，新增 ${e} 个货道`,"ok"),Pt(t)}catch(e){g(e)||r("套用模板失败: "+e.message,"err")}}async function Pt(t){var e,n,a;try{const[s,i,c]=await Promise.all([$("/api/v2/ops/admin/devices/"+encodeURIComponent(t)+"/detail","GET"),$("/api/v2/ops/admin/slots/discrepancies?deviceId="+encodeURIComponent(t),"GET").catch(()=>[]),$("/api/v2/ops/admin/replenishment/suggest?deviceId="+encodeURIComponent(t),"GET").catch(()=>[])]),l=s.metrics||{},u=s.slots||[],p=u.reduce((f,S)=>Math.max(f,S.rowNo||0),0),m=u.reduce((f,S)=>Math.max(f,S.colNo||0),0),I=T("ops:device:edit"),k=T("ops:replenishment:edit"),L=[];for(let f=1;f<=Math.max(p,1);f++)for(let S=1;S<=Math.max(m,1);S++){const b=u.find(R=>R.rowNo===f&&R.colNo===S);if(!b)continue;const E=b.hasDiscrepancy?`<div class="slot-diff">账${b.bookQty} / 实${b.lastPhysicalQty} (${b.qtyDiff>0?"+":""}${b.qtyDiff})</div>`:"",w=I?`onclick="showSlotEditor('${d(t)}', '${d(b.slotCode)}')"`:k?`onclick="promptSlotStocktakeFor('${d(t)}','${d(b.slotCode)}',${b.bookQty})"`:"";L.push(`
          <div class="slot-cell slot-${d((b.stockStatus||"disabled").toLowerCase())}${b.hasDiscrepancy?" slot-mismatch":""} ${w?"slot-clickable":""}" ${w} title="${o(b.assignedSkuName||b.assignedSkuId||"未配置")}">
            <div class="slot-code">${o(b.slotCode)}</div>
            <div class="slot-sku">${o(b.assignedSkuName||b.assignedSkuId||"-")}</div>
            <div class="slot-qty">${b.bookQty}/${b.parLevel||"-"}</div>
            <div class="slot-meta">${Ts(b.stockStatus)} ${b.fillRatePct}%</div>
            ${E}
          </div>`)}const _=(c||[]).map(f=>`
      <tr>
        <td>${o(f.skuId)}</td>
        <td>${o(f.currentQty)}</td>
        <td>${o(f.inTransitQty??0)}</td>
        <td>${o(f.suggestQty)}</td>
        <td>${o(f.soldQty7d??0)}</td>
        <td>${o(f.ropPoint??0)}</td>
        <td><span class="badge badge-active">${o(f.suggestReason||"PAR")}</span></td>
      </tr>`).join(""),h=(i||[]).map(f=>`
      <tr>
        <td><code>${o(f.slotCode)}</code></td>
        <td>${o(f.assignedSkuName||f.assignedSkuId||"-")}</td>
        <td>${f.bookQty}</td>
        <td>${f.physicalQty}</td>
        <td class="${f.qtyDiff!==0?"warn-text":""}">${f.qtyDiff>0?"+":""}${f.qtyDiff}</td>
        <td>${Q(f.lastPhysicalAt)}</td>
        <td>${k?`<button class="btn-ghost btn-sm" onclick="promptSlotStocktakeFor('${d(t)}','${d(f.slotCode)}',${f.bookQty})">重盘</button>`:"-"}</td>
      </tr>`).join(""),y=(s.skuInventory||[]).map(f=>`
      <tr><td>${o(f.skuId)}</td><td>${f.quantity}/${f.capacity}</td><td>${o(f.lowThreshold)}</td></tr>`).join("");mt(`
      <div class="modal-backdrop device-detail-backdrop" onclick="closeModal(event)">
        <div class="modal modal-wide" onclick="event.stopPropagation()">
          <div class="device-detail-head">
            <div>
              <h3>${o(((e=s.device)==null?void 0:e.deviceName)||t)}</h3>
              <p class="meta"><code>${o(t)}</code> · ${Ce((n=s.device)==null?void 0:n.onlineStatus)} · 心跳 ${Q((a=s.device)==null?void 0:a.updatedAt)}</p>
            </div>
            <button type="button" class="btn-ghost btn-sm" data-modal-cancel onclick="closeModal()">关闭</button>
          </div>
          <div class="device-kpi-grid">
            <div class="kpi-card"><div class="kpi-label">补货率</div><div class="kpi-value">${l.fillRatePct??0}%</div></div>
            <div class="kpi-card"><div class="kpi-label">缺货率</div><div class="kpi-value">${l.oosRatePct??0}%</div></div>
            <div class="kpi-card"><div class="kpi-label">缺货通道</div><div class="kpi-value">${l.oosSlotCount??0}</div></div>
            <div class="kpi-card"><div class="kpi-label">低库存通道</div><div class="kpi-value">${l.lowStockSlotCount??0}</div></div>
            <div class="kpi-card"><div class="kpi-label">库存准确率</div><div class="kpi-value">${l.inventoryAccuracyPct??100}%</div></div>
            <div class="kpi-card"><div class="kpi-label">上次补货</div><div class="kpi-value kpi-sm">${l.lastRestockAt?Q(l.lastRestockAt):"暂无"}</div></div>
          </div>
          <div class="pane-head">
            <h4 style="margin:0">陈列图（货道）</h4>
            <div>
              ${I&&!u.length?`<button type="button" class="btn-ghost btn-sm" onclick="applyPlanogramTemplate('${d(t)}')">套用默认模板</button>`:""}
              ${I?`<button type="button" class="btn-primary btn-sm" onclick="showSlotEditor('${d(t)}', null)">添加货道</button>`:""}
            </div>
          </div>
          <p class="meta">${I?"点击货道可编辑配置；":""}${k?"可盘点更新实测数量":""}</p>
          <div class="slot-grid">${L.length?L.join(""):'<p class="meta">暂无货道配置</p>'}</div>
          ${h?`
          <h4 style="margin-top:16px;color:var(--warn)">账实差异告警 (${i.length})</h4>
          <table class="table"><thead><tr><th>货道</th><th>SKU</th><th>账面</th><th>实测</th><th>差异</th><th>盘点时间</th><th>操作</th></tr></thead><tbody>${h}</tbody></table>`:""}
          <h4 style="margin-top:16px">SKU 汇总库存</h4>
          ${y?`<table class="table"><thead><tr><th>SKU</th><th>数量/容量</th><th>低库存线</th></tr></thead><tbody>${y}</tbody></table>`:'<p class="meta">暂无</p>'}
          ${(c||[]).length?`
          <h4 style="margin-top:16px">动销 ROP 补货建议</h4>
          <table class="table"><thead><tr><th>SKU</th><th>账面</th><th>在途</th><th>建议量</th><th>7日销量</th><th>ROP点</th><th>策略</th></tr></thead><tbody>${_}</tbody></table>`:""}
          <div class="filters" style="margin-top:12px">
            ${k?`<button type="button" class="btn-ghost btn-sm" onclick="promptSlotStocktake('${d(t)}')">通道盘点</button>`:""}
            ${k?`<button type="button" class="btn-ghost btn-sm" onclick="closeModal();navigate('replenishment')">去补货管理</button>`:""}
          </div>
        </div>
      </div>`)}catch(s){g(s)||r("加载设备详情失败: "+s.message,"err")}}async function Cs(){try{const e=(await $("/api/v2/ops/admin/slots/discrepancies","GET")||[]).map(n=>`
      <tr>
        <td><button class="btn-link" onclick="closeModal();viewDeviceDetail('${d(n.deviceId)}')">${o(n.deviceName||n.deviceId)}</button></td>
        <td><code>${o(n.deviceId)}</code></td>
        <td><code>${o(n.slotCode)}</code></td>
        <td>${o(n.assignedSkuName||n.assignedSkuId||"-")}</td>
        <td>${n.bookQty}</td>
        <td>${n.physicalQty}</td>
        <td class="warn-text">${n.qtyDiff>0?"+":""}${n.qtyDiff}</td>
        <td>${Q(n.lastPhysicalAt)}</td>
      </tr>`).join("");mt(`
      <div class="modal-backdrop" onclick="closeModal(event)">
        <div class="modal modal-wide" onclick="event.stopPropagation()">
          <div class="device-detail-head">
            <h3>账实差异货道</h3>
            <button type="button" class="btn-ghost btn-sm" data-modal-cancel onclick="closeModal()">关闭</button>
          </div>
          ${e?`<table class="table"><thead><tr><th>设备</th><th>ID</th><th>货道</th><th>SKU</th><th>账面</th><th>实测</th><th>差异</th><th>盘点时间</th></tr></thead><tbody>${e}</tbody></table>`:P("暂无账实差异","完成通道盘点后将在此显示账面与实测不一致的货道","closeModal()")}
        </div>
      </div>`)}catch(t){g(t)||r("加载差异告警失败: "+t.message,"err")}}async function Bs(t,e){let n=null;e&&(n=(await $("/api/v2/ops/admin/devices/"+encodeURIComponent(t)+"/slots","GET")||[]).find(u=>u.slotCode===e)||null);let a='<option value="">未绑定</option>';try{const l=await $("/api/v2/ops/admin/skus","GET");a+=(l||[]).map(u=>`<option value="${d(u.skuId)}" ${n&&n.assignedSkuId===u.skuId?"selected":""}>${o(u.skuName)} (${o(u.skuId)})</option>`).join("")}catch{}const s=!!n,i=(n==null?void 0:n.rowNo)||1,c=(n==null?void 0:n.colNo)||1;mt(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>${s?"编辑货道":"添加货道"} · ${o(t)}</h3>
        <div class="form-grid">
          <div><label>货道编号</label>
            <input id="seCode" value="${d((n==null?void 0:n.slotCode)||"")}" ${s?"disabled":""} placeholder="A1"></div>
          <div><label>行</label><input id="seRow" type="number" min="1" value="${i}"></div>
          <div><label>列</label><input id="seCol" type="number" min="1" value="${c}"></div>
          <div><label>类型</label>
            <select id="seType">
              <option value="SHELF" ${!n||n.slotType==="SHELF"?"selected":""}>层架 SHELF</option>
              <option value="HOOK" ${(n==null?void 0:n.slotType)==="HOOK"?"selected":""}>挂钩 HOOK</option>
              <option value="BASKET" ${(n==null?void 0:n.slotType)==="BASKET"?"selected":""}>篮筐 BASKET</option>
            </select></div>
          <div style="grid-column:1/-1"><label>绑定 SKU</label><select id="seSku">${a}</select></div>
          <div><label>标准容量 (PAR)</label><input id="sePar" type="number" min="0" value="${(n==null?void 0:n.parLevel)??8}"></div>
          <div><label>补货线 (MIN)</label><input id="seMin" type="number" min="0" value="${(n==null?void 0:n.minLevel)??2}"></div>
          <div><label>最大容量</label><input id="seMax" type="number" min="0" value="${(n==null?void 0:n.maxLevel)??(n==null?void 0:n.parLevel)??8}"></div>
          <div><label>启用</label>
            <select id="seEnabled">
              <option value="true" ${!n||n.enabled!==!1?"selected":""}>是</option>
              <option value="false" ${n&&n.enabled===!1?"selected":""}>否</option>
            </select></div>
        </div>
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveSlotConfig(event, '${d(t)}', ${s})">保存</button>
          ${s&&T("ops:device:edit")?`<button type="button" class="btn-ghost" onclick="deleteSlotConfig('${d(t)}','${d(n.slotCode)}')">删除货道</button>`:""}
          ${T("ops:replenishment:edit")&&s?`<button type="button" class="btn-ghost" onclick="promptSlotStocktakeFor('${d(t)}','${d(n.slotCode)}',${n.bookQty??0})">盘点此货道</button>`:""}
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal();viewDeviceDetail('${d(t)}')">返回详情</button>
        </div>
      </div>
    </div>`)}async function Ps(t,e,n){await U(t,async()=>{var p;const a=(((p=document.getElementById("seCode"))==null?void 0:p.value)||"").trim().toUpperCase(),s=parseInt(document.getElementById("seRow").value,10),i=parseInt(document.getElementById("seCol").value,10),c=parseInt(document.getElementById("sePar").value,10),l=parseInt(document.getElementById("seMin").value,10),u=parseInt(document.getElementById("seMax").value,10);if(!a||Number.isNaN(s)||Number.isNaN(i)||Number.isNaN(c)){r("请填写货道编号、行列与标准容量","err");return}try{await $("/api/v2/ops/admin/devices/"+encodeURIComponent(e)+"/slots","PUT",[{slotCode:a,rowNo:s,colNo:i,slotType:document.getElementById("seType").value,assignedSkuId:document.getElementById("seSku").value||null,parLevel:c,minLevel:l,maxLevel:Number.isNaN(u)?c:u,enabled:document.getElementById("seEnabled").value==="true"}]),r("货道已保存","ok"),ot(),Pt(e)}catch(m){g(m)||r("保存失败: "+m.message,"err")}})}async function Rs(t,e){if(await et(`确认删除货道 ${e}？仅无账面库存时可删除。`,{title:"删除货道"}))try{await $("/api/v2/ops/admin/devices/"+encodeURIComponent(t)+"/slots/"+encodeURIComponent(e),"DELETE"),r("货道已删除","ok"),ot(),Pt(t)}catch(n){g(n)||r("删除失败: "+n.message,"err")}}async function As(t,e,n){const a=prompt(`货道 ${e} 实测数量
当前账面：${n}`,String(n));if(a==null||a==="")return;const s=parseInt(a,10);if(Number.isNaN(s)||s<0){r("请输入有效数量","err");return}try{await $("/api/v2/ops/admin/devices/"+encodeURIComponent(t)+"/slots/stocktake","POST",{slotCode:e.trim(),physicalQty:s}),r("盘点已记录","ok"),ot(),Pt(t)}catch(i){g(i)||r("盘点失败: "+i.message,"err")}}async function Ds(t){const e=prompt("货道编号（如 A1）");if(!e)return;const n=prompt("实测数量");if(n==null||n==="")return;const a=parseInt(n,10);if(Number.isNaN(a)||a<0){r("请输入有效数量","err");return}try{await $("/api/v2/ops/admin/devices/"+encodeURIComponent(t)+"/slots/stocktake","POST",{slotCode:e.trim(),physicalQty:a}),r("盘点已记录","ok"),Pt(t)}catch(s){g(s)||r("盘点失败: "+s.message,"err")}}async function Ms(t,e){await U(t,async()=>{var c;const n=document.getElementById("dfId").value.trim(),a=document.getElementById("dfName").value.trim(),s=document.getElementById("dfType").value.trim(),i=((c=document.getElementById("dfMerchant"))==null?void 0:c.value)||"";try{e?await $("/api/v2/ops/admin/devices/"+encodeURIComponent(n),"PATCH",{deviceName:a,deviceType:s,merchantId:i}):await $("/api/v2/ops/admin/devices","POST",{deviceId:n,deviceName:a,deviceType:s,merchantId:i||null}),ot(),r("保存成功","ok"),ee()}catch(l){g(l)||r("保存失败: "+l.message,"err")}})}function Os(){M("sessions"),document.getElementById("pageContent").innerHTML=`
    <div class="card">
      <div class="filters">
        <div><label>设备ID</label><input id="sfDevice" value="${V.deviceId}" placeholder="CAB-001" oninput="debouncedSearchSessions()"></div>
        <div><label>状态</label>
          <select id="sfState">
            <option value="">全部</option>
            ${["CREATED","OPENING","SHOPPING","RECOGNIZING","WAITING_UPLOAD","SETTLING","COMPLETED","DISPUTED","FAILED","CANCELLED"].map(t=>`<option value="${t}" ${V.state===t?"selected":""}>${o(Je(t))}</option>`).join("")}
          </select>
        </div>
        <div><button class="btn-primary" onclick="searchSessions()">查询</button></div>
        <div>${D("fetchSessions()")}</div>
        <div><button class="btn-ghost" onclick="exportSessionsCsv()">导出 CSV</button></div>
        ${N("sessions",`<button type="button" class="btn-ghost btn-sm" onclick="selClear('sessions')">清除选择</button>`)}
      </div>
      <div id="sessionTable"></div>
    </div>`,j(document.getElementById("sessionTable"),7,6),Ht()}async function Pn(){V.deviceId=document.getElementById("sfDevice").value.trim(),V.state=document.getElementById("sfState").value,V.page=0,Ht()}function Ns(t){const e=[];return t.orderId&&T("ops:order:list")&&e.push(`<button type="button" class="btn-ghost btn-sm" onclick="showOrderDetail('${d(t.orderId)}')">订单</button>`),t.state==="DISPUTED"&&T("ops:dispute")&&e.push(`<button type="button" class="btn-ghost btn-sm" onclick="openDisputeForSession('${d(t.sessionId)}')">争议</button>`),!["COMPLETED","CANCELLED"].includes(t.state)&&T("ops:session:cancel")&&e.push(`<button type="button" class="btn-danger btn-sm" onclick="cancelSession('${d(t.sessionId)}')">取消</button>`),e.length?`<div class="row-actions">${e.join("")}</div>`:'<span class="meta">-</span>'}function Us(t){H.sessionId=t,H.page=0,Bt("disputes")}async function Ht(){const t=document.getElementById("sessionTable");if(t){j(t,7,6);try{const e=new URLSearchParams({page:V.page,size:V.size,...V.deviceId?{deviceId:V.deviceId}:{},...V.state?{state:V.state}:{}}),n=await $("/api/v2/ops/admin/sessions?"+e,"GET");if(!n.items.length){t.innerHTML=P("暂无会话","调整筛选条件或等待用户开门购物","fetchSessions()");return}const a=i=>!["COMPLETED","CANCELLED"].includes(i.state),s=Ee(n.items,lt.sessions.field,lt.sessions.dir);t.innerHTML=x("sessions",`
      <table class="table-sessions">
        <thead><tr>
          ${F("sessions")}
          <th>会话ID</th><th>用户</th><th>设备</th><th>状态</th><th>上传</th><th>订单</th><th>视频</th>
          ${kt("sessions","createdAt","创建时间")}<th class="col-actions">操作</th>
        </tr></thead>
        <tbody>${s.map(i=>`
          ${G("sessions",i.sessionId)}
          ${q("sessions",i.sessionId)}
          <td><code>${o(i.sessionId)}</code></td>
          <td>${o(i.userId)}</td>
          <td>${o(i.deviceId)}</td>
          <td>${Ln(i.state)}</td>
          <td>${o(Xe(i.uploadStatus))}</td>
          <td>${i.orderId?`<code class="meta">${o(i.orderId)}</code>`:"-"}</td>
          <td onclick="event.stopPropagation()">${i.videoUri||i.videoPreviewUrl?`<button type="button" class="btn-ghost btn-sm" onclick="showSessionVideo('${d(i.sessionId)}', '${d(i.videoUri||"")}')">${Ie(i.videoUri)}</button>`:"-"}</td>
          <td>${Q(i.createdAt)}</td>
          <td class="col-actions" onclick="event.stopPropagation()">${Ns(i)}</td>
        </tr>`).join("")}</tbody>
      </table>`)+Rt(n,"session"),B("sessions"),Le("sessions")}catch(e){W(t,e,!1)}}}async function xs(){try{const t=new URLSearchParams({...V.deviceId?{deviceId:V.deviceId}:{},...V.state?{state:V.state}:{}});await Bn("/api/v2/ops/admin/sessions/export?"+t,"sessions.csv")}catch(t){g(t)||r(t.message,"err")}}async function _s(t){if(await et("确认取消会话 "+t+"？设备将可再次开门。",{title:"取消会话",danger:!0}))try{await $("/api/v2/ops/admin/sessions/"+t+"/cancel","POST"),r("会话已取消","ok"),Ht(),K==="devices"&&ee()}catch(e){g(e)||r("失败: "+e.message,"err")}}function Hs(){M("orders"),document.getElementById("pageContent").innerHTML=`
    <div class="card">
      <div class="filters">
        <div><label>设备ID</label><input id="ofDevice" value="${st.deviceId}" placeholder="可选" oninput="debouncedSearchOrders()"></div>
        <div><button class="btn-primary" onclick="searchOrders()">查询</button></div>
        <div>${D("fetchOrders()")}</div>
        <div><button class="btn-ghost" onclick="exportOrdersCsv()">导出 CSV</button></div>
        ${N("orders")}
      </div>
      <div id="orderTable"></div>
    </div>`,j(document.getElementById("orderTable"),8,6),ne()}function Rn(){st.deviceId=document.getElementById("ofDevice").value.trim(),st.page=0,ne()}async function Fs(){try{const t=new URLSearchParams(st.deviceId?{deviceId:st.deviceId}:{});await Bn("/api/v2/ops/admin/orders/export?"+t,"orders.csv")}catch(t){g(t)||r(t.message,"err")}}async function ne(){const t=document.getElementById("orderTable");if(t){j(t,8,6);try{const e=new URLSearchParams({page:st.page,size:st.size,...st.deviceId?{deviceId:st.deviceId}:{}}),n=await $("/api/v2/ops/admin/orders?"+e,"GET");if(!n.items.length){t.innerHTML=P("暂无订单","完成购物后会在此展示订单记录","fetchOrders()");return}const a=Ee(n.items,lt.orders.field,lt.orders.dir);t.innerHTML=x("orders",`
      <table>
        <thead><tr>
          ${F("orders")}
          <th>订单ID</th><th>会话</th>${kt("orders","userId","用户")}<th>设备</th>
          ${kt("orders","totalAmountCents","金额")}<th>商品行</th>
          ${kt("orders","createdAt","时间")}<th>操作</th>
        </tr></thead>
        <tbody>${a.map(s=>`
          ${G("orders",s.orderId)}
          ${q("orders",s.orderId)}
          <td><code>${o(s.orderId)}</code></td>
          <td>${o(s.sessionId)}</td>
          <td>${o(s.userId)}</td>
          <td>${o(s.deviceId)}</td>
          <td>${C(s.totalAmountCents)}</td>
          <td>${o(s.lineCount)}</td>
          <td>${Q(s.createdAt)}</td>
          <td onclick="event.stopPropagation()"><button class="btn-ghost btn-sm" onclick="showOrderDetail('${d(s.orderId)}')">详情</button></td>
        </tr>`).join("")}</tbody>
      </table>`)+Rt(n,"order"),B("orders"),Le("orders")}catch(e){W(t,e,!1)}}}function Rt(t,e){return Zt(t,e)}function qs(t,e){var a;const n=parseInt(e,10)||20;t==="session"?V.size=n:t==="user"?ut.size=n:t==="audit"?Qt.size=n:t==="recharge"?tt.size=n:t==="dispute"?H.size=n:t==="upload"?window.uploadQueueFilters&&(window.uploadQueueFilters.size=n):t==="merchantSplit"?window.merchantSplitFilters&&(window.merchantSplitFilters.size=n):t==="rbacOp"?(a=window._rbacState)!=null&&a.operatorFilters&&(window._rbacState.operatorFilters.size=n):st.size=n,Be(t,0)}function Gs(t,e){const n=Math.max(1,parseInt(e,10)||1)-1;Be(t,n)}function Be(t,e){var n;t==="session"?(V.page=Math.max(0,e),Ht()):t==="user"?(ut.page=Math.max(0,e),At()):t==="audit"?(Qt.page=Math.max(0,e),Ae()):t==="recharge"?(tt.page=Math.max(0,e),ae()):t==="dispute"?(H.page=Math.max(0,e),Ct()):t==="upload"?(window.uploadQueueFilters&&(window.uploadQueueFilters.page=Math.max(0,e)),typeof fetchUploadQueue=="function"&&fetchUploadQueue()):t==="merchantSplit"?(window.merchantSplitFilters&&(window.merchantSplitFilters.page=Math.max(0,e)),typeof fetchMerchantSplits=="function"&&fetchMerchantSplits()):t==="rbacOp"?((n=window._rbacState)!=null&&n.operatorFilters&&(window._rbacState.operatorFilters.page=Math.max(0,e)),typeof fetchRbacOperators=="function"&&fetchRbacOperators()):(st.page=Math.max(0,e),ne())}async function js(t){try{const e=await $("/api/v2/ops/admin/orders/"+t,"GET"),n=(e.lines||[]).map(a=>`<tr><td>${o(a.skuName)}</td><td>${o(a.skuId)}</td><td>${o(a.quantity)}</td><td><code>${o(a.batchNo||"-")}</code></td><td>${C(a.unitPriceCents)}</td><td>${C(a.lineAmountCents)}</td></tr>`).join("");mt(`
      <div class="modal-backdrop" onclick="closeModal(event)">
        <div class="modal" onclick="event.stopPropagation()">
          <h3>订单 ${o(e.orderId)}</h3>
          <div class="meta">会话 ${o(e.sessionId)} · 设备 ${o(e.deviceId)} · 用户 ${o(e.userId)}</div>
          <table style="margin-top:12px">
            <thead><tr><th>商品</th><th>SKU</th><th>数量</th><th>批次</th><th>单价</th><th>小计</th></tr></thead>
            <tbody>${n}</tbody>
          </table>
          <p style="margin-top:12px;font-weight:700">合计 ${C(e.totalAmountCents)}</p>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">关闭</button>
        </div>
      </div>`)}catch(e){g(e)||r("加载失败: "+e.message,"err")}}function ot(t){t&&t.target!==t.currentTarget||(ye(),we(),document.getElementById("modalRoot").classList.add("hidden"),document.getElementById("modalRoot").innerHTML="")}function mt(t,e){document.getElementById("modalRoot").innerHTML=t,document.getElementById("modalRoot").classList.remove("hidden"),Se(e||(()=>ot()))}async function An(){try{pt=Dn(await $("/api/v2/ops/admin/skus","GET"))}catch{pt=[{skuId:"SKU-DEMO-001",skuName:"演示商品",priceCents:350,status:"ACTIVE",visionEnabled:!0}]}}function Pe(){M("skus"),document.getElementById("pageContent").innerHTML=`
    <div class="card">
      <div class="filters">
        ${J("sku.edit","新增商品","showSkuForm()","btn-primary")}
        ${J("sku.edit","编辑所选","editSelectedSku()","btn-ghost btn-sm")}
        ${N("skus")}
        ${D("loadSkusPage()")}
      </div>
      <div id="skuTable"></div>
    </div>`,j(document.getElementById("skuTable"),10,5),zs()}function Ks(){const t=ke("skus");if(t.length!==1){r("请勾选恰好 1 个商品再编辑","err");return}Mn(t[0])}function Vs(t){return t==="INACTIVE"?"下架":"上架"}function Ws(t,e){return t?`<img src="${d(t)}" alt="${d(e||"")}" class="sku-thumb" loading="lazy"
    referrerpolicy="no-referrer"
    onerror="this.replaceWith(Object.assign(document.createElement('span'),{className:'meta',textContent:'无图'}))">`:'<span class="meta">-</span>'}function Dn(t){return[...t||[]].sort((e,n)=>String(e.skuId).localeCompare(String(n.skuId),"zh-CN"))}function Mn(t){const e=pt.find(n=>n.skuId===t);if(!e){r("商品不存在或列表未刷新","err");return}On(e)}async function zs(){const t=document.getElementById("skuTable");if(t){j(t,9,5);try{const e=Dn(await $("/api/v2/ops/admin/skus","GET"));if(pt=e,!e.length){t.innerHTML=P("暂无商品","添加 SKU 后可在争议审核中选择商品","fetchSkusTable()");return}t.innerHTML=x("skus",`
      <table class="table-sku">
        <thead><tr>
          ${F("skus")}
          <th>SKU ID</th><th>名称</th><th>分类</th><th>价格</th><th>重量(g)</th><th>条码</th><th>状态</th><th>图片</th><th>操作</th>
        </tr></thead>
        <tbody>${e.map(n=>`
          ${G("skus",n.skuId)}
          ${q("skus",n.skuId)}
          <td><code>${o(n.skuId)}</code></td>
          <td>${o(n.skuName)}</td>
          <td>${o(n.category||"-")}</td>
          <td>${C(n.priceCents)}</td>
          <td>${n.weightGrams!=null?o(n.weightGrams):"-"}</td>
          <td>${o(n.barcode||"-")}</td>
          <td>${Vs(n.status)}${n.visionEnabled===!1?" · 无视觉":""}</td>
          <td>${Ws(n.imageUrl,n.skuName)}</td>
          <td onclick="event.stopPropagation()">${T("ops:sku:edit")?`<button type="button" class="btn-ghost btn-sm" onclick="showSkuFormById('${d(n.skuId)}')">编辑</button>`:"-"}</td>
        </tr>`).join("")}</tbody>
      </table>`),B("skus")}catch(e){W(t,e,!1)}}}function On(t){const e=!!t;mt(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" style="max-width:640px" onclick="event.stopPropagation()">
        <h3>${e?"编辑商品":"新增商品"}</h3>
        <label>SKU ID</label>
        <input id="skuId" value="${e?d(t.skuId):""}" ${e?"disabled":""} placeholder="SKU-XXX-001">
        <label>商品名称</label>
        <input id="skuName" value="${e?d(t.skuName):""}" placeholder="可乐 330ml">
        <div class="filters form-grid">
          <div><label>分类</label><input id="skuCategory" value="${e?d(t.category||""):""}" placeholder="饮料"></div>
          <div><label>条码</label><input id="skuBarcode" value="${e?d(t.barcode||""):""}" placeholder="6901234567890"></div>
          <div><label>价格（分）</label><input id="skuPrice" type="number" min="1" value="${e?t.priceCents:350}"></div>
          <div><label>采购成本（分）</label><input id="skuCost" type="number" min="0" value="${e&&t.purchaseCostCents!=null?t.purchaseCostCents:""}" placeholder="280"></div>
          <div><label>重量（克）</label><input id="skuWeight" type="number" min="0" value="${e&&t.weightGrams!=null?t.weightGrams:""}" placeholder="330"></div>
        </div>
        <p class="meta">价格/成本单位：分（350 = ¥3.50，成本用于 COGS 毛利报表）</p>
        <label>商品描述</label>
        <textarea id="skuDescription" rows="3" placeholder="规格、口味、包装说明等">${e?o(t.description||""):""}</textarea>
        <div class="filters">
          <div><label>状态</label>
            <select id="skuStatus">
              <option value="ACTIVE" ${!e||t.status!=="INACTIVE"?"selected":""}>上架</option>
              <option value="INACTIVE" ${e&&t.status==="INACTIVE"?"selected":""}>下架</option>
            </select>
          </div>
          <div style="display:flex;align-items:flex-end;padding-bottom:8px">
            <label style="display:flex;align-items:center;gap:8px;margin:0">
              <input id="skuVisionEnabled" type="checkbox" ${!e||t.visionEnabled!==!1?"checked":""}>
              参与视觉识别
            </label>
          </div>
        </div>
        <h4 style="margin:12px 0 8px">保质期 / 效期</h4>
        <div class="filters form-grid">
          <div><label>保质期（天）</label>
            <input id="skuShelfLife" type="number" min="0" value="${e&&t.shelfLifeDays!=null?t.shelfLifeDays:""}" placeholder="180"></div>
          <div><label>临期提醒（天）</label>
            <input id="skuNearExpiry" type="number" min="1" value="${e?t.nearExpiryDays??7:7}"></div>
          <div><label>到期前禁售（天）</label>
            <input id="skuBlockSale" type="number" min="0" value="${e?t.blockSaleDaysBeforeExpiry??0:0}"></div>
          <div><label>存储类型</label>
            <select id="skuStorageType">
              <option value="AMBIENT" ${!e||t.storageType==="AMBIENT"||!t.storageType?"selected":""}>常温</option>
              <option value="CHILLED" ${e&&t.storageType==="CHILLED"?"selected":""}>冷藏</option>
              <option value="FROZEN" ${e&&t.storageType==="FROZEN"?"selected":""}>冷冻</option>
            </select>
          </div>
        </div>
        <label>图片 URL</label>
        <input id="skuImageUrl" value="${e?d(t.imageUrl||""):""}" placeholder="https://example.com/cola.jpg" oninput="previewSkuImage()">
        <div id="skuImagePreview" class="sku-preview-wrap">${e&&t.imageUrl?`<img src="${d(t.imageUrl)}" alt="预览" class="sku-preview" referrerpolicy="no-referrer">`:'<span class="meta">填写 URL 后显示预览</span>'}</div>
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveSku(event, ${e})">保存</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`)}function Qs(){var n;const t=(n=document.getElementById("skuImageUrl"))==null?void 0:n.value.trim(),e=document.getElementById("skuImagePreview");if(e){if(!t){e.innerHTML='<span class="meta">填写 URL 后显示预览</span>';return}e.innerHTML=`<img src="${d(t)}" alt="预览" class="sku-preview"
    onerror="this.parentElement.innerHTML='<span class=\\'meta\\'>图片无法加载</span>'">`}}async function Ys(t,e){await U(t,async()=>{const n=document.getElementById("skuId").value.trim(),a=document.getElementById("skuName").value.trim(),s=parseInt(document.getElementById("skuPrice").value,10),i=document.getElementById("skuCost").value.trim(),c=i?parseInt(i,10):null,l=document.getElementById("skuWeight").value.trim(),u=l?parseInt(l,10):null,p=document.getElementById("skuImageUrl").value.trim(),m=document.getElementById("skuCategory").value.trim(),I=document.getElementById("skuBarcode").value.trim(),k=document.getElementById("skuDescription").value.trim(),L=document.getElementById("skuStatus").value,_=document.getElementById("skuVisionEnabled").checked,h=document.getElementById("skuShelfLife").value.trim(),y=h?parseInt(h,10):null,f=parseInt(document.getElementById("skuNearExpiry").value,10)||7,S=parseInt(document.getElementById("skuBlockSale").value,10)||0,b=document.getElementById("skuStorageType").value||"AMBIENT";if(!n||!a||!s){r("请填写 SKU、名称和价格","err");return}try{const E={skuId:n,skuName:a,priceCents:s,status:L,visionEnabled:_,nearExpiryDays:f,blockSaleDaysBeforeExpiry:S,storageType:b,...y!=null&&!Number.isNaN(y)?{shelfLifeDays:y}:{},...u!=null&&!Number.isNaN(u)?{weightGrams:u}:{},...c!=null&&!Number.isNaN(c)?{purchaseCostCents:c}:{},...p?{imageUrl:p}:{},...m?{category:m}:{},...I?{barcode:I}:{},...k?{description:k}:{}};e?await $("/api/v2/ops/admin/skus/"+encodeURIComponent(n),"PUT",E):await $("/api/v2/ops/admin/skus","POST",E),ot(),r("保存成功","ok"),Pe()}catch(E){g(E)||r("保存失败: "+E.message,"err")}})}function Js(){M("users"),document.getElementById("pageContent").innerHTML=`
    <div class="card">
      <div class="filters">
        <div><label>手机号</label><input id="ufPhone" value="${ut.phone}" placeholder="138" oninput="debouncedSearchUsers()"></div>
        <div><button class="btn-primary" onclick="searchUsers()">查询</button></div>
        <div>${D("fetchUsers()")}</div>
        ${N("users")}
      </div>
      <div id="userTable"></div>
    </div>`,j(document.getElementById("userTable"),8,6),At()}function Nn(){ut.phone=document.getElementById("ufPhone").value.trim(),ut.page=0,At()}async function At(){const t=document.getElementById("userTable");if(t){j(t,8,6);try{const e=new URLSearchParams({page:ut.page,size:ut.size,...ut.phone?{phone:ut.phone}:{}}),n=await $("/api/v2/ops/admin/users?"+e,"GET");if(!n.items.length){t.innerHTML=P("暂无用户","消费者通过小程序注册后会出现在此列表","fetchUsers()");return}const a=Ee(n.items,lt.users.field,lt.users.dir);t.innerHTML=x("users",`
      <table>
        <thead><tr>
          ${F("users")}
          ${kt("users","userId","userId")}<th>手机号</th><th>姓名</th><th>角色</th><th>实名</th>
          ${kt("users","balanceCents","余额")}${kt("users","createdAt","注册时间")}<th>操作</th>
        </tr></thead>
        <tbody>${a.map(s=>`
          ${G("users",s.userId)}
          ${q("users",s.userId)}
          <td>${o(s.userId)}</td>
          <td>${o(s.phoneNumber)}</td>
          <td>${o(s.name||"-")}</td>
          <td>${s.role==="OPERATOR"?'<span class="badge badge-active">运营</span>':"消费者"}</td>
          <td>${s.verified?"是":"否"}</td>
          <td>${C(s.balanceCents)}</td>
          <td>${Q(s.createdAt)}</td>
          <td onclick="event.stopPropagation()">${s.role==="OPERATOR"?T("ops:rbac:assign")?`<button class="btn-ghost btn-sm" onclick="showRbacAssignForUser(${s.userId})">分配角色</button>`:"-":`<span class="filters" style="gap:4px">
                ${T("ops:user:balance")?`<button class="btn-ghost btn-sm" onclick="showBalanceForm(${s.userId}, ${s.balanceCents})">调余额</button>`:""}
                ${T("ops:user:list")?s.verified?`<button class="btn-ghost btn-sm" onclick="showVerifyUserForm(${s.userId}, false, '${d(s.name||"")}')">取消实名</button>`:`<button class="btn-ghost btn-sm" onclick="showVerifyUserForm(${s.userId}, true, '')">标记实名</button>`:""}
              </span>`}</td>
        </tr>`).join("")}</tbody>
      </table>`)+Rt(n,"user"),B("users"),Le("users")}catch(e){W(t,e,!1)}}}function Xs(t,e){mt(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>调整余额 · userId ${t}</h3>
        <p class="meta">当前余额 ${C(e)}</p>
        <label>变动金额（分，正数充值/负数扣减）</label>
        <input id="deltaCents" type="number" value="1000" placeholder="1000 = 加10元">
        <p class="meta">例：1000 表示加 ¥10.00；-350 表示扣 ¥3.50</p>
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveBalance(event, ${t})">确认</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`)}async function Zs(t,e){await U(t,async()=>{const n=parseInt(document.getElementById("deltaCents").value,10);if(isNaN(n)||n===0){r("请输入有效金额","err");return}try{await $("/api/v2/ops/admin/users/"+e+"/balance","POST",{deltaCents:n}),ot(),At(),r("余额已更新","ok")}catch(a){g(a)||r("失败: "+a.message,"err")}})}async function Re(t,e,n){try{await $("/api/v2/ops/admin/users/"+t+"/verify","POST",{verified:e,...n?{realName:n}:{}}),At(),r("实名状态已更新","ok")}catch(a){g(a)||r("失败: "+a.message,"err")}}async function to(t,e,n){const a=e?`标记实名 · userId ${t}`:`取消实名 · userId ${t}`;if(!e)return await et(`确认取消用户 ${t} 的实名状态？`,{title:"取消实名",danger:!0})?Re(t,!1):void 0;mt(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>${o(a)}</h3>
        <label>真实姓名（可选）</label>
        <input id="verifyRealName" value="${d(n||"")}" placeholder="张三">
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveVerifyUser(event, ${t})">确认实名</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`)}async function eo(t,e){await U(t,async()=>{const n=document.getElementById("verifyRealName").value.trim();ot(),await Re(e,!0,n||void 0)})}function no(t){typeof openRbacUserAssign=="function"?openRbacUserAssign(t):Bt("rbac")}async function ao(){const t=document.getElementById("pageContent"),e="reports";M("reports");try{const n=await $("/api/v2/ops/admin/reports/devices","GET");if(K!==e)return;if(t.innerHTML=`
      <div class="card">
        <div class="filters">${N("reports")}${D("loadReportsPage()")}</div>
      </div>`,!n.length){t.innerHTML+=`<div class="card">${P("暂无设备报表","注册设备并产生订单后自动生成统计","loadReportsPage()")}</div>`;return}t.innerHTML+=`
      <div class="card" style="padding:0;overflow:hidden">
        ${x("reports",`<table>
          <thead><tr>
            ${F("reports")}
            <th>设备</th><th>状态</th><th>累计订单</th><th>累计营收</th>
            <th>今日订单</th><th>今日营收</th><th>累计会话</th><th>进行中</th>
          </tr></thead>
          <tbody>${n.map(a=>`
            ${G("reports",a.deviceId)}
            ${q("reports",a.deviceId)}
            <td><code>${o(a.deviceId)}</code><br><span class="meta">${o(a.deviceName||"-")}</span></td>
            <td>${Ce(a.onlineStatus)}</td>
            <td>${a.orderTotal}</td>
            <td>${C(a.revenueTotalCents)}</td>
            <td>${a.orderToday}</td>
            <td>${C(a.revenueTodayCents)}</td>
            <td>${a.sessionTotal}</td>
            <td>${a.sessionActive?'<span class="badge badge-active">是</span>':"-"}</td>
          </tr>`).join("")}</tbody>
        </table>`)}
      </div>`,B("reports")}catch(n){if(K!==e)return;W(t,n)}}function so(){M("audit"),document.getElementById("pageContent").innerHTML=`
    <div class="card">
      <div class="filters">
        ${N("audit")}
        ${D("fetchAuditLogs()")}
      </div>
      <div id="auditTable"></div>
    </div>`,j(document.getElementById("auditTable"),5,6),Ae()}async function Ae(){const t=document.getElementById("auditTable");if(t){j(t,5,6);try{const e=new URLSearchParams({page:Qt.page,size:Qt.size}),n=await $("/api/v2/ops/admin/audit-logs?"+e,"GET");if(!n.items.length){t.innerHTML=P("暂无操作记录","运营人员的敏感操作会记录在此","fetchAuditLogs()");return}t.innerHTML=(typeof renderAuditTableHtml=="function"?renderAuditTableHtml(n.items,"audit"):"")+Rt(n,"audit"),B("audit")}catch(e){W(t,e,!1)}}}function De(){M("recentAudit"),document.getElementById("pageContent").innerHTML=`
    <div class="card">
      <div class="filters">
        <button class="btn-ghost btn-sm ${Ut.mine?"":"active-tab"}" onclick="setRecentScope(false)">全部操作</button>
        <button class="btn-ghost btn-sm ${Ut.mine?"active-tab":""}" onclick="setRecentScope(true)">我的操作</button>
        ${N("recentAudit")}
        ${D("fetchRecentLogs()")}
        <button class="btn-ghost btn-sm" onclick="navigate('audit')">完整操作日志</button>
      </div>
      <div id="recentTable"></div>
    </div>`,Un()}function oo(t){Ut.mine=t,De()}async function Un(){const t=document.getElementById("recentTable");if(t){t.innerHTML='<p class="sub">加载中…</p>';try{const e=new URLSearchParams({size:Ut.size,mine:Ut.mine?"true":"false"}),n=await $("/api/v2/ops/admin/audit-logs/recent?"+e,"GET");t.innerHTML=typeof renderAuditTableHtml=="function"?renderAuditTableHtml(n,"recentAudit"):P("暂无操作记录","运营后台的敏感操作会记录在此","fetchRecentLogs()"),B("recentAudit")}catch(e){W(t,e,!1)}}}async function io(){const t=document.getElementById("pageContent"),e="disputes";M("disputes"),await An(),K===e&&(t.innerHTML=`
    <div class="card">
      <div class="filters">
        <div><label>状态</label>
          <select id="dfStatus">
            <option value="OPEN" ${H.status==="OPEN"?"selected":""}>待审核</option>
            <option value="RESOLVED" ${H.status==="RESOLVED"?"selected":""}>已结案</option>
            <option value="" ${H.status?"":"selected"}>全部</option>
          </select>
        </div>
        <div><label>会话ID</label><input id="dfSession" value="${d(H.sessionId)}" placeholder="可选"></div>
        <div><label>设备ID</label><input id="dfDevice" value="${d(H.deviceId)}" placeholder="CAB-001"></div>
        <div><button class="btn-primary" onclick="searchDisputes()">查询</button></div>
        <div>${D("fetchDisputes()")}</div>
        ${N("disputes")}
        <label class="filter-check"><input type="checkbox" title="全选" onchange="selToggleAll('disputes', this.checked)"> 全选</label>
      </div>
      <div id="disputeList"></div>
    </div>`,j(document.getElementById("disputeList"),1,4),Ct())}function co(){H.status=document.getElementById("dfStatus").value,H.sessionId=document.getElementById("dfSession").value.trim(),H.deviceId=document.getElementById("dfDevice").value.trim(),H.page=0,Ct()}async function Ct(){const t=document.getElementById("disputeList");if(t){j(t,1,4);try{await An();const e=new URLSearchParams({page:H.page,size:H.size,status:H.status||"ALL",...H.sessionId?{sessionId:H.sessionId}:{},...H.deviceId?{deviceId:H.deviceId}:{}}),n=await $("/api/v2/ops/disputes?"+e,"GET");if(!n.items.length){t.innerHTML=P("暂无争议工单","识别异常或用户申诉的工单会出现在此","fetchDisputes()");return}t.innerHTML=x("disputes",n.items.map(vo).join(""))+Rt(n,"dispute"),B("disputes"),nt()}catch(e){W(t,e,!1)}}}const xn={};function lo(t){if(!t)return"-";const e=(Date.now()-new Date(t).getTime())/36e5;return e<1?"刚刚提交":e<24?`${Math.floor(e)} 小时前`:`${Math.floor(e/24)} 天前`}function ro(t){return pt.length?pt.map(e=>`<option value="${d(e.skuId)}" ${e.skuId===t?"selected":""}>${o(e.skuName)} (${o(e.skuId)}) ${C(e.priceCents)}</option>`).join(""):'<option value="">暂无商品，请先在商品管理添加</option>'}function Me(t,e){var a;const n=t||((a=pt[0])==null?void 0:a.skuId)||"";return`<div class="dispute-line filters" style="margin-top:8px">
    <div class="dispute-sku-field"><label>商品</label><select class="sku-select">${ro(n)}</select></div>
    <div class="dispute-qty-field"><label>数量</label><input type="number" class="qty-input" value="${e||1}" min="1"></div>
    <div class="dispute-action-field"><button type="button" class="btn-ghost btn-sm" onclick="removeDisputeLine(this)">移除</button></div>
  </div>`}function uo(t){var a;const e=document.querySelector(`.ticket[data-ticket="${t}"]`);if(!e)return;e.querySelector(".dispute-lines").insertAdjacentHTML("beforeend",Me((a=pt[0])==null?void 0:a.skuId,1))}function po(t){const e=xn[t]||[],n=document.querySelector(`.ticket[data-ticket="${t}"]`);if(!n||!e.length){r("无识别建议可采纳","err");return}const a=n.querySelector(".dispute-lines");a.innerHTML=e.map(s=>Me(s.skuId,s.quantity)).join("")}function mo(t){if(t.closest(".ticket").querySelector(".dispute-lines").querySelectorAll(".dispute-line").length<=1){r("至少保留一行商品","err");return}t.closest(".dispute-line").remove()}function ho(t){return!t||!t.length?'<div class="meta">识别建议：无</div>':`<div class="meta">识别建议：${t.map(n=>{const a=n.batchNo?` @${o(n.batchNo)}`:"";return`${o(n.skuName||n.skuId)} × ${o(n.quantity)}${a}`}).join("；")}</div>`}function vo(t){var m,I,k;xn[t.ticketId]=t.suggestedItems||[];const e=t.status==="OPEN",n=lo(t.createdAt),a=e&&(t.slaOverdue||Date.now()-new Date(t.createdAt).getTime()>48*36e5),s=e&&t.slaDueAt?`<div class="meta">SLA 截止 ${Q(t.slaDueAt)}${t.slaHoursRemaining!=null?` · 剩余 ${t.slaHoursRemaining}h`:""}</div>`:"",i=t.sessionId&&(t.videoUri||t.videoPreviewUrl)?`<button type="button" class="btn-ghost btn-sm" onclick="showSessionVideo('${d(t.sessionId)}', '${d(t.videoUri||"")}')">${Ie(t.videoUri)}</button>`:t.sessionId?'<span class="meta">无视频</span>':"",c=!e&&t.resolutionItems&&t.resolutionItems.length?`<div class="meta">结案商品：${t.resolutionItems.map(L=>`${o(L.skuId)} × ${o(L.quantity)}`).join("；")}</div>`:"",l=t.suggestedItems&&((m=t.suggestedItems[0])==null?void 0:m.skuId)||((I=pt[0])==null?void 0:I.skuId),u=t.billedAmountCents!=null?`<div class="meta">已扣款 ¥${(t.billedAmountCents/100).toFixed(2)}${t.orderId?` · 订单 ${o(t.orderId)}`:""}</div>`:t.sessionState==="DISPUTED"?'<div class="meta">待扣款（识别待审核）</div>':"",p=e?`
    <div class="dispute-lines">${Me(l,t.suggestedItems&&((k=t.suggestedItems[0])==null?void 0:k.quantity)||1)}</div>
    <div class="filters" style="margin-top:8px">
      <button type="button" class="btn-ghost btn-sm" onclick="addDisputeLine('${d(t.ticketId)}')">添加商品</button>
      <button type="button" class="btn-ghost btn-sm" onclick="applyDisputeSuggestion('${d(t.ticketId)}')">采用识别建议</button>
      <button type="button" class="btn-ok btn-sm" onclick="resolveTicket('${d(t.ticketId)}', this, 'CONFIRM')">确认扣款</button>
      <button type="button" class="btn-danger btn-sm" onclick="resolveTicket('${d(t.ticketId)}', this, 'WAIVE')">免单退款</button>
    </div>`:"";return`${qa("disputes",t.ticketId,"ticket")}
    <div class="ticket-check" onclick="event.stopPropagation()">${sn("disputes",t.ticketId)}</div>
    <div>${ra(t.status)}${a?' <span class="badge badge-fail">超时待审</span>':""}</div>
    <div class="meta">工单 ${o(t.ticketId)} · 设备 ${o(t.deviceId||"-")} · 会话 ${o(t.sessionId)}</div>
    <div class="meta">原因 ${o(t.reason||"-")} · 等待 ${o(n)} · 创建 ${Q(t.createdAt)}${t.resolvedAt?` · 结案 ${Q(t.resolvedAt)}`:""}</div>
    ${s}
    ${u}
    ${ho(t.suggestedItems)}
    ${c}
    <div class="filters" style="margin-top:8px">${i}</div>
    ${p}
  </div>`}function bo(t,e){return`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal modal-wide" onclick="event.stopPropagation()">
        <h3>${o(t||"购物录像")}</h3>
        ${e?`<p class="meta">${o(e)}</p>`:""}
        <p id="videoLoadHint" class="meta">正在加载…</p>
        <div id="sessionMediaHost" class="session-media-host">
          <video id="sessionVideoPlayer" controls autoplay muted playsinline preload="auto" class="session-media-video hidden"></video>
        </div>
        <p class="meta">若无法播放，请确认该会话已上传录像且 MinIO 服务正常（端口 9000）。</p>
        <div class="modal-actions"><button type="button" class="btn-ghost" onclick="closeModal()">关闭</button></div>
      </div>
    </div>`}async function _n(t,e){if(!(localStorage.getItem("admin_token")||z)){r("请先登录","err");return}ye();const a=document.getElementById("modalRoot"),s=e||"",i=$e(s)==="image",c=i?"购物截图":"购物视频",l=i?"该会话上传的是静态截图（非视频），可用于辅助审核。":"";a.innerHTML=bo(c,l),a.classList.remove("hidden");const u=a.querySelector("#videoLoadHint"),p=a.querySelector("#sessionMediaHost"),m=a.querySelector("#sessionVideoPlayer");try{const I=await ls(t,s),k=URL.createObjectURL(I.blob);Ua(k),u.classList.add("hidden"),I.kind==="image"?(m.classList.add("hidden"),p.innerHTML=`<img src="${d(k)}" alt="购物截图" class="session-media-image">`):(m.classList.remove("hidden"),m.src=k,m.load(),m.play().catch(()=>{}),m.addEventListener("error",()=>{u.className="err video-err",u.textContent="视频解码失败：文件可能已损坏或格式不受支持。",u.classList.remove("hidden")},{once:!0}))}catch(I){u.className="err video-err",u.textContent=ft(I)||"加载失败：该会话可能没有录像，或 MinIO 中文件不存在。可运行 .\\scripts\\e2e-shopping.ps1 生成测试视频。"}}function go(t,e){return _n(t,e)}async function fo(t,e,n="CONFIRM"){const a=e.closest(".ticket"),s=(n||"CONFIRM").toUpperCase();if(s==="WAIVE"){if(!await et("确认免单？将退还该会话已扣款项（如有）。",{title:"免单结案",danger:!0}))return;await U({target:e},async()=>{try{const l=await $(`/api/v2/ops/disputes/${t}/resolve`,"POST",{items:[],resolutionType:"WAIVE"});r(l.message||"已免单","ok"),Ct()}catch(l){throw g(l)||r("失败: "+l.message,"err"),l}},"结案中…");return}const i=[];if(a.querySelectorAll(".dispute-line").forEach(l=>{const u=l.querySelector(".sku-select").value,p=parseInt(l.querySelector(".qty-input").value,10)||0;u&&p>0&&i.push({skuId:u,quantity:p})}),!i.length){r("请至少添加一件商品","err");return}const c=i.map(l=>`${l.skuId} × ${l.quantity}`).join("；");await et(`确认按以下商品结算？
${c}`,{title:"确认扣款"})&&await U({target:e},async()=>{try{const l=await $(`/api/v2/ops/disputes/${t}/resolve`,"POST",{items:i,resolutionType:s});r(l.message||"已结案","ok"),Ct()}catch(l){throw g(l)||r("失败: "+l.message,"err"),l}},"结案中…")}function yo(){M("recharges"),document.getElementById("pageContent").innerHTML=`
    <div class="card">
      <div class="filters">
        <div><label>状态</label>
          <select id="rfStatus">
            <option value="">全部</option>
            ${["PENDING","PAID","REFUNDED","CANCELLED"].map(t=>`<option value="${t}" ${tt.status===t?"selected":""}>${Ka(t)}</option>`).join("")}
          </select>
        </div>
        <div><label>用户ID</label><input id="rfUserId" value="${d(tt.userId)}" placeholder="可选"></div>
        <div><button class="btn-primary" onclick="searchRecharges()">查询</button></div>
        <div>${D("fetchRecharges()")}</div>
        ${N("recharges")}
      </div>
      <div id="rechargeTable"></div>
    </div>`,j(document.getElementById("rechargeTable"),10,6),ae()}function $o(){tt.status=document.getElementById("rfStatus").value,tt.userId=document.getElementById("rfUserId").value.trim(),tt.page=0,ae()}async function ae(){const t=document.getElementById("rechargeTable");if(t){j(t,10,6);try{const e=new URLSearchParams({page:tt.page,size:tt.size,...tt.status?{status:tt.status}:{},...tt.userId?{userId:tt.userId}:{}}),n=await $("/api/v2/ops/admin/recharges?"+e,"GET");if(!n.items.length){t.innerHTML=P("暂无充值订单","用户小程序充值成功后会出现在此列表","fetchRecharges()");return}const a=T("ops:user:balance");t.innerHTML=x("recharges",`
      <table>
        <thead><tr>
          ${F("recharges")}
          <th>订单号</th><th>用户</th><th>金额</th><th>渠道</th><th>状态</th>
          <th>微信单号</th><th>创建</th><th>支付</th><th>退款</th><th>操作</th>
        </tr></thead>
        <tbody>${n.items.map(s=>`
          ${G("recharges",s.orderId)}
          ${q("recharges",s.orderId)}
          <td><code>${o(s.orderId)}</code></td>
          <td>${o(s.userId)}</td>
          <td>${C(s.amountCents)}</td>
          <td>${o(tn(s.channel))}</td>
          <td>${aa(s.status)}</td>
          <td class="meta">${o(s.wxTransactionId||"-")}</td>
          <td>${Q(s.createdAt)}</td>
          <td>${Q(s.paidAt)}</td>
          <td>${Q(s.refundedAt)}</td>
          <td onclick="event.stopPropagation()">${s.status==="PAID"&&a?`<button class="btn-danger btn-sm" onclick="refundRecharge('${d(s.orderId)}', ${s.amountCents})">退款</button>`:"-"}</td>
        </tr>`).join("")}</tbody>
      </table>`)+Rt(n,"recharge"),B("recharges")}catch(e){W(t,e,!1)}}}function Hn(t,e){mt(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>确认退款</h3>
        <p>订单 <code>${o(t)}</code>，金额 <strong>${C(e)}</strong></p>
        <label>退款原因（可选）</label>
        <textarea id="refundReason" rows="3" placeholder="用户申请、重复支付等"></textarea>
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-danger" onclick="confirmRefundRecharge(event, '${d(t)}')">确认退款</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`)}async function Io(t,e){await U(t,async()=>{var a;const n=((a=document.getElementById("refundReason"))==null?void 0:a.value.trim())||"";try{await $("/api/v2/ops/admin/recharge/"+encodeURIComponent(e)+"/refund","POST",n?{reason:n}:{}),ot(),r("退款成功","ok"),ae()}catch(s){g(s)||r("退款失败: "+s.message,"err")}},"退款中…")}function ko(t,e){Hn(t,e)}bn();ms();gn(ht);us();Object.assign($t,{api:$,getCurrentPage:()=>K,fmtTime:Q,fmtMoney:C,closeModal:ot});let Wt=null;function Oe(){Wt&&(clearInterval(Wt),Wt=null)}function wo(){Oe(),Wt=setInterval(()=>{K==="devices"&&ee()},3e4)}window.addEventListener("popstate",()=>{if(!z||document.getElementById("appView").classList.contains("hidden"))return;Lt=Math.max(0,Lt-1),_t=Lt>0,be();const t=kn();Bt(t,{fromPopstate:!0})});Object.assign(window,{sendCode:hs,login:fn,switchLoginMode:gn,logout:ve,navigate:Bt,navigateBack:ys,closeVisitedTab:fs,loadDashboard:Z,showDeviceForm:Ss,saveDevice:Ms,viewDeviceDetail:Pt,applyPlanogramTemplate:Ls,loadFinancePage:Cn,showSlotDiscrepancies:Cs,showSlotEditor:Bs,saveSlotConfig:Ps,deleteSlotConfig:Rs,promptSlotStocktake:Ds,promptSlotStocktakeFor:As,searchSessions:Pn,exportSessionsCsv:xs,cancelSession:_s,openDisputeForSession:Us,searchOrders:Rn,exportOrdersCsv:Fs,showOrderDetail:js,changePage:Be,changePageSize:qs,jumpToPage:Gs,toggleSidebar:wn,toggleNavSection:$s,toggleTheme:Zn,toggleTableSort:ks,debouncedSearchSessions:Za,debouncedSearchOrders:ts,debouncedSearchUsers:es,closeModal:ot,loadSkusPage:Pe,showSkuForm:On,showSkuFormById:Mn,editSelectedSku:Ks,previewSkuImage:Qs,saveSku:Ys,selToggle:an,selToggleAll:Ha,selRowClick:Fa,selClear:M,selSync:B,searchUsers:Nn,showBalanceForm:Xs,saveBalance:Zs,setUserVerified:Re,showVerifyUserForm:to,saveVerifyUser:eo,showRbacAssignForUser:no,fetchAuditLogs:Ae,fetchRecentLogs:Un,setRecentScope:oo,loadRecentPage:De,resolveTicket:fo,addDisputeLine:uo,applyDisputeSuggestion:po,removeDisputeLine:mo,searchDisputes:co,fetchDisputes:Ct,showSessionVideo:_n,showDisputeVideo:go,searchRecharges:$o,refundRecharge:ko,showRefundRechargeForm:Hn,confirmRefundRecharge:Io});const v=(...t)=>$t.api(...t),X=t=>$t.getCurrentPage()===t,at=t=>$t.fmtTime(t),ct=t=>$t.fmtMoney(t),dt=(...t)=>$t.closeModal(...t);function it(t,e){const n=document.getElementById("modalRoot");n.innerHTML=t,n.classList.remove("hidden"),Se(e||(()=>dt()))}function It(t,e){W(t,e,!0)}async function Fn(){const t=document.getElementById("pageContent"),e="sla";try{const n=await v("/api/v2/ops/admin/sla","GET");if(!X(e))return;const a=n.realtime||{};t.innerHTML=`
      <div class="card"><div class="filters">${D("loadSlaPage()")}</div></div>
      <div class="cards">
        <div class="card"><div class="card-label">24h 开门成功率</div><div class="card-value">${Gt(a.doorSuccessRate24h)}</div></div>
        <div class="card"><div class="card-label">24h 平均识别耗时</div><div class="card-value">${o(a.avgRecognizeMs24h||0)} ms</div></div>
        <div class="card"><div class="card-label">当前设备在线率</div><div class="card-value">${Gt(a.deviceOnlineRateNow)}</div></div>
        <div class="card"><div class="card-label">待审争议</div><div class="card-value">${o(a.disputeOpen??0)}</div></div>
        <div class="card"><div class="card-label">SLA 超时争议</div><div class="card-value ${a.disputeOverdue>0?"warn":""}">${o(a.disputeOverdue??0)}</div></div>
        <div class="card"><div class="card-label">24h 争议结案</div><div class="card-value">${o(a.disputeResolved24h??0)}</div></div>
        <div class="card"><div class="card-label">24h SLA 达标率</div><div class="card-value">${Gt(a.disputeSlaCompliance24h??1)}</div></div>
      </div>
      <h3>日快照 ${o(n.snapshotDate||"-")}</h3>
      <table class="table"><thead><tr>
        <th>开门尝试</th><th>成功</th><th>成功率</th><th>识别均耗</th><th>P95</th><th>设备数</th><th>在线峰值</th>
      </tr></thead><tbody><tr>
        <td>${o(n.doorOpenAttempts??0)}</td><td>${o(n.doorOpenSuccess??0)}</td><td>${Gt(n.doorSuccessRate)}</td>
        <td>${o(n.avgRecognizeMs??0)} ms</td><td>${o(n.p95RecognizeMs??0)} ms</td>
        <td>${o(n.deviceTotal??0)}</td><td>${o(n.deviceOnlinePeak??0)}</td>
      </tr></tbody></table>`}catch(n){if(!X(e))return;It(t,n)}}async function Ne(){const t=document.getElementById("pageContent"),e="ota";M("ota");try{const n=await v("/api/v2/ops/admin/ota/releases","GET");if(!X(e))return;const a=(n||[]).map(s=>`
      ${G("ota",s.releaseId)}
      ${q("ota",s.releaseId)}
      <td>${o(s.appVersion)}</td><td>${o(Sa(s.channel))}</td><td>${s.mandatory?"是":"否"}</td>
      <td>${o(s.grayPercent??100)}%</td><td>${o(wa(s.status))}</td><td>${at(s.publishedAt)}</td>
      <td onclick="event.stopPropagation()">${s.downloadUrl?`<a href="${d(s.downloadUrl)}" target="_blank" rel="noopener">下载</a>`:o(s.objectStorageUri||"-")}</td>
    </tr>`).join("");t.innerHTML=`
      <div class="filters">
        ${J("ota.publish","发布新版本","showOtaPublishForm()","btn-primary btn-sm")}
        ${N("ota")}
        ${D("loadOtaPage()")}
      </div>
      <div id="otaPublishForm" class="hidden card" style="margin:12px 0;padding:12px">
        <label>版本号</label><input id="otaVersion" placeholder="1.2.0">
        <label>渠道</label><input id="otaChannel" value="STABLE">
        <label>灰度比例 (0-100)</label><input id="otaGray" type="number" value="100" min="0" max="100">
        <label>对象存储 URI (MinIO/OSS)</label><input id="otaUri" placeholder="s3://cabinet-videos/ota/app-1.2.0.apk">
        <label>下载 URL（可选，无 URI 时填写）</label><input id="otaUrl" placeholder="https://...">
        <label><input type="checkbox" id="otaMandatory"> 强制升级</label>
        <button type="button" class="btn-primary btn-sm" onclick="publishOta(event)">提交发布</button>
      </div>
      ${n&&n.length?x("ota",`<table class="table"><thead><tr>
        ${F("ota")}
        <th>版本</th><th>渠道</th><th>强制</th><th>灰度</th><th>状态</th><th>发布时间</th><th>包</th>
      </tr></thead><tbody>${a}</tbody></table>`):P("暂无 OTA 发布","发布柜机 APK 后设备可检查更新","loadOtaPage()")}
      <p class="sub">柜机检查更新：GET /internal/v1/devices/{id}/ota/check?currentVersion=…</p>`,B("ota"),nt()}catch(n){if(!X(e))return;It(t,n)}}function So(){document.getElementById("otaPublishForm").classList.toggle("hidden")}async function Eo(t){await U(t,async()=>{const e={appVersion:document.getElementById("otaVersion").value.trim(),channel:document.getElementById("otaChannel").value.trim()||"STABLE",mandatory:document.getElementById("otaMandatory").checked,grayPercent:parseInt(document.getElementById("otaGray").value,10)||100,objectStorageUri:document.getElementById("otaUri").value.trim()||null,downloadUrl:document.getElementById("otaUrl").value.trim()||null,status:"PUBLISHED"};if(!e.appVersion){r("请填写版本号","err");return}try{await v("/api/v2/ops/admin/ota/releases","POST",e),r("已发布","ok"),Ne()}catch(n){g(n)||r("发布失败: "+n.message,"err")}},"发布中…")}async function se(){const t=document.getElementById("pageContent"),e="risk";M("riskEvents"),M("blacklist");try{const[n,a]=await Promise.all([v("/api/v2/ops/admin/risk/events?page=0&size=20","GET"),v("/api/v2/ops/admin/risk/blacklist","GET")]);if(!X(e))return;const s=(n.items||[]).map(c=>`
      ${G("riskEvents",c.eventId)}
      ${q("riskEvents",c.eventId)}
      <td>${at(c.createdAt)}</td><td>${o(La(c.eventType))}</td><td>${o(Ta(c.severity))}</td>
      <td>${o(c.userId||"-")}</td><td>${o(c.deviceId||"-")}</td><td>${o(c.detail||"")}</td>
    </tr>`).join(""),i=(a||[]).map(c=>`
      ${G("blacklist",c.userId)}
      ${q("blacklist",c.userId)}
      <td>${o(c.userId)}</td><td>${o(c.reason)}</td><td>${o(c.source)}</td><td>${at(c.expiresAt)}</td>
      <td onclick="event.stopPropagation()">${T("ops:risk:blacklist")?`<button class="btn-ghost btn-sm btn-danger" onclick="removeBlacklist(${c.userId})">解除</button>`:"-"}</td>
    </tr>`).join("");t.innerHTML=`
      <div class="card">
        <div class="filters">
          ${D("loadRiskPage()")}
          ${J("risk.blacklist","添加黑名单","showBlacklistForm()","btn-primary btn-sm")}
        </div>
      </div>
      <h3>风控事件 ${N("riskEvents")}</h3>
      ${(n.items||[]).length?x("riskEvents",`<table class="table"><thead><tr>
          ${F("riskEvents")}
          <th>时间</th><th>类型</th><th>级别</th><th>用户</th><th>设备</th><th>详情</th>
        </tr></thead><tbody>${s}</tbody></table>`):P("暂无风控事件","触发风控规则后会在此展示","loadRiskPage()")}
      <h3>黑名单 ${N("blacklist")}</h3>
      ${(a||[]).length?x("blacklist",`<table class="table"><thead><tr>
          ${F("blacklist")}
          <th>用户</th><th>原因</th><th>来源</th><th>过期</th><th>操作</th>
        </tr></thead><tbody>${i}</tbody></table>`):P("暂无黑名单用户","手动拉黑或自动风控命中后会出现在此","loadRiskPage()")}`,B("riskEvents"),B("blacklist"),nt()}catch(n){if(!X(e))return;It(t,n)}}function To(){it(`
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
    </div>`)}async function Lo(t){await U(t,async()=>{const e=parseInt(document.getElementById("blUserId").value,10),n=document.getElementById("blReason").value.trim(),a=document.getElementById("blExpires").value.trim();if(!e||!n){r("请填写用户 ID 和原因","err");return}try{const s={userId:e,reason:n};a&&(s.expiresAt=a),await v("/api/v2/ops/admin/risk/blacklist","POST",s),dt(),r("已加入黑名单","ok"),se()}catch(s){g(s)||r("操作失败: "+s.message,"err")}},"提交中…")}const Co=Xt(()=>Wn(),350);async function Bo(t){if(await et(`确认解除用户 ${t} 的黑名单？`,{title:"解除黑名单",danger:!0}))try{await v("/api/v2/ops/admin/risk/blacklist/"+t,"DELETE"),r("已解除","ok"),se()}catch(e){g(e)||r("操作失败: "+e.message,"err")}}async function qn(){const t=document.getElementById("pageContent"),e=new Date().toISOString().slice(0,10),n=new Date(Date.now()-30*864e5).toISOString().slice(0,10);M("reconciliation"),t.innerHTML=`
    <div class="filters">
      <div><label>开始</label><input id="reconFrom" type="date" value="${n}"></div>
      <div><label>结束</label><input id="reconTo" type="date" value="${e}"></div>
      <div><label>渠道</label>
        <select id="reconChannel"><option value="WECHAT">微信</option><option value="ALIPAY">支付宝</option><option value="MOCK">Mock</option></select>
      </div>
      <div><button class="btn-ghost btn-sm" onclick="fetchReconciliationList()">查询</button></div>
      <div>${D("fetchReconciliationList()")}</div>
      ${N("reconciliation")}
      ${J("recon.run","执行对账","runReconToday(event)","btn-primary btn-sm")}
    </div>
    <div id="reconTable"></div>`,nt(),j(document.getElementById("reconTable"),8,6),Ue()}async function Ue(){var e,n;const t=document.getElementById("reconTable");if(t){j(t,8,6);try{const a=(e=document.getElementById("reconFrom"))==null?void 0:e.value,s=(n=document.getElementById("reconTo"))==null?void 0:n.value,i=new URLSearchParams;a&&i.set("from",a),s&&i.set("to",s);const c=await v("/api/v2/ops/admin/reconciliation?"+i,"GET");if(!c||!c.length){t.innerHTML=P("暂无对账记录","选择日期范围后查询，或执行对账任务","fetchReconciliationList()");return}const l=(c||[]).map(u=>`
      ${G("reconciliation",u.reconId)}
      ${q("reconciliation",u.reconId)}
      <td>${o(u.reconDate)}</td><td>${o(tn(u.channel))}</td>
      <td>${ct(u.platformTotal)}</td><td>${ct(u.ledgerTotal)}</td>
      <td>${ct(u.diffCents)}</td>
      <td>${o(u.matchedCount??0)}/${o(u.unmatchedCount??0)}</td>
      <td>${o(en(u.status))}</td><td>${at(u.completedAt)}</td>
      <td onclick="event.stopPropagation()"><button type="button" class="btn-ghost btn-sm" onclick="showReconDetail(${o(u.reconId)})">明细</button></td>
    </tr>`).join("");t.innerHTML=x("reconciliation",`
      <table class="table"><thead><tr>
        ${F("reconciliation")}
        <th>日期</th><th>渠道</th><th>平台总额</th><th>账本总额</th><th>差额</th>
        <th>匹配/未匹配</th><th>状态</th><th>完成时间</th><th>操作</th>
      </tr></thead><tbody>${l||'<tr><td colspan="10">暂无记录</td></tr>'}</tbody></table>`),B("reconciliation")}catch(a){It(t,a)}}}async function Po(t){await U(t,async()=>{var a;const e=new Date().toISOString().slice(0,10),n=((a=document.getElementById("reconChannel"))==null?void 0:a.value)||"WECHAT";try{await v(`/api/v2/ops/admin/reconciliation/run?date=${e}&channel=${n}`,"POST"),r("对账任务已提交","ok"),Ue()}catch(s){g(s)||r("对账失败: "+s.message,"err")}},"执行中…")}async function Ro(t){try{const e=await v("/api/v2/ops/admin/reconciliation/"+t,"GET"),n=e.summary,a=e.lines||[],s=a.filter(c=>!c.matched),i=a.slice(0,100).map(c=>`<tr class="${c.matched?"":"err"}">
      <td>${o(c.platformTradeNo)}</td><td>${o(c.merchantOrderNo||"-")}</td>
      <td>${ct(c.amountCents)}</td><td>${o(c.tradeType||"-")}</td>
      <td>${c.matched?"✓":"✗"}</td><td>${at(c.tradeTime)}</td>
    </tr>`).join("");it(`
      <div class="modal-backdrop" onclick="closeModal(event)">
        <div class="modal modal-wide" onclick="event.stopPropagation()">
          <h3>对账明细 #${o(t)} · ${o(n.reconDate)} · ${o(n.channel)}</h3>
          <p class="meta">平台 ${ct(n.platformTotal)} / 账本 ${ct(n.ledgerTotal)} / 差额 ${ct(n.diffCents)} · ${o(en(n.status))}</p>
          ${s.length?`<p class="err">未匹配 ${o(s.length)} 笔</p>`:""}
          <table class="table"><thead><tr>
            <th>平台流水</th><th>商户单号</th><th>金额</th><th>类型</th><th>匹配</th><th>时间</th>
          </tr></thead><tbody>${i||'<tr><td colspan="6">无明细</td></tr>'}</tbody></table>
          ${a.length>100?'<p class="sub">仅显示前 100 条</p>':""}
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">关闭</button>
        </div>
      </div>`)}catch(e){g(e)||r("加载失败: "+e.message,"err")}}async function rt(){const t=document.getElementById("pageContent"),e="replenishment";M("replenInventory");const n=oe.lowStockOnly;try{const a="/api/v2/ops/admin/inventory"+(n?"?lowStockOnly=true":""),[s,i,c,l]=await Promise.all([v("/api/v2/ops/admin/replenishment/routes","GET"),v(a,"GET"),v("/api/v2/ops/admin/skus","GET").catch(()=>[]),v("/api/v2/ops/admin/expiry/alerts","GET").catch(()=>[])]);if(!X(e))return;const u=Object.fromEntries((c||[]).map(h=>[h.skuId,h])),p=h=>{const y=u[h];return y?`${o(y.skuName)} <code>${o(h)}</code>`:`<code>${o(h)}</code>`},m=(i||[]).filter(h=>h.quantity<=h.lowThreshold).length,I=(l||[]).map(h=>`<tr>
      <td>${o(h.deviceId)}</td><td>${p(h.skuId)}</td>
      <td><code>${o(h.batchNo||"-")}</code></td><td>${o(h.quantity)}</td>
      <td><span class="badge badge-active">${o(h.reason)}</span></td>
      <td>${at(h.createdAt)}</td>
    </tr>`).join(""),k=(s||[]).map(h=>{const y=(h.tasks||[]).map(S=>`<tr>
        <td>${o(S.deviceId)}</td><td>${o(je(S.status))}</td>
        <td>${S.completedAt?at(S.completedAt):"-"}</td>
        <td>${S.status!=="COMPLETED"&&T("ops:replenishment:edit")?`<button class="btn-ghost btn-sm" onclick="showReplenishmentLinesForm(${S.taskId},'${d(S.deviceId)}')">录入行</button>
             <button class="btn-ghost btn-sm" onclick="completeReplenishmentTask(${S.taskId})">完成</button>`:"-"}</td>
      </tr>`).join(""),f=y?`<table class="table sub-table"><thead><tr><th>设备</th><th>状态</th><th>完成时间</th><th>操作</th></tr></thead><tbody>${y}</tbody></table>`:'<span class="meta">无任务</span>';return`<tr><td colspan="5">
        <div><strong>${o(h.routeName)}</strong> · ${o(h.plannedDate)} · ${o(je(h.status))} · 负责人 ${o(h.assigneeUserId||"-")}</div>
        ${f}
      </td></tr>`}).join(""),L=[...new Set((i||[]).map(h=>h.deviceId))],_=(i||[]).map(h=>{const y=h.quantity<=h.lowThreshold,f=`${h.deviceId}:${h.skuId}`;return`
      ${G("replenInventory",f,y?"row-low-stock":"")}
      ${q("replenInventory",f)}
      <td>${o(h.deviceId)}</td><td>${p(h.skuId)}</td>
      <td>${o(h.quantity)}/${o(h.capacity)}${y?' <span class="badge badge-active">低库存</span>':""}</td>
      <td>${o(h.lowThreshold)}</td>
      <td onclick="event.stopPropagation()">
        ${T("ops:replenishment:edit")?`<button class="btn-ghost btn-sm" onclick='showInventoryForm(${JSON.stringify(h)})'>编辑</button>`:""}
        <button class="btn-ghost btn-sm" onclick="viewDeviceLots('${d(h.deviceId)}')">批次</button>
      </td>
    </tr>`}).join("");t.innerHTML=`
      <div class="filters">
        ${J("replenish.plan","规划路线","showReplenishmentPlanForm()","btn-primary btn-sm")}
        ${J("replenish.edit","录入库存","showInventoryForm()","btn-ghost btn-sm")}
        ${J("replenish.edit","SKU 盘点","showSkuStocktakeForm()","btn-ghost btn-sm")}
        ${J("replenish.edit","报损","showWriteOffForm()","btn-ghost btn-sm")}
        ${m>0&&T("ops:replenishment:edit")?`<button type="button" class="btn-ok btn-sm" onclick="planRouteFromLowStock()">从低库存生成路线 (${m})</button>`:""}
        <label class="filter-check"><input type="checkbox" id="replLowOnly" ${n?"checked":""} onchange="toggleReplenishmentLowStock()"> 仅低库存</label>
        ${N("replenInventory")}
        ${D("loadReplenishmentPage()")}
      </div>
      <p class="meta">${n?`当前显示 ${i.length} 条低库存记录`:`共 ${i.length} 条库存，其中 ${m} 条低库存`}</p>
      <h3>效期告警 / 待下架</h3>
      ${(l||[]).length?`<table class="table"><thead><tr><th>设备</th><th>商品</th><th>批次</th><th>数量</th><th>原因</th><th>创建时间</th></tr></thead><tbody>${I}</tbody></table>`:'<p class="meta">暂无待下架任务</p>'}
      <h3>补货路线</h3>
      ${(s||[]).length?`<table class="table"><tbody>${k}</tbody></table>`:P("暂无补货路线","点击「规划路线」创建补货任务","loadReplenishmentPage()")}
      <h3>柜内库存</h3>
      ${L.length?`<p class="meta">设备：${L.map(h=>`<button class="btn-ghost btn-sm" onclick="viewDeviceLots('${d(h)}')">${o(h)} 批次</button>`).join(" ")}</p>`:""}
      ${(i||[]).length?x("replenInventory",`<table class="table"><thead><tr>
          ${F("replenInventory")}
          <th>设备</th><th>商品</th><th>库存/容量</th><th>低库存阈值</th><th>操作</th>
        </tr></thead><tbody>${_}</tbody></table>`):P(n?"暂无低库存 SKU":"暂无库存数据",n?"所有 SKU 库存充足":"点击「录入库存」添加柜内 SKU 数量","loadReplenishmentPage()")}`,B("replenInventory"),nt()}catch(a){if(!X(e))return;It(t,a)}}function Ao(){const t=document.getElementById("replLowOnly");oe.lowStockOnly=!!(t!=null&&t.checked),rt()}async function Do(){if(T("ops:replenishment:edit"))try{const e=await v("/api/v2/ops/admin/inventory?lowStockOnly=true","GET")||[];if(!e.length){r("暂无低库存 SKU","err");return}const n=[...new Set(e.map(i=>i.deviceId))],a=new Date().toISOString().slice(0,10),s={};if(e.forEach(i=>{s[i.deviceId]=(s[i.deviceId]||[]).concat(`${i.skuId}×${i.quantity}`)}),!await et(`将为 ${n.length} 台设备创建补货路线，涉及 ${e.length} 个低库存 SKU？`,{title:"创建补货路线"}))return;await v("/api/v2/ops/admin/replenishment/routes","POST",{routeName:`低库存补货-${a}`,plannedDate:a,assigneeUserId:parseInt(localStorage.getItem("admin_userId")||"100000001",10),tasks:n.map(i=>({deviceId:i,notes:"低库存: "+(s[i]||[]).join("; ")}))}),r("补货路线已创建","ok"),oe.lowStockOnly=!1,rt()}catch(t){g(t)||r("创建失败: "+t.message,"err")}}function Mo(t){const e=!!t;it(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>${e?"编辑库存":"录入库存"}</h3>
        <label>设备 ID</label>
        <input id="invDevice" value="${e?d(t.deviceId):"CAB-001"}" ${e?"disabled":""}>
        <label>SKU ID</label>
        <input id="invSku" value="${e?d(t.skuId):"SKU-DEMO-001"}" ${e?"disabled":""}>
        <div class="filters">
          <div><label>当前数量</label><input id="invQty" type="number" min="0" value="${e?t.quantity:0}"></div>
          <div><label>容量</label><input id="invCap" type="number" min="1" value="${e?t.capacity:20}"></div>
        </div>
        <label>低库存阈值</label>
        <input id="invLow" type="number" min="0" value="${e?t.lowThreshold:3}">
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveInventory(event)">保存</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`)}async function Oo(t){await U(t,async()=>{const e=document.getElementById("invDevice").value.trim(),n=document.getElementById("invSku").value.trim(),a=parseInt(document.getElementById("invQty").value,10),s=parseInt(document.getElementById("invCap").value,10),i=parseInt(document.getElementById("invLow").value,10);if(!e||!n||Number.isNaN(a)||Number.isNaN(s)){r("请填写完整","err");return}try{await v("/api/v2/ops/admin/inventory","PUT",{deviceId:e,skuId:n,quantity:a,capacity:s,lowThreshold:i||0}),dt(),r("库存已保存","ok"),rt()}catch(c){g(c)||r("保存失败: "+c.message,"err")}})}function No(t){const e=!!t;it(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>SKU 盘点调整</h3>
        <p class="meta">按 SKU 汇总账面与实盘差异，写入批次流水（FEFO 缩账或补录）。</p>
        <label>设备 ID</label>
        <input id="stkDevice" value="${e?d(t.deviceId):"CAB-001"}">
        <label>SKU ID</label>
        <input id="stkSku" value="${e?d(t.skuId):"SKU-DEMO-001"}">
        <label>实盘数量</label>
        <input id="stkQty" type="number" min="0" value="${e?t.quantity:0}">
        <label>备注</label>
        <input id="stkNote" placeholder="可选">
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveSkuStocktake(event)">提交盘点</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`)}async function Uo(t){await U(t,async()=>{const e=document.getElementById("stkDevice").value.trim(),n=document.getElementById("stkSku").value.trim(),a=parseInt(document.getElementById("stkQty").value,10),s=document.getElementById("stkNote").value.trim()||null;if(!e||!n||Number.isNaN(a)){r("请填写完整","err");return}try{await v("/api/v2/ops/admin/inventory/stocktake","POST",{deviceId:e,skuId:n,countedQuantity:a,note:s}),dt(),r("盘点已提交","ok"),rt()}catch(i){g(i)||r("盘点失败: "+i.message,"err")}})}function xo(t){const e=t||{};it(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>库存报损</h3>
        <label>设备 ID</label>
        <input id="woDevice" value="${d(e.deviceId||"CAB-001")}">
        <label>SKU ID</label>
        <input id="woSku" value="${d(e.skuId||"SKU-DEMO-001")}">
        <label>批次号（可选，空则 FEFO）</label>
        <input id="woBatch" value="${d(e.batchNo||"")}">
        <label>数量</label>
        <input id="woQty" type="number" min="1" value="${e.quantity||1}">
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
    </div>`)}async function _o(t){await U(t,async()=>{const e=document.getElementById("woDevice").value.trim(),n=document.getElementById("woSku").value.trim(),a=document.getElementById("woBatch").value.trim()||null,s=parseInt(document.getElementById("woQty").value,10),i=document.getElementById("woReason").value;if(!e||!n||!s||s<1){r("请填写完整","err");return}if(await et(`确认报损 ${n} × ${s}？`,{title:"报损确认"}))try{await v("/api/v2/ops/admin/inventory/write-off","POST",{deviceId:e,skuId:n,batchNo:a,quantity:s,reason:i}),dt(),r("报损已记录","ok"),rt()}catch(c){g(c)||r("报损失败: "+c.message,"err")}})}async function Ho(t){if(await et(`确认完成任务 #${t}？将应用已录入的补货行并更新批次库存。`,{title:"完成任务"}))try{await v("/api/v2/ops/admin/replenishment/tasks/"+t+"/complete","POST"),r("任务已完成","ok"),rt()}catch(e){g(e)||r("操作失败: "+e.message,"err")}}async function Fo(t,e){let n=[];try{n=await v("/api/v2/ops/admin/skus","GET")}catch(c){if(g(c))return}const a=(n||[]).map(c=>`<option value="${d(c.skuId)}">${o(c.skuName)} (${o(c.skuId)})</option>`).join(""),s=new Date().toISOString().slice(0,10),i=new Date(Date.now()+30*864e5).toISOString().slice(0,10);it(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" style="max-width:720px" onclick="event.stopPropagation()">
        <h3>补货行项目 · 任务 #${t}</h3>
        <p class="meta">设备 <code>${o(e)}</code> · 完成前提交上架/下架明细，完成时将写入批次与库存流水</p>
        <div id="replLinesContainer"></div>
        <button type="button" class="btn-ghost btn-sm" onclick="addReplenishmentLineRow()">+ 添加一行</button>
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveReplenishmentLines(event, ${t})">保存行项目</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`),window._replLineSkuOptions=a,window._replLineDefaults={today:s,expiryDefault:i};try{const c=await v("/api/v2/ops/admin/replenishment/tasks/"+t+"/lines","GET");if(!document.getElementById("replLinesContainer"))return;c!=null&&c.length?c.forEach(u=>zt(u)):zt()}catch(c){zt(),g(c)||r("加载已有行失败: "+c.message,"err")}}function zt(t){const e=document.getElementById("replLinesContainer");if(!e)return;const n=window._replLineSkuOptions||"",a=window._replLineDefaults||{today:"",expiryDefault:""},s=(t==null?void 0:t.lineType)||"RESTOCK",i=(t==null?void 0:t.skuId)||"";e.children.length;const c=document.createElement("div");if(c.className="card",c.style.marginBottom="10px",c.innerHTML=`
    <div class="filters form-grid">
      <div><label>类型</label>
        <select class="repl-line-type">
          <option value="RESTOCK" ${s==="RESTOCK"?"selected":""}>上架 RESTOCK</option>
          <option value="PULL_OFF" ${s==="PULL_OFF"?"selected":""}>下架 PULL_OFF</option>
        </select>
      </div>
      <div><label>SKU</label>
        <select class="repl-line-sku"><option value="">选择商品</option>${n}</select>
      </div>
      <div><label>数量</label><input class="repl-line-qty" type="number" min="1" value="${(t==null?void 0:t.quantity)||1}"></div>
      <div><label>批次号</label><input class="repl-line-batch" value="${d((t==null?void 0:t.batchNo)||"")}" placeholder="B20260701-001"></div>
      <div><label>生产日期</label><input class="repl-line-prod" type="date" value="${(t==null?void 0:t.productionDate)||a.today}"></div>
      <div><label>到期日</label><input class="repl-line-exp" type="date" value="${(t==null?void 0:t.expiryDate)||a.expiryDefault}"></div>
      <div><label>货道</label><input class="repl-line-slot" value="${d((t==null?void 0:t.slotId)||"")}" placeholder="A1"></div>
    </div>
    <button type="button" class="btn-ghost btn-sm" onclick="this.closest('.card').remove()">删除此行</button>`,e.appendChild(c),i){const l=c.querySelector(".repl-line-sku");l&&(l.value=i)}}async function qo(t,e){t&&t.preventDefault();const n=document.getElementById("replLinesContainer");if(!n)return;const a=[];if(n.querySelectorAll(".card").forEach(s=>{var k,L,_,h,y,f,S,b,E,w;const i=((k=s.querySelector(".repl-line-type"))==null?void 0:k.value)||"RESTOCK",c=(_=(L=s.querySelector(".repl-line-sku"))==null?void 0:L.value)==null?void 0:_.trim(),l=parseInt((h=s.querySelector(".repl-line-qty"))==null?void 0:h.value,10),u=((f=(y=s.querySelector(".repl-line-batch"))==null?void 0:y.value)==null?void 0:f.trim())||null,p=((S=s.querySelector(".repl-line-prod"))==null?void 0:S.value)||null,m=((b=s.querySelector(".repl-line-exp"))==null?void 0:b.value)||null,I=((w=(E=s.querySelector(".repl-line-slot"))==null?void 0:E.value)==null?void 0:w.trim())||null;!c||!l||a.push({lineType:i,skuId:c,quantity:l,batchNo:u,productionDate:p,expiryDate:m,slotId:I})}),!a.length){r("请至少填写一行有效明细","err");return}try{await v("/api/v2/ops/admin/replenishment/tasks/"+e+"/lines","POST",{lines:a}),dt(),r("补货行已保存","ok"),rt()}catch(s){g(s)||r("保存失败: "+s.message,"err")}}async function Go(t){try{const e=await v("/api/v2/ops/admin/devices/"+encodeURIComponent(t)+"/lots","GET"),n=(e||[]).map(a=>`<tr>
      <td><code>${o(a.batchNo)}</code></td><td>${Ko(a.skuId)}</td>
      <td>${o(a.quantity)}</td><td>${o(a.expiryDate||"-")}</td>
      <td>${o(jo(a.status))}</td><td>${o(a.slotId||"-")}</td>
      <td>${T("ops:replenishment:edit")&&a.quantity>0?`<button class="btn-ghost btn-sm" onclick='showWriteOffForm(${JSON.stringify({deviceId:t,skuId:a.skuId,batchNo:a.batchNo,quantity:a.quantity})})'>报损</button>`:"-"}</td>
    </tr>`).join("");it(`
      <div class="modal-backdrop" onclick="closeModal(event)">
        <div class="modal" style="max-width:800px" onclick="event.stopPropagation()">
          <h3>设备批次 · ${o(t)}</h3>
          ${(e||[]).length?`<table class="table"><thead><tr><th>批次</th><th>商品</th><th>数量</th><th>到期</th><th>状态</th><th>货道</th><th>操作</th></tr></thead><tbody>${n}</tbody></table>`:'<p class="meta">暂无批次记录（可通过补货行 RESTOCK 入库）</p>'}
          <div class="filters" style="margin-top:12px">
            <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">关闭</button>
          </div>
        </div>
      </div>`)}catch(e){g(e)||r("加载批次失败: "+e.message,"err")}}function jo(t){return{ON_SALE:"在售",NEAR_EXPIRY:"临期",BLOCKED:"禁售",DEPLETED:"售罄"}[t]||t||"-"}function Ko(t){return`<code>${o(t)}</code>`}async function Vo(){const t=new Date().toISOString().slice(0,10);it(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" style="max-width:560px" onclick="event.stopPropagation()">
        <h3>规划补货路线</h3>
        <label>路线名称</label>
        <input id="rpName" value="补货路线-${t}">
        <label>选择设备</label>
        <div id="rpDeviceList"><p class="meta">加载设备中…</p></div>
        <div class="filters">
          <div><label>负责人 userId</label>
            <input id="rpAssignee" type="number" value="${d(localStorage.getItem("admin_userId")||"100000001")}"></div>
          <div><label>计划日期</label><input id="rpDate" type="date" value="${t}"></div>
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
    </div>`);try{const e=await v("/api/v2/ops/admin/devices","GET"),n=document.getElementById("rpDeviceList");if(!n)return;if(!(e!=null&&e.length)){n.innerHTML='<p class="meta">暂无设备，请先在设备管理注册</p>';return}const a=e.map(s=>`
      <label class="device-check">
        <input type="checkbox" class="rp-device-cb" value="${d(s.deviceId)}">
        <span class="device-check-main">${o(s.deviceId)} · ${o(s.deviceName||"-")}</span>
        <span class="meta">${o(s.merchantName||"未绑定商户")} · ${o(Ze(s.onlineStatus))}</span>
      </label>`).join("");n.innerHTML=`
      <div class="device-check-toolbar">
        <button type="button" class="btn-ghost btn-sm" onclick="toggleAllReplenishmentDevices(true)">全选</button>
        <button type="button" class="btn-ghost btn-sm" onclick="toggleAllReplenishmentDevices(false)">清空</button>
      </div>
      <div class="device-check-list">${a}</div>`}catch(e){const n=document.getElementById("rpDeviceList");n&&!g(e)&&(n.innerHTML=`<p class="meta">加载设备失败：${o(e.message)}</p>`)}}function Gn(){return[...document.querySelectorAll(".rp-device-cb:checked")].map(t=>t.value)}function Wo(t){document.querySelectorAll(".rp-device-cb").forEach(e=>{e.checked=t})}async function zo(t){await U(t,async()=>{const e=document.getElementById("rpName").value.trim(),n=Gn(),a=parseInt(document.getElementById("rpAssignee").value,10),s=document.getElementById("rpDate").value,i=parseFloat(document.getElementById("rpLat").value),c=parseFloat(document.getElementById("rpLng").value);if(!e||!n.length||!s||Number.isNaN(a)){r("请填写路线名称、设备和负责人","err");return}try{await v("/api/v2/ops/admin/replenishment/plan","POST",{routeName:e,assigneeUserId:a,plannedDate:s,deviceIds:n,startLatitude:i,startLongitude:c}),dt(),r("路线已规划","ok"),rt()}catch(l){g(l)||r("规划失败: "+l.message,"err")}},"创建中…")}async function jn(){var n,a,s,i,c,l;const t=document.getElementById("pageContent"),e="rbac";M("rbacRoles"),M("rbacOperators");try{const[u,p,m]=await Promise.all([v("/api/v2/ops/admin/rbac/roles","GET"),v("/api/v2/ops/admin/rbac/permissions","GET"),v("/api/v2/ops/admin/rbac/me","GET")]);if(!X(e))return;window._rbacState={tab:((n=window._rbacState)==null?void 0:n.tab)||"roles",selectedRoleId:((a=window._rbacState)==null?void 0:a.selectedRoleId)||(((s=u[0])==null?void 0:s.roleId)??null),selectedUserId:((i=window._rbacState)==null?void 0:i.selectedUserId)||null,roles:u||[],perms:p||[],rolePermIds:new Set,operatorFilters:((c=window._rbacState)==null?void 0:c.operatorFilters)||{page:0,size:20,phone:""},recentScope:((l=window._rbacState)==null?void 0:l.recentScope)||"all"},window._rbacRoles=u||[];const I=((m==null?void 0:m.roleNames)||[]).join("、")||"未分配";t.innerHTML=`
      <div class="card rbac-profile">
        <div class="rbac-profile-main">
          <strong>${o((m==null?void 0:m.name)||(m==null?void 0:m.phoneNumber)||"运营账号")}</strong>
          <span class="sub">${o((m==null?void 0:m.phoneNumber)||"")}</span>
        </div>
        <div class="rbac-profile-meta">
          <span>角色：${o(I)}</span>
          <span>权限项：${o((m==null?void 0:m.permissionCount)??0)}</span>
        </div>
        <div class="filters">${D("loadRbacPage()")}</div>
      </div>
      <div class="tabs rbac-tabs">
        <button type="button" class="tab ${window._rbacState.tab==="roles"?"active":""}" onclick="switchRbacTab('roles')">角色权限</button>
        ${T("ops:rbac:assign")?`<button type="button" class="tab ${window._rbacState.tab==="users"?"active":""}" onclick="switchRbacTab('users')">用户授权</button>`:""}
      </div>
      <div id="rbacPanel"></div>`,nt(),await xe()}catch(u){if(!X(e))return;It(t,u)}}function Qo(t){window._rbacState&&(window._rbacState.tab=t,document.querySelectorAll(".rbac-tabs .tab").forEach(e=>{e.classList.toggle("active",e.textContent.includes(t==="roles"?"角色":t==="users"?"用户":"最近"))}),xe())}async function xe(){const t=document.getElementById("rbacPanel");if(!t||!window._rbacState)return;const{tab:e}=window._rbacState;e==="roles"?(t.innerHTML=`
      <div class="rbac-split">
        <div class="card rbac-pane">
          <h3 class="pane-title">角色列表 ${N("rbacRoles")}</h3>
          ${x("rbacRoles",`<table class="table rbac-role-table">
            <thead><tr>
              ${F("rbacRoles")}
              <th>角色</th><th>标识</th><th>权限</th>
            </tr></thead>
            <tbody>${(window._rbacState.roles||[]).map(n=>`
              ${G("rbacRoles",n.roleId,window._rbacState.selectedRoleId===n.roleId?"rbac-role-row selected":"rbac-role-row",`if (!event.ctrlKey && !event.metaKey) selectRbacRole(${n.roleId})`)}
              ${q("rbacRoles",n.roleId)}
              <td>${o(n.roleName)}</td>
              <td><code>${o(n.roleKey)}</code></td>
              <td class="meta">${o((n.permissions||[])[0]||"-")}</td>
            </tr>`).join("")}</tbody>
          </table>`)}
        </div>
        <div class="card rbac-pane" id="rbacPermPane">
          <p class="sub">选择左侧角色以配置菜单权限</p>
        </div>
      </div>`,B("rbacRoles"),window._rbacState.selectedRoleId&&await _e(window._rbacState.selectedRoleId)):e==="users"&&(t.innerHTML=`
      <div class="rbac-split">
        <div class="card rbac-pane">
          <h3 class="pane-title">运营账号</h3>
          <div class="filters">
            <div><label>手机号</label>
              <input id="rbacOpPhone" placeholder="搜索手机号" value="${d(window._rbacState.operatorFilters.phone)}" oninput="debouncedSearchRbacOperators()"></div>
            <button class="btn-primary btn-sm" onclick="searchRbacOperators()">搜索</button>
            ${N("rbacOperators")}
          </div>
          <div id="rbacOperatorList"></div>
        </div>
        <div class="card rbac-pane" id="rbacUserRolePane">
          <p class="sub">选择左侧运营账号分配角色</p>
        </div>
      </div>`,await He())}function Yo(t){const e=new Map,n=[];(t||[]).forEach(s=>e.set(s.permissionId,{...s,children:[]})),(t||[]).forEach(s=>{const i=e.get(s.permissionId);s.parentId&&s.parentId!==0&&e.has(s.parentId)?e.get(s.parentId).children.push(i):n.push(i)});const a=s=>{s.sort((i,c)=>(i.sortOrder||0)-(c.sortOrder||0)),s.forEach(i=>a(i.children))};return a(n),n}function Jo(t){return{M:"目录",C:"菜单",F:"按钮"}[t]||t}function Kn(t,e,n=0){return(t||[]).map(a=>{var c;const s=e.has(a.permissionId),i=(c=a.children)!=null&&c.length?`<div class="perm-children">${Kn(a.children,e,n+1)}</div>`:"";return`
      <div class="perm-tree-node" style="padding-left:${n*18}px">
        <label class="perm-tree-label">
          <input type="checkbox" class="perm-cb" data-id="${a.permissionId}" ${s?"checked":""}
            onchange="onPermCheckChange(this, ${a.permissionId})">
          <span class="perm-type perm-type-${d(a.permType)}">${o(Jo(a.permType))}</span>
          <span class="perm-name">${o(a.permName)}</span>
          <code class="perm-code">${o(a.permCode)}</code>
        </label>
      </div>${i}`}).join("")}function Vn(t,e){const n=[t];return(e||[]).filter(a=>a.parentId===t).forEach(a=>{n.push(...Vn(a.permissionId,e))}),n}function Xo(t,e){const n=document.getElementById("rbacPermPane");if(!n)return;const a=t.checked;if(Vn(e,window._rbacState.perms).forEach(i=>{const c=n.querySelector('.perm-cb[data-id="'+i+'"]');c&&(c.checked=a)}),a){let i=(window._rbacState.perms.find(c=>c.permissionId===e)||{}).parentId;for(;i&&i!==0;){const c=n.querySelector('.perm-cb[data-id="'+i+'"]');c&&(c.checked=!0),i=(window._rbacState.perms.find(l=>l.permissionId===i)||{}).parentId}}}async function Zo(t){window._rbacState.selectedRoleId=t,document.querySelectorAll(".rbac-role-row").forEach(e=>{var n;e.classList.toggle("selected",(n=e.getAttribute("onclick"))==null?void 0:n.includes("("+t+")"))}),await _e(t)}async function _e(t){const e=document.getElementById("rbacPermPane");if(e){e.innerHTML='<p class="sub">加载权限树…</p>';try{const n=await v("/api/v2/ops/admin/rbac/roles/"+t+"/permissions","GET"),a=(window._rbacState.roles||[]).find(l=>l.roleId===t),s=new Set(n.permissionIds||[]);window._rbacState.rolePermIds=s;const i=(a==null?void 0:a.roleKey)==="admin",c=Yo(window._rbacState.perms);e.innerHTML=`
      <div class="pane-head">
        <h3 class="pane-title">${o((a==null?void 0:a.roleName)||n.roleName)} · 菜单权限</h3>
        ${i?'<span class="badge badge-done">超级管理员不可编辑</span>':J("rbac.role.save","保存权限","saveRolePermissions(event)","btn-primary btn-sm")}
      </div>
      <div class="perm-tree">${Kn(c,s)}</div>`,nt()}catch(n){g(n)||(e.innerHTML='<p class="err">'+o(n.message)+"</p>")}}}async function ti(t){await U(t,async()=>{var a;const e=(a=window._rbacState)==null?void 0:a.selectedRoleId;if(!e)return;const n=[...document.querySelectorAll("#rbacPermPane .perm-cb:checked")].map(s=>parseInt(s.dataset.id,10));try{await v("/api/v2/ops/admin/rbac/roles/"+e+"/permissions","PUT",n),r("角色权限已保存","ok"),_e(e)}catch(s){g(s)||r("保存失败: "+s.message,"err")}})}function Wn(){var t;window._rbacState.operatorFilters.phone=(((t=document.getElementById("rbacOpPhone"))==null?void 0:t.value)||"").trim(),window._rbacState.operatorFilters.page=0,He()}async function He(){const t=document.getElementById("rbacOperatorList");if(t){t.innerHTML='<p class="sub">加载中…</p>';try{const e=window._rbacState.operatorFilters,n=new URLSearchParams({page:e.page,size:e.size});e.phone&&n.set("phone",e.phone);const a=await v("/api/v2/ops/admin/rbac/operators?"+n,"GET");if(!a.items.length){t.innerHTML=P("暂无运营账号","运营账号 userId ≥ 100000000","searchRbacOperators()");return}t.innerHTML=x("rbacOperators",`
      <table class="table">
        <thead><tr>
          ${F("rbacOperators")}
          <th>手机号</th><th>姓名</th><th>当前角色</th>
        </tr></thead>
        <tbody>${a.items.map(s=>`
          ${G("rbacOperators",s.userId,window._rbacState.selectedUserId===s.userId?"rbac-user-row selected":"rbac-user-row",`if (!event.ctrlKey && !event.metaKey) selectRbacUser(${s.userId})`)}
          ${q("rbacOperators",s.userId)}
          <td>${o(s.phoneNumber)}</td>
          <td>${o(s.name||"-")}</td>
          <td class="meta">${o((s.roleNames||[]).join("、")||"未分配")}</td>
        </tr>`).join("")}</tbody>
      </table>`)+mi(a),B("rbacOperators")}catch(e){g(e)||(t.innerHTML='<p class="err">'+o(e.message)+"</p>")}}}async function Fe(t){window._rbacState.selectedUserId=t,document.querySelectorAll(".rbac-user-row").forEach(n=>{var a;n.classList.toggle("selected",(a=n.getAttribute("onclick"))==null?void 0:a.includes("("+t+")"))});const e=document.getElementById("rbacUserRolePane");if(e){e.innerHTML='<p class="sub">加载授权…</p>';try{const[n,a]=await Promise.all([v("/api/v2/ops/admin/rbac/users/"+t+"/roles","GET"),v("/api/v2/ops/admin/rbac/users/"+t+"/merchants","GET")]),s=new Set(n.roleIds||[]),i=new Set(a.merchantIds||[]),c=(window._rbacRoles||[]).map(p=>`<label class="role-check-item">
        <input type="checkbox" class="rbac-role-cb" value="${d(p.roleId)}" ${s.has(p.roleId)?"checked":""}>
        <span>${o(p.roleName)}</span>
        <code>${o(p.roleKey)}</code>
      </label>`).join("");let l=window._rbacMerchants||[];if(!l.length)try{l=await v("/api/v2/ops/admin/merchants","GET"),window._rbacMerchants=l}catch{}const u=l.map(p=>`<label class="role-check-item">
        <input type="checkbox" class="rbac-merchant-cb" value="${d(p.merchantId)}" ${i.has(p.merchantId)?"checked":""}>
        <span>${o(p.merchantName)}</span>
        <code>${o(p.merchantId)}</code>
      </label>`).join("");e.innerHTML=`
      <h3 class="pane-title">分配角色 · 用户 ${o(t)}</h3>
      <div class="role-check-list">${c||'<p class="sub">无可用角色</p>'}</div>
      ${J("rbac.assign","保存角色","saveUserRoles(event)","btn-primary btn-sm")}
      <h3 class="pane-title" style="margin-top:20px">数据范围 · 商户</h3>
      <p class="sub">不勾选任何商户 = 全局可见；勾选后仅可见对应商户的设备/订单/分账。</p>
      <div class="role-check-list">${u||'<p class="sub">暂无商户，请先在商户分账页创建</p>'}</div>
      ${J("rbac.assign","保存商户范围","saveUserMerchants(event)","btn-primary btn-sm")}`,nt()}catch(n){g(n)||(e.innerHTML='<p class="err">'+o(n.message)+"</p>")}}}async function ei(t){await U(t,async()=>{var a;const e=(a=window._rbacState)==null?void 0:a.selectedUserId;if(!e){r("请先选择运营账号","err");return}const n=[...document.querySelectorAll(".rbac-merchant-cb:checked")].map(s=>s.value);try{await v("/api/v2/ops/admin/rbac/users/"+e+"/merchants","PUT",n),r("商户数据范围已保存","ok"),Fe(e)}catch(s){g(s)||r("保存失败: "+s.message,"err")}})}async function ni(t){await U(t,async()=>{var a;const e=(a=window._rbacState)==null?void 0:a.selectedUserId;if(!e){r("请先选择运营账号","err");return}const n=[...document.querySelectorAll(".rbac-role-cb:checked")].map(s=>parseInt(s.value,10));try{await v("/api/v2/ops/admin/rbac/users/"+e+"/roles","PUT",n),r("用户授权已保存","ok"),He(),Fe(e)}catch(s){g(s)||r("保存失败: "+s.message,"err")}})}function ai(t){window._rbacState.recentScope=t,xe()}async function si(){const t=document.getElementById("rbacRecentTable");if(t){t.innerHTML='<p class="sub">加载中…</p>';try{const e=window._rbacState.recentScope==="mine",n=new URLSearchParams({size:15,mine:e?"true":"false"}),a=await v("/api/v2/ops/admin/audit-logs/recent?"+n,"GET");t.innerHTML=Qn(a,"rbacRecent"),B("rbacRecent")}catch(e){g(e)||(t.innerHTML='<p class="err">'+o(e.message)+"</p>")}}}function zn(t){return t.operatorPhone||t.operatorName?`${o(t.operatorName||"-")}<br><span class="meta">${o(t.operatorPhone||t.operatorId)}</span>`:o(t.operatorId)}function Qn(t,e="audit"){return!t||!t.length?P("暂无操作记录","运营后台的敏感操作会记录在此"):x(e,`
    <table class="table">
      <thead><tr>
        ${F(e)}
        <th>时间</th><th>操作人</th><th>动作</th><th>对象</th><th>详情</th>
      </tr></thead>
      <tbody>${t.map(n=>`
        ${G(e,n.logId)}
        ${q(e,n.logId)}
        <td>${at(n.createdAt)}</td>
        <td>${zn(n)}</td>
        <td>${o(Pa(n.action))}</td>
        <td>${o(Ra(n.targetType))} ${o(n.targetId||"")}</td>
        <td class="meta">${o(n.detail||"-")}</td>
      </tr>`).join("")}</tbody>
    </table>`)}function oi(t){window._rbacState=window._rbacState||{},window._rbacState.tab="users",window._rbacState.selectedUserId=t,navigate("rbac")}function Gt(t){return t==null?"-":(t*100).toFixed(1)+"%"}const gt={page:0,size:20,deviceId:""},oe={lowStockOnly:!1},Yn=2;function ii(t){if(!t)return"-";const e=Date.now()-new Date(t).getTime();if(e<0)return"刚刚";const n=e/36e5;return n<1?`${Math.max(1,Math.floor(e/6e4))} 分钟`:n<24?`${Math.floor(n)} 小时`:`${Math.floor(n/24)} 天`}function We(t,e=Yn){return t?Date.now()-new Date(t).getTime()>e*36e5:!1}async function Dt(){const t=document.getElementById("pageContent"),e="vision-mappings";M("visionYolo"),M("visionAliyun");try{const[n,a]=await Promise.all([v("/api/v2/ops/admin/vision-mappings","GET"),v("/api/v2/ops/admin/skus","GET")]);if(!X(e))return;const s=Object.fromEntries((a||[]).map(p=>[p.skuId,p])),i=p=>{const m=s[p];return m?`${o(m.skuName)} <code>${o(p)}</code>`:`<code>${o(p)}</code>`},c=(a||[]).map(p=>`<option value="${d(p.skuId)}">${o(p.skuName)} (${o(p.skuId)})</option>`).join(""),l=(n.yolo||[]).map(p=>`
      ${G("visionYolo",p.className)}
      ${q("visionYolo",p.className)}
      <td><code>${o(p.className)}</code></td>
      <td>${i(p.skuId)}</td>
      <td>${o(p.minConfidence)}</td>
      <td onclick="event.stopPropagation()">${T("ops:vision:edit")?`<button class="btn-danger btn-sm" onclick="deleteYoloMapping('${d(p.className)}')">删除</button>`:"-"}</td>
    </tr>`).join(""),u=(n.aliyun||[]).map(p=>`
      ${G("visionAliyun",p.categoryId)}
      ${q("visionAliyun",p.categoryId)}
      <td><code>${o(p.categoryId)}</code></td>
      <td>${o(p.categoryName||"-")}</td>
      <td>${i(p.skuId)}</td>
      <td>${o(p.minConfidence)}</td>
      <td onclick="event.stopPropagation()">${T("ops:vision:edit")?`<button class="btn-danger btn-sm" onclick="deleteAliyunMapping('${d(p.categoryId)}')">删除</button>`:"-"}</td>
    </tr>`).join("");t.innerHTML=`
      <div class="card"><div class="filters">${D("loadVisionMappingsPage()")}</div></div>
      <div class="card">
        <h3 style="margin-top:0">YOLO 类名 → SKU（本地联调） ${N("visionYolo")}</h3>
        ${T("ops:vision:edit")?`
        <div class="filters">
          <div><label>类名</label><input id="ymClass" placeholder="bottle"></div>
          <div><label>SKU</label><select id="ymSku">${c}</select></div>
          <div><label>最低置信度</label><input id="ymConf" type="number" step="0.01" min="0" max="1" value="0.5"></div>
          <div><button type="button" class="btn-primary btn-sm" onclick="saveYoloMapping(event)">保存</button></div>
        </div>`:""}
        ${l?x("visionYolo",`<table class="table"><thead><tr>
          ${F("visionYolo")}
          <th>类名</th><th>SKU</th><th>置信度</th><th>操作</th>
        </tr></thead><tbody>${l}</tbody></table>`):P("暂无 YOLO 映射","添加 COCO 类名与商品 SKU 的对应关系","loadVisionMappingsPage()")}
      </div>
      <div class="card">
        <h3 style="margin-top:0">阿里云类目 → SKU（生产） ${N("visionAliyun")}</h3>
        ${T("ops:vision:edit")?`
        <div class="filters">
          <div><label>类目 ID</label><input id="amCatId" placeholder="201234567"></div>
          <div><label>类目名称</label><input id="amCatName" placeholder="碳酸饮料"></div>
          <div><label>SKU</label><select id="amSku">${c}</select></div>
          <div><label>最低置信度</label><input id="amConf" type="number" step="0.01" min="0" max="1" value="0.7"></div>
          <div><button type="button" class="btn-primary btn-sm" onclick="saveAliyunMapping(event)">保存</button></div>
        </div>`:""}
        ${u?x("visionAliyun",`<table class="table"><thead><tr>
          ${F("visionAliyun")}
          <th>类目ID</th><th>名称</th><th>SKU</th><th>置信度</th><th>操作</th>
        </tr></thead><tbody>${u}</tbody></table>`):P("暂无阿里云映射","对接商品理解 API 后在此维护类目与 SKU","loadVisionMappingsPage()")}
      </div>`,B("visionYolo"),B("visionAliyun"),nt()}catch(n){if(!X(e))return;It(t,n)}}async function ci(t){await U(t,async()=>{const e=document.getElementById("ymClass").value.trim(),n=document.getElementById("ymSku").value,a=parseFloat(document.getElementById("ymConf").value)||.5;if(!e){r("请填写类名","err");return}try{await v("/api/v2/ops/admin/vision-mappings/yolo","POST",{className:e,skuId:n,minConfidence:a}),r("已保存","ok"),Dt()}catch(s){g(s)||r("保存失败: "+s.message,"err")}})}async function li(t){if(await et(`删除 YOLO 映射 ${t}？`,{title:"删除映射",danger:!0}))try{await v("/api/v2/ops/admin/vision-mappings/yolo/"+encodeURIComponent(t),"DELETE"),r("已删除","ok"),Dt()}catch(e){g(e)||r("删除失败: "+e.message,"err")}}async function di(t){await U(t,async()=>{const e=document.getElementById("amCatId").value.trim(),n=document.getElementById("amCatName").value.trim(),a=document.getElementById("amSku").value,s=parseFloat(document.getElementById("amConf").value)||.7;if(!e){r("请填写类目 ID","err");return}try{await v("/api/v2/ops/admin/vision-mappings/aliyun","POST",{categoryId:e,categoryName:n,skuId:a,minConfidence:s}),r("已保存","ok"),Dt()}catch(i){g(i)||r("保存失败: "+i.message,"err")}})}async function ri(t){if(await et(`删除阿里云映射 ${t}？`,{title:"删除映射",danger:!0}))try{await v("/api/v2/ops/admin/vision-mappings/aliyun/"+encodeURIComponent(t),"DELETE"),r("已删除","ok"),Dt()}catch(e){g(e)||r("删除失败: "+e.message,"err")}}async function Jn(){const t=document.getElementById("pageContent");M("uploadQueue"),t.innerHTML=`
    <div class="card">
      <div class="filters">
        <div><label>设备ID</label><input id="uqDevice" value="${d(gt.deviceId)}" placeholder="可选"></div>
        <div><button class="btn-primary" onclick="searchUploadQueue()">查询</button></div>
        <div>${D("fetchUploadQueue()")}</div>
        ${N("uploadQueue")}
      </div>
      <div id="uploadQueueTable"></div>
    </div>`,j(document.getElementById("uploadQueueTable"),8,6),qe()}function ui(){gt.deviceId=document.getElementById("uqDevice").value.trim(),gt.page=0,qe()}async function qe(){const t=document.getElementById("uploadQueueTable");if(t){j(t,8,6);try{const e=new URLSearchParams({page:gt.page,size:gt.size,state:"WAITING_UPLOAD",...gt.deviceId?{deviceId:gt.deviceId}:{}}),n=await v("/api/v2/ops/admin/sessions?"+e,"GET");if(!n.items.length){t.innerHTML=P("暂无待上传会话","断网续传或视频未上传的会话会出现在此","fetchUploadQueue()");return}const a=n.items.filter(i=>We(i.closeTime||i.updatedAt)).length,s=`<div class="stats stats-inline">
      <div class="stat"><div class="label">本页待上传</div><div class="value warn">${n.items.length}</div></div>
      <div class="stat"><div class="label">超时 (&gt;${Yn}h)</div><div class="value ${a?"warn":"ok"}">${a}</div></div>
      <div class="stat"><div class="label">合计</div><div class="value">${n.total}</div></div>
    </div>`;t.innerHTML=s+x("uploadQueue",`
      <table>
        <thead><tr>
          ${F("uploadQueue")}
          <th>会话ID</th><th>用户</th><th>设备</th><th>上传状态</th><th>等待时长</th><th>融合模式</th><th>视频</th><th>关门时间</th><th>更新时间</th>
        </tr></thead>
        <tbody>${n.items.map(i=>{const c=i.closeTime||i.updatedAt,l=We(c);return`
          ${G("uploadQueue",i.sessionId,l?"row-overdue":"")}
          ${q("uploadQueue",i.sessionId)}
          <td><code>${o(i.sessionId)}</code>${l?' <span class="badge badge-fail">超时</span>':""}</td>
          <td>${o(i.userId)}</td>
          <td>${o(i.deviceId)}</td>
          <td>${o(Xe(i.uploadStatus))}</td>
          <td>${o(ii(c))}</td>
          <td>${o(Ba(i.cameraFusionMode))}</td>
          <td onclick="event.stopPropagation()">${i.videoUri||i.videoPreviewUrl?`<button type="button" class="btn-ghost btn-sm" onclick="showSessionVideo('${d(i.sessionId)}', '${d(i.videoUri||"")}')">${Ie(i.videoUri)}</button>`:o(i.videoUri||"-")}</td>
          <td>${at(i.closeTime)}</td>
          <td>${at(i.updatedAt)}</td>
        </tr>`}).join("")}</tbody>
      </table>`)+pi(n),B("uploadQueue")}catch(e){W(t,e,!1)}}}function pi(t){return Zt(t,"upload")}function mi(t){const e=window._rbacState.operatorFilters;return Zt({page:e.page,size:e.size,total:t.total||0},"rbacOp")}function hi(t){return Zt(t,"merchantSplit")}const A={page:0,size:20,merchantId:"",status:"PENDING"};async function Ge(){const t=document.getElementById("pageContent");M("merchants"),M("merchantSplits");let e="";if(T("ops:merchant:split"))try{const n=await v("/api/v2/ops/admin/merchants/profit-sharing/status","GET"),a=n.apiReady?"ok":"warn";e=`<div class="demo-banner" style="${n.apiReady?"background:#f6ffed;border-color:#b7eb8f;color:#389e0d":""}">
        分账：${n.enabled?"已启用":"未启用"} · 微信支付 ${o(n.wechatPayConfigured)} · API ${n.apiReady?"就绪":"未就绪"}
        · 重试 ${n.retryEnabled?"开":"关"}(${n.retryBatchSize}/批)
        <span class="meta"> — ${o(n.note||"")}</span>
      </div>`}catch{}t.innerHTML=`
    ${e}
    <div class="card">
      <div class="filters">
        <button class="btn-primary btn-sm" data-perm="ops:merchant:edit" onclick="showMerchantForm()">新增商户</button>
        ${N("merchants")}
        ${D("loadMerchantsPage()")}
      </div>
      <div id="merchantTable" class="sub">加载中…</div>
    </div>
    <div class="card">
      <h3 style="margin-top:0">分账明细</h3>
      <div class="filters">
        <div><label>商户ID</label><input id="msMerchant" value="${d(A.merchantId)}" placeholder="可选"></div>
        <div><label>状态</label>
          <select id="msStatus">
            <option value="">全部</option>
            <option value="PENDING" ${A.status==="PENDING"?"selected":""}>待处理</option>
            <option value="ACCRUED" ${A.status==="ACCRUED"?"selected":""}>待分账</option>
            <option value="LEDGER_ONLY" ${A.status==="LEDGER_ONLY"?"selected":""}>仅记账</option>
            <option value="WECHAT_SUBMITTED" ${A.status==="WECHAT_SUBMITTED"?"selected":""}>已提交</option>
            <option value="WECHAT_FAILED" ${A.status==="WECHAT_FAILED"?"selected":""}>失败</option>
            <option value="SUBMITTED" ${A.status==="SUBMITTED"?"selected":""}>已提交(旧)</option>
            <option value="SUCCESS" ${A.status==="SUCCESS"?"selected":""}>成功</option>
            <option value="FAILED" ${A.status==="FAILED"?"selected":""}>失败(旧)</option>
          </select>
        </div>
        <div><button class="btn-primary btn-sm" onclick="searchMerchantSplits()">查询</button></div>
        <div>${D("fetchMerchantSplits()")}</div>
        ${N("merchantSplits")}
        <div><button class="btn-ghost btn-sm" onclick="exportMerchantSplits()">导出 CSV</button></div>
        ${T("ops:merchant:split")?'<div><button class="btn-ok btn-sm" id="batchSplitBtn" onclick="batchSubmitProfitSharing()" disabled>批量提交微信分账</button></div>':""}
      </div>
      <div id="merchantSplitTable"></div>
    </div>`,nt(),vi(),Mt()}async function vi(){const t=document.getElementById("merchantTable");if(t)try{const e=await v("/api/v2/ops/admin/merchants","GET");if(!e||!e.length){t.innerHTML=P("暂无商户","点击「新增商户」创建加盟商/直营主体","loadMerchantsPage()");return}t.innerHTML=x("merchants",`<table class="table"><thead><tr>
      ${F("merchants")}
      <th>商户ID</th><th>名称</th><th>平台抽成</th><th>设备数</th><th>状态</th><th>操作</th>
    </tr></thead><tbody>${e.map(n=>`
      ${G("merchants",n.merchantId)}
      ${q("merchants",n.merchantId)}
      <td><code>${o(n.merchantId)}</code></td>
      <td>${o(n.merchantName)}</td>
      <td>${(n.platformRateBps/100).toFixed(1)}%</td>
      <td>${o(n.deviceCount)}</td>
      <td>${o(Ea(n.status))}</td>
      <td onclick="event.stopPropagation()">${T("ops:merchant:edit")?`<button class="btn-ghost btn-sm" onclick='showMerchantForm(${JSON.stringify(n)})'>编辑</button>`:"-"}</td>
    </tr>`).join("")}</tbody></table>`),B("merchants")}catch(e){W(t,e,!1)}}function bi(t){const e=!!t;it(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>${e?"编辑商户":"新增商户"}</h3>
        <label>商户ID</label>
        <input id="mfId" value="${e?d(t.merchantId):""}" ${e?"disabled":""} placeholder="MCH-001">
        <label>商户名称</label>
        <input id="mfName" value="${e?d(t.merchantName):""}">
        <label>联系电话</label>
        <input id="mfPhone" value="${e?d(t.contactPhone||""):""}">
        <label>平台抽成（基点，1000=10%）</label>
        <input id="mfRate" type="number" min="0" max="10000" value="${e?d(t.platformRateBps):"1000"}">
        <label>微信分账接收方 ID（可选）</label>
        <input id="mfWx" value="${e?d(t.wechatReceiverId||""):""}">
        <label>备注</label>
        <input id="mfRemark" value="${e?d(t.remark||""):""}">
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveMerchant(event, ${e})">保存</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`)}async function gi(t,e){await U(t,async()=>{const n={merchantId:document.getElementById("mfId").value.trim(),merchantName:document.getElementById("mfName").value.trim(),contactPhone:document.getElementById("mfPhone").value.trim(),platformRateBps:parseInt(document.getElementById("mfRate").value,10)||1e3,wechatReceiverId:document.getElementById("mfWx").value.trim(),remark:document.getElementById("mfRemark").value.trim(),status:"ACTIVE"};if(!n.merchantId||!n.merchantName){r("请填写商户 ID 和名称","err");return}try{await v("/api/v2/ops/admin/merchants","POST",n),dt(),r("保存成功","ok"),Ge()}catch(a){g(a)||r("保存失败: "+a.message,"err")}})}function fi(){A.merchantId=document.getElementById("msMerchant").value.trim(),A.status=document.getElementById("msStatus").value,A.page=0,M("merchantSplits"),Mt()}function ge(){const t=document.getElementById("batchSplitBtn");if(!t)return;const e=ke("merchantSplits").length;t.disabled=e===0,t.textContent=e?`批量提交微信分账 (${e})`:"批量提交微信分账"}async function Mt(){const t=document.getElementById("merchantSplitTable");if(t){j(t,8,6);try{const e=new URLSearchParams({page:A.page,size:A.size,...A.merchantId?{merchantId:A.merchantId}:{},...A.status?{status:A.status}:{}}),n=await v("/api/v2/ops/admin/merchants/revenue-splits?"+e,"GET");if(!n.items.length){t.innerHTML=P("暂无分账记录",A.status==="PENDING"?"没有待处理的分账，订单结算后会自动记账":"订单结算后会按设备所属商户自动记账","fetchMerchantSplits()"),ge();return}t.innerHTML=x("merchantSplits",`<table><thead><tr>
      ${F("merchantSplits")}
      <th>分账ID</th><th>订单</th><th>商户</th><th>设备</th><th>总额</th><th>平台</th><th>商户收入</th><th>状态</th><th>时间</th><th>操作</th>
    </tr></thead><tbody>${n.items.map(a=>{const s=yi(a),i=$i(a);return`
      ${G("merchantSplits",a.splitId)}
      ${q("merchantSplits",a.splitId)}
      <td><code>${o(a.splitId)}</code></td>
      <td>${o(a.orderId)}</td>
      <td>${o(a.merchantName||a.merchantId)}</td>
      <td>${o(a.deviceId)}</td>
      <td>${ct(a.grossCents)}</td>
      <td>${ct(a.platformCents)}</td>
      <td>${ct(a.merchantCents)}</td>
      <td>${_a(a.status)}${a.failureReason?` <span class="meta" title="${d(a.failureReason)}">!</span>`:""}</td>
      <td>${at(a.createdAt)}</td>
      <td onclick="event.stopPropagation()">${[s?`<button type="button" class="btn-ghost btn-sm" onclick="showWeChatSubmitForm('${d(a.splitId)}', '${d(a.wechatTransactionId||"")}')">提交</button>`:"",i?`<button type="button" class="btn-ghost btn-sm" onclick="refreshWeChatProfitSharing('${d(a.splitId)}')">刷新</button>`:""].filter(Boolean).join(" ")||"-"}</td>
    </tr>`}).join("")}</tbody></table>`)+hi(n),B("merchantSplits"),ge()}catch(e){W(t,e,!1)}}}function yi(t){if(!T("ops:merchant:split"))return!1;const e=(t.status||"").toUpperCase();return e==="ACCRUED"||e==="LEDGER_ONLY"||e==="WECHAT_FAILED"||e==="FAILED"}function $i(t){if(!T("ops:merchant:split"))return!1;const e=(t.status||"").toUpperCase();return(e==="WECHAT_SUBMITTED"||e==="WECHAT_FAILED")&&!!(t.wechatOutOrderNo&&t.wechatTransactionId)}function Ii(t,e){it(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" onclick="event.stopPropagation()">
        <h3>提交微信分账</h3>
        <p class="meta">分账ID <code>${o(t)}</code></p>
        <label>微信交易单号 wxTransactionId</label>
        <input id="wxTxnId" value="${d(e||"")}" placeholder="余额支付订单需手动填写">
        <p class="meta">购物订单为余额支付时，需填写对应微信充值/支付流水号。</p>
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="submitWeChatProfitSharing(event, '${d(t)}')">提交</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`)}async function ki(t,e){await U(t,async()=>{var a;const n=((a=document.getElementById("wxTxnId"))==null?void 0:a.value.trim())||"";try{await v("/api/v2/ops/admin/merchants/revenue-splits/"+encodeURIComponent(e)+"/wechat-submit","POST",n?{wxTransactionId:n}:{}),dt(),r("分账已提交","ok"),Mt()}catch(s){g(s)||r("提交失败: "+s.message,"err")}},"提交中…")}async function wi(t){try{await v("/api/v2/ops/admin/merchants/revenue-splits/"+encodeURIComponent(t)+"/wechat-refresh","POST",{}),r("状态已刷新","ok"),Mt()}catch(e){g(e)||r("刷新失败: "+e.message,"err")}}async function Si(){const t=ke("merchantSplits");if(!t.length||!await et(`确认批量提交 ${t.length} 笔微信分账？
已有 wxTransactionId 的记录将自动提交；缺少流水号的会跳过。`,{title:"批量分账"}))return;const e=document.getElementById("batchSplitBtn");e&&(e.disabled=!0);let n=0,a=0;const s=[];for(const c of t)try{await v("/api/v2/ops/admin/merchants/revenue-splits/"+encodeURIComponent(c)+"/wechat-submit","POST",{}),n+=1,an("merchantSplits",c,!1)}catch(l){if(g(l))break;const u=l.message||String(l);/wxTransactionId|流水|余额支付/i.test(u)?a+=1:s.push(`${c}: ${u}`)}const i=[`成功 ${n} 笔`];a&&i.push(`跳过 ${a} 笔（缺流水号）`),s.length&&i.push(`失败 ${s.length} 笔`),r(i.join("，"),s.length?"err":"ok"),s.length&&console.warn("批量分账失败:",s),Mt()}async function Ei(){try{const t=({}.VITE_API_BASE||"").replace(/\/$/,"")||window.location.origin,e=new URLSearchParams({...A.merchantId?{merchantId:A.merchantId}:{},...A.status?{status:A.status}:{}}),n=await fetch(t+"/api/v2/ops/admin/merchants/revenue-splits/export?"+e,{headers:{Authorization:"Bearer "+localStorage.getItem("admin_token")}});if(!n.ok)throw new Error("导出失败");const a=await n.blob(),s=URL.createObjectURL(a),i=document.createElement("a");i.href=s,i.download="revenue-splits.csv",i.click(),URL.revokeObjectURL(s)}catch(t){r("导出失败: "+t.message,"err")}}async function Ft(){const t=document.getElementById("pageContent"),e="warehouse",n=window._transitDeviceFilter||"";try{const a=n?"/api/v2/ops/admin/warehouse/in-transit?deviceId="+encodeURIComponent(n):"/api/v2/ops/admin/warehouse/in-transit",[s,i,c,l,u]=await Promise.all([v("/api/v2/ops/admin/warehouse/list","GET"),v("/api/v2/ops/admin/warehouse/inventory","GET"),v("/api/v2/ops/admin/warehouse/outbounds","GET"),v("/api/v2/ops/admin/skus","GET").catch(()=>[]),v(a,"GET").catch(()=>[])]);if(!X(e))return;const p=Object.fromEntries((l||[]).map(y=>[y.skuId,y])),m=y=>{var f;return((f=p[y])==null?void 0:f.skuName)||y},I=(s||[])[0],k=(i||[]).map(y=>`<tr>
      <td><code>${o(y.batchNo)}</code></td>
      <td>${o(m(y.skuId))}</td>
      <td>${o(y.quantity)}</td>
      <td>${o(y.expiryDate||"-")}</td>
    </tr>`).join(""),L=(c||[]).slice(0,10).map(y=>{const f=(y.lines||[]).map(S=>`${o(m(S.skuId))}×${S.quantity}@${o(S.batchNo)}→${o(S.deviceId||"-")}`).join("<br>");return`<tr>
        <td>#${y.outboundId}</td><td>${o(y.status)}</td><td>${y.routeId||"-"}</td>
        <td>${f||"-"}</td>
        <td onclick="event.stopPropagation()">${T("ops:replenishment:edit")&&y.status!=="SHIPPED"?`<button class="btn-ghost btn-sm" onclick="pickWarehouseOutbound(${y.outboundId})">拣货</button>
             <button class="btn-ok btn-sm" onclick="shipWarehouseOutbound(${y.outboundId})">出库</button>`:"-"}</td>
      </tr>`}).join(""),_=Date.now(),h=(u||[]).map(y=>{const S=(y.createdAt?Math.round((_-new Date(y.createdAt).getTime())/36e5):0)>=24;return`<tr class="${S?"warn-row":""}">
        <td><code>${o(y.deviceId)}</code></td>
        <td>${o(m(y.skuId))}</td>
        <td><code>${o(y.batchNo)}</code></td>
        <td>${y.quantity}</td>
        <td>#${y.outboundId}</td>
        <td>${at(y.createdAt)}${S?' <span class="warn-text">超24h</span>':""}</td>
      </tr>`}).join("");t.innerHTML=`
      <div class="filters">
        ${J("replenish.edit","仓库入库","showWarehouseInboundForm()","btn-primary btn-sm")}
        ${D("loadWarehousePage()")}
      </div>
      <p class="meta">${I?`当前仓库：${o(I.warehouseName)} (${o(I.warehouseId)})`:"暂无仓库"}</p>
      <h3>仓库批次库存</h3>
      ${(i||[]).length?`<table class="table"><thead><tr><th>批次</th><th>商品</th><th>数量</th><th>到期</th></tr></thead><tbody>${k}</tbody></table>`:P("仓库无库存","点击「仓库入库」添加批次","loadWarehousePage()")}
      <h3>出库单（FEFO 拣货）</h3>
      ${(c||[]).length?`<table class="table"><thead><tr><th>ID</th><th>状态</th><th>路线</th><th>明细</th><th>操作</th></tr></thead><tbody>${L}</tbody></table>`:'<p class="meta">规划补货路线后自动生成出库单</p>'}
      <h3 style="margin-top:24px">在途库存（发往柜机，未签收）</h3>
      <div class="filters">
        <div><label>柜机筛选</label><input id="transitDeviceFilter" value="${d(n)}" placeholder="留空=全部"></div>
        <button type="button" class="btn-ghost btn-sm" onclick="filterInTransit()">查询</button>
      </div>
      ${(u||[]).length?`<table class="table"><thead><tr><th>柜机</th><th>SKU</th><th>批次</th><th>数量</th><th>出库单</th><th>发运时间</th></tr></thead><tbody>${h}</tbody></table>`:P("无在途库存","出库发运后、补货签收前会显示在此","loadWarehousePage()")}`,nt()}catch(a){if(!X(e))return;It(t,a)}}async function Ti(){const e=(await v("/api/v2/ops/admin/skus","GET").catch(()=>[])||[]).filter(s=>s.status==="ACTIVE").map(s=>`<option value="${d(s.skuId)}">${o(s.skuName)}</option>`).join(""),n=new Date().toISOString().slice(0,10),a=new Date(Date.now()+30*864e5).toISOString().slice(0,10);it(`
    <div class="modal-backdrop" onclick="closeModal(event)">
      <div class="modal" style="max-width:560px" onclick="event.stopPropagation()">
        <h3>仓库入库</h3>
        <label>SKU</label><select id="whInSku">${e}</select>
        <label>批次号</label><input id="whInBatch" placeholder="B-WH-001">
        <div class="filters form-grid">
          <div><label>数量</label><input id="whInQty" type="number" min="1" value="10"></div>
          <div><label>到期日</label><input id="whInExpiry" type="date" value="${a}"></div>
        </div>
        <label>生产日期</label><input id="whInProd" type="date" value="${n}">
        <div class="filters" style="margin-top:12px">
          <button type="button" class="btn-primary" onclick="saveWarehouseInbound(event)">确认入库</button>
          <button type="button" class="btn-ghost" data-modal-cancel onclick="closeModal()">取消</button>
        </div>
      </div>
    </div>`)}async function Li(t){var c,l,u,p,m,I;t&&t.preventDefault();const e=(c=document.getElementById("whInSku"))==null?void 0:c.value,n=(u=(l=document.getElementById("whInBatch"))==null?void 0:l.value)==null?void 0:u.trim(),a=parseInt((p=document.getElementById("whInQty"))==null?void 0:p.value,10),s=(m=document.getElementById("whInExpiry"))==null?void 0:m.value,i=(I=document.getElementById("whInProd"))==null?void 0:I.value;if(!e||!n||!a||!s){r("请填写完整","err");return}try{await v("/api/v2/ops/admin/warehouse/inbound","POST",{warehouseId:"WH-DEMO-001",refNo:"IN-"+Date.now(),lines:[{skuId:e,batchNo:n,quantity:a,expiryDate:s,productionDate:i}]}),dt(),r("入库成功","ok"),Ft()}catch(k){g(k)||r("入库失败: "+k.message,"err")}}async function Ci(t){try{await v("/api/v2/ops/admin/warehouse/outbounds/"+t+"/pick","POST"),r("已标记拣货","ok"),Ft()}catch(e){g(e)||r("操作失败: "+e.message,"err")}}async function Bi(t){if(await et("确认出库？将扣减仓库库存。",{title:"出库确认"}))try{await v("/api/v2/ops/admin/warehouse/outbounds/"+t+"/ship","POST"),r("出库完成","ok"),Ft()}catch(e){g(e)||r("出库失败: "+e.message,"err")}}$t.opsLoaders={sla:Fn,ota:Ne,risk:se,reconciliation:qn,replenishment:rt,warehouse:Ft,rbac:jn,visionMappings:Dt,uploadQueue:Jn,merchants:Ge};Object.assign(window,{loadSlaPage:Fn,loadOtaPage:Ne,loadRiskPage:se,showBlacklistForm:To,saveBlacklist:Lo,removeBlacklist:Bo,loadReconciliationPage:qn,loadReplenishmentPage:rt,showInventoryForm:Mo,showSkuStocktakeForm:No,saveSkuStocktake:Uo,showWriteOffForm:xo,saveWriteOff:_o,saveInventory:Oo,completeReplenishmentTask:Ho,loadRbacPage:jn,showOtaPublishForm:So,publishOta:Eo,fetchReconciliationList:Ue,runReconToday:Po,showReconDetail:Ro,showReplenishmentPlanForm:Vo,saveReplenishmentPlan:zo,getSelectedReplenishmentDevices:Gn,toggleAllReplenishmentDevices:Wo,switchRbacTab:Qo,selectRbacRole:Zo,saveRolePermissions:ti,searchRbacOperators:Wn,debouncedSearchRbacOperators:Co,selectRbacUser:Fe,saveUserRoles:ni,saveUserMerchants:ei,setRbacRecentScope:ai,fetchRbacRecent:si,onPermCheckChange:Xo,openRbacUserAssign:oi,renderAuditTableHtml:Qn,formatOperatorCell:zn,loadVisionMappingsPage:Dt,saveYoloMapping:ci,deleteYoloMapping:li,saveAliyunMapping:di,deleteAliyunMapping:ri,loadUploadQueuePage:Jn,searchUploadQueue:ui,fetchUploadQueue:qe,loadMerchantsPage:Ge,showMerchantForm:bi,saveMerchant:gi,searchMerchantSplits:fi,fetchMerchantSplits:Mt,exportMerchantSplits:Ei,showWeChatSubmitForm:Ii,submitWeChatProfitSharing:ki,batchSubmitProfitSharing:Si,toggleReplenishmentLowStock:Ao,planRouteFromLowStock:Do});document.addEventListener("selchange",t=>{var e;((e=t.detail)==null?void 0:e.scope)==="merchantSplits"&&ge()});window.merchantSplitFilters=A;window.uploadQueueFilters=gt;window.replenishmentFilters=oe;window.showReplenishmentLinesForm=Fo;window.addReplenishmentLineRow=zt;window.saveReplenishmentLines=qo;window.viewDeviceLots=Go;window.loadWarehousePage=Ft;window.refreshWeChatProfitSharing=wi;window.showWarehouseInboundForm=Ti;window.saveWarehouseInbound=Li;window.pickWarehouseOutbound=Ci;window.shipWarehouseOutbound=Bi;Xn();
