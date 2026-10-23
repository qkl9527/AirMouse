/* Agent 手机端：复用遥控器配对连接；输入草稿只在当前页面内存中保留。 */
(() => {
  'use strict';
  const transport = window.airmouseAgentTransport;
  if (!transport) return;
  const view = document.createElement('section');
  view.className = 'view agent-view'; view.id = 'view-agent'; view.dataset.view = 'agent';
  view.innerHTML = `
    <header class="agent-head"><div><h1>客厅 Agent</h1><p id="agentModelLabel">选择提供商和模型</p></div><div class="agent-head-actions"><button type="button" class="agent-settings-button" id="agentDisplay">电视显示</button><button type="button" class="agent-settings-button" id="agentSettings">模型设置</button></div></header>
    <button type="button" class="agent-session-entry" id="agentSessions"><span><small id="agentSessionKind">当前会话 · 保存在电视</small><strong id="agentSessionTitle">新会话</strong></span><span>切换 / 新建 ↗</span></button>
    <p class="agent-context-note" id="agentContextNotice" role="status" hidden></p>
    <div class="agent-launch"><div><strong id="agentTvTitle">让答案在电视上展开</strong><p id="agentTvStatus">手机提问，电视同步展示</p></div><button type="button" id="agentOpen">在电视打开</button></div>
    <section class="agent-empty" id="agentEmpty"><h2>一个问题，<br>让客厅有了新话题。</h2><p>聊想法、找灵感，或发一张图片。<br>从这里开始，把答案交给大屏。</p><div class="agent-prompts"><button type="button" data-agent-prompt="推荐一部适合全家一起看的电影，并说说推荐理由。">今晚看什么</button><button type="button" data-agent-prompt="帮我规划一个轻松的周末，先问我三个问题。">聊聊周末计划</button></div></section>
    <div class="agent-reading-tools"><button type="button" id="agentLatest" hidden>↓ 到最新回答底部</button></div>
    <div class="agent-chat" id="agentMessages" aria-label="对话记录"></div>
    <p class="agent-progress" id="agentProgress" role="status" aria-live="polite"></p>
    <p class="agent-error" id="agentError" role="alert" hidden></p>
    <section class="agent-composer-dock" id="agentComposerDock" aria-label="Agent 输入区">
    <div class="agent-dock-toolbar"><button type="button" id="agentAccessSettings" class="agent-access-chip" data-mode="approval" aria-haspopup="dialog">待批准模式 ▾</button><button type="button" id="agentComposeToggle" aria-expanded="true" aria-controls="agentComposeBody"><span id="agentComposeToggleLabel">收起输入框</span><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg></button></div>
    <div class="agent-compose-body" id="agentComposeBody">
    <form class="agent-compose" id="agentForm">
      <div class="agent-attachment" id="agentAttachment" hidden><img id="agentPreview" alt="待发送图片"><span id="agentImageName"></span><button type="button" id="agentRemoveImage" aria-label="移除图片">移除</button></div>
      <label for="agentText" class="agent-sr">发送给 Agent 的问题</label><textarea id="agentText" maxlength="8000" placeholder="想聊什么？也可以先加一张图片…" rows="3"></textarea>
      <div class="agent-composer-tools"><div class="agent-compose-actions"><button type="button" id="agentPick">＋ 图片</button><button type="button" id="agentImageLink">图片链接</button></div><button type="submit" class="agent-send" id="agentSend">发送</button><button type="button" id="agentStop" hidden>停止</button></div>
      <input type="file" id="agentFile" accept="image/jpeg,image/png,image/webp" hidden>
    </form>
    <div class="agent-foot"><p id="agentProviderFoot">已配对手机共享本次对话。</p><button type="button" id="agentClear">新对话</button></div>
    </div></section>`;
  document.querySelector('main.app').append(view);
  const nav = document.createElement('button');
  nav.type = 'button'; nav.className = 'nav-button'; nav.dataset.panel = 'agent';
  nav.innerHTML = '<svg class="agent-nav-icon" viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="3" width="18" height="15" rx="5"/><path d="m7 18-2 4 8-4M8 10h8M12 6v8"/></svg><span>Agent</span>';
  const scenesNav = document.querySelector('[data-panel="scenes"]');
  scenesNav.parentNode.insertBefore(nav, scenesNav);
  nav.addEventListener('click', () => { transport.showPanel('agent'); sync(); });
  const modal = document.createElement('div'); modal.className = 'agent-modal'; modal.hidden = true;
  modal.innerHTML = `<section class="agent-modal-card" role="dialog" aria-modal="true" aria-labelledby="agentModalTitle" tabindex="-1"><div class="agent-modal-head"><h2 id="agentModalTitle">模型设置</h2><button type="button" id="agentModalClose" aria-label="关闭面板">关闭</button></div><div id="agentConfigFields"><div id="agentProviderSettings"></div><details class="agent-shared-settings"><summary>电视阅读与调试</summary><div class="agent-stream-setting"><div><strong>流式输出</strong><p>逐步显示回答，自动跟随最新内容。更改从下一条问题生效。</p></div><button type="button" id="agentStreamToggle" role="switch" aria-checked="true">开启</button></div><label for="agentTextSize">电视对话内容字号（px） <output id="agentTextSizeValue">28</output><input id="agentTextSize" type="range" min="12" max="48" step="1" value="28"></label><p>12–48px，调整后立即同步到电视并保存。</p><div class="agent-stream-setting"><div><strong>Agent Debug</strong><p>查看电视 WebView、连接和页面错误，不记录聊天或 Key。</p></div><button type="button" id="agentDebugToggle" role="switch" aria-checked="false">关闭</button></div><button type="button" id="agentDebugRefresh">刷新诊断报告</button><button type="button" id="agentDebugCopy" hidden>复制诊断报告</button><textarea id="agentDebugReport" class="agent-debug-report" rows="10" readonly hidden aria-label="Agent 诊断报告"></textarea><p>普通 HTTP 模式的配置经局域网传输，请在可信网络中配置。</p></details></div><div id="agentAccessFields" hidden><p>选择 Agent 操作电视的方式。此设置保存在电视，对所有已配对手机生效。</p><fieldset class="agent-access-options"><legend>操作权限</legend><label class="agent-access-option"><input type="radio" name="agentAccessMode" value="approval" checked><span><strong>待批准（默认）</strong><small>每次操作显示具体步骤，等待手机选择“允许”或“拒绝”。</small></span></label><label class="agent-access-option"><input type="radio" name="agentAccessMode" value="full"><span><strong>完全访问</strong><small>Agent 直接执行已开放的电视操作，不弹出审批、不等待逐次允许。操作结果仍记录在对话中，可随时停止。</small></span></label></fieldset><p id="agentAccessBusy" hidden>请先停止当前回答或等待操作完成，再切换权限。</p><button type="button" id="agentSaveAccess" class="accent">保存操作权限</button></div><div id="agentLinkFields" hidden><label for="agentUrl">公开 HTTPS 图片链接<input id="agentUrl" type="url" placeholder="https://…/photo.jpg" maxlength="2048"></label><p>当前模型提供商将读取这张图片。不要使用需要登录或仅局域网可见的地址。</p><button type="button" class="accent" id="agentUseUrl">添加图片</button></div><div id="agentClearFields" hidden><p id="agentSessionHint">普通会话保存在电视，随时回来继续。一次性对话离开后不保留。所有已配对手机共享当前会话。</p><div class="agent-session-create"><button type="button" class="accent" id="agentConfirmClear">＋ 新建会话</button><button type="button" id="agentTemporary">一次性对话</button></div><p id="agentSessionBusy" hidden>请先停止当前回答或等待操作完成，再切换会话。</p><div id="agentSessionList" class="agent-session-list"></div></div><p id="agentModalError" class="agent-error" role="alert"></p></section>`;
  document.body.append(modal);
  const $ = id => document.getElementById(id);
  let state = { configured:false, busy:false, tvVisible:false, revision:-1, messages:[] };
  let image = '', imageName = '', preparing = false, imageGeneration = 0, localError = '', connected = false;
  let returnFocus = null, openingTimer = null, submission = null, sizeTimer=null;
  const pending = new Map();
  const drafts=new Map();
  let sessionChanging=false, deleteArmed=null, accessDirty=false, accessSaving=false;
  const showError = message => { localError = message; renderControls(); };
  const requestId = () => `agent-${Date.now().toString(36)}-${Math.random().toString(36).slice(2,10)}`;
  function command(type, values = {}, onSuccess, onError) {
    const id = requestId();
    if (!transport.send({ type, requestId:id, ...values })) { (onError || showError)('电视尚未连接，请等待重新连接。'); return null; }
    const timer = setTimeout(() => {
      pending.delete(id);
      (onError || showError)('电视暂未确认，请检查连接。已发送的内容可能已被接收，请先核对对话。');
    }, type==='agent_provider_models'?45000:12000);
    pending.set(id, { timer, type, onSuccess, onError });
    return id;
  }
  function sync() { command('agent_sync'); }
  const providerUI=window.createAgentProviderSettings($('agentProviderSettings'),{command,notify:message=>{$('agentProgress').textContent=message;}});
  function closeModal() { modal.hidden = true; providerUI.close(); if(returnFocus) returnFocus.focus(); }
  function openModal(kind) {
    returnFocus = document.activeElement;
    if(kind==='config')providerUI.open(state.providerSettings,state.busy);
    if(kind==='config' || kind==='display')syncConfiguration();
    $('agentProviderSettings').hidden=kind!=='config';
    modal.querySelector('.agent-shared-settings').open=kind==='display';
    modal.classList.toggle('is-provider-settings',kind==='config');
    if(kind==='clear')renderSessions();
    if(kind==='access'){accessDirty=false;syncAccessMode();}
    $('agentAccessFields').hidden=kind!=='access';
    $('agentConfigFields').hidden = kind !== 'config' && kind !== 'display'; $('agentLinkFields').hidden = kind !== 'link'; $('agentClearFields').hidden = kind !== 'clear';
    $('agentModalTitle').textContent = {config:'模型设置',display:'电视显示与调试',access:'Agent 操作权限',link:'添加图片链接',clear:'会话'}[kind];
    $('agentModalError').textContent = ''; modal.hidden = false;
    modal.querySelector('[role="dialog"]').focus();
  }
  $('agentSettings').onclick = () => openModal('config');
  $('agentDisplay').onclick = () => openModal('display');
  $('agentAccessSettings').onclick = () => openModal('access');
  $('agentImageLink').onclick = () => openModal('link');
  $('agentClear').onclick = () => openModal('clear');
  $('agentSessions').onclick = () => openModal('clear');
  $('agentModalClose').onclick = closeModal;
  modal.onclick = event => { if(event.target === modal) closeModal(); };
  modal.addEventListener('keydown', event => {
    if(event.key === 'Escape') { closeModal(); return; }
    if(event.key !== 'Tab') return;
    const nodes = [...modal.querySelectorAll('button,a,input,select,textarea,summary,[tabindex="0"]')].filter(node => !node.disabled && node.getClientRects().length);
    const first = nodes[0], last = nodes[nodes.length - 1];
    if(event.shiftKey && (document.activeElement === first || document.activeElement === modal.querySelector('[role="dialog"]'))) { event.preventDefault(); last.focus(); }
    else if(!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
  });
  function syncAccessMode(){
    const mode=state.accessMode==='full'?'full':'approval';
    $('agentAccessSettings').textContent=mode==='full'?'完全访问 ▾':'待批准模式 ▾';
    $('agentAccessSettings').dataset.mode=mode;
    if(!accessDirty)modal.querySelectorAll('[name="agentAccessMode"]').forEach(input=>input.checked=input.value===mode);
    $('agentSaveAccess').disabled=accessSaving || !!state.busy || !connected;
    $('agentSaveAccess').textContent=accessSaving?'正在保存…':'保存操作权限';
    $('agentAccessBusy').hidden=!state.busy;
    modal.querySelectorAll('[name="agentAccessMode"]').forEach(input=>input.disabled=accessSaving);
  }
  modal.querySelectorAll('[name="agentAccessMode"]').forEach(input=>input.onchange=()=>{accessDirty=true;});
  $('agentSaveAccess').onclick=()=>{
    const selected=modal.querySelector('[name="agentAccessMode"]:checked');
    if(!selected || accessSaving || state.busy || !connected)return;
    accessSaving=true;syncAccessMode();
    command('agent_access_mode',{value:selected.value,expectedMode:state.accessMode || 'approval'},()=>{
      accessSaving=false;accessDirty=false;closeModal();syncAccessMode();
    },message=>{accessSaving=false;$('agentModalError').textContent=message;syncAccessMode();});
  };
  function syncConfiguration(){
    providerUI.sync(state.providerSettings,state.busy);
    const debugEnabled=!!state.diagnostics?.enabled;
    $('agentDebugToggle').setAttribute('aria-checked',String(debugEnabled));
    $('agentDebugToggle').textContent=debugEnabled?'开启':'关闭';
    if(document.activeElement!==$('agentTextSize')){
      $('agentTextSize').value=state.configuration?.textSize || 28;
      $('agentTextSizeValue').textContent=String(state.configuration?.textSize || 28);
    }
  }
  function refreshDiagnostics(){
    $('agentDebugRefresh').disabled=true;
    command('agent_debug_get',{},response=>{
      const report={...response.data,phone:{connected,revision:state.revision,protocol:location.protocol,viewport:innerWidth+' × '+innerHeight,userAgent:navigator.userAgent}};
      $('agentDebugReport').value=JSON.stringify(report,null,2);
      $('agentDebugReport').hidden=false;$('agentDebugCopy').hidden=false;$('agentDebugRefresh').disabled=false;
    },message=>{$('agentDebugRefresh').disabled=false;$('agentModalError').textContent=message;});
  }
  $('agentDebugRefresh').onclick=refreshDiagnostics;
  $('agentDebugToggle').onclick=()=>{
    $('agentDebugToggle').disabled=true;
    command('agent_debug',{enabled:!state.diagnostics?.enabled},()=>{$('agentDebugToggle').disabled=false;refreshDiagnostics();},message=>{
      $('agentDebugToggle').disabled=false;$('agentModalError').textContent=message;
    });
  };
  $('agentDebugCopy').onclick=async()=>{
    const report=$('agentDebugReport');
    try {
      if(navigator.clipboard && window.isSecureContext)await navigator.clipboard.writeText(report.value);
      else {report.focus();report.select();if(!document.execCommand('copy'))throw new Error();}
      $('agentModalError').textContent='诊断报告已复制。';
    }catch(_){report.focus();report.select();$('agentModalError').textContent='请长按报告手动复制。';}
  };
  $('agentTextSize').addEventListener('input',()=>{
    $('agentTextSizeValue').textContent=$('agentTextSize').value;clearTimeout(sizeTimer);
    const value=Number($('agentTextSize').value);
    sizeTimer=setTimeout(()=>command('agent_text_size',{value},null,message=>{$('agentModalError').textContent=message;}),250);
  });
  function changeSession(type,values){
    if(sessionChanging || state.busy || submission)return;
    sessionChanging=true;renderSessions();renderControls();
    command(type,{...values,expectedSessionId:state.session?.id},()=>{sessionChanging=false;localError='';closeModal();renderSessions();renderControls();},message=>{
      sessionChanging=false;$('agentModalError').textContent=message;renderSessions();renderControls();
    });
  }
  $('agentConfirmClear').onclick=()=>changeSession('agent_session_new',{temporary:false});
  $('agentTemporary').onclick=()=>changeSession('agent_session_new',{temporary:true});
  function renderSessions(){
    const current=state.session || {};
    $('agentSessionTitle').textContent=current.title || '新会话';
    $('agentSessionKind').textContent=current.temporary?'一次性对话 · 离开即丢弃':'当前会话 · 保存在电视';
    $('agentSessionHint').textContent=current.temporary?'当前是一次性对话：切换或新建后，此对话会被丢弃。所有已配对手机会一起切换。':'普通会话保存在电视，随时回来继续。一次性对话离开后不保留。所有已配对手机共享当前会话。';
    const disabled=state.busy || sessionChanging || !!submission || !connected;
    $('agentConfirmClear').disabled=disabled;$('agentTemporary').disabled=disabled;
    $('agentSessionBusy').hidden=!state.busy;
    // 只在列表发生变化时重建，避免流式分片不断移走键盘焦点。
    const entries=state.sessions || [];
    const signature=JSON.stringify([entries,current.id,disabled,deleteArmed]);
    if($('agentSessionList').dataset.signature===signature)return;
    $('agentSessionList').dataset.signature=signature;$('agentSessionList').textContent='';
    if(!entries.length){const empty=document.createElement('p');empty.textContent='还没有历史会话，发送第一条消息后会保存在这里。';$('agentSessionList').append(empty);}
    entries.forEach(item=>{
      const row=document.createElement('div');row.className='agent-session-row';
      const choose=document.createElement('button');choose.type='button';choose.disabled=disabled || item.id===current.id;
      const title=document.createElement('strong');title.textContent=item.title;
      const detail=document.createElement('small');detail.textContent=(item.id===current.id?'当前 · ':'')+new Date(item.updatedAt).toLocaleString()+` · ${item.messageCount} 条记录`;
      choose.append(title,detail);choose.onclick=()=>changeSession('agent_session_switch',{sessionId:item.id});row.append(choose);
      if(item.id!==current.id){
        const remove=document.createElement('button');remove.type='button';remove.className='agent-session-delete';remove.disabled=disabled;
        remove.textContent=deleteArmed===item.id?'确认删除':'删除';remove.setAttribute('aria-label',`${remove.textContent}会话：${item.title}`);
        remove.onclick=()=>{
          if(deleteArmed!==item.id){deleteArmed=item.id;renderSessions();return;}
          command('agent_session_delete',{sessionId:item.id},()=>{deleteArmed=null;sync();},message=>{$('agentModalError').textContent=message;});
        };row.append(remove);
      }
      $('agentSessionList').append(row);
    });
  }
  $('agentStop').onclick = () => command('agent_stop');
  $('agentOpen').onclick = () => {
    if(!connected) { showError('电视尚未连接，请先连接电视。'); return; }
    $('agentTvStatus').textContent = '正在打开电视页面…'; $('agentOpen').disabled = true;
    command('agent_open', {}, null, message => { clearTimeout(openingTimer); $('agentOpen').disabled = false; showError(message); });
    clearTimeout(openingTimer);
    openingTimer = setTimeout(() => {
      $('agentOpen').disabled = false;
      if(!state.tvVisible) { $('agentTvStatus').textContent = '未确认打开，可从电视 AirMouse 主页面进入'; showError('电视尚未进入 Agent。请确认电视亮屏，或从电视 AirMouse 主页面打开 Agent。'); }
    }, 7000);
  };
  function attachment() {
    $('agentAttachment').hidden = !image;
    const isLocal = image.startsWith('data:image/jpeg;base64,');
    $('agentPreview').hidden = !isLocal;
    if(isLocal) $('agentPreview').src = image; else $('agentPreview').removeAttribute('src');
    $('agentImageName').textContent = imageName;
    renderControls();
  }
  $('agentRemoveImage').onclick = () => { imageGeneration++; image = ''; imageName = ''; attachment(); };
  $('agentUseUrl').onclick = () => {
    try {
      const url = new URL($('agentUrl').value.trim());
      if(url.protocol !== 'https:' || url.username || url.password) throw new Error();
      imageGeneration++; image = url.href; imageName = '图片链接 · ' + url.hostname; attachment(); closeModal();
    } catch(_) { $('agentModalError').textContent = '请输入有效的公开 HTTPS 图片地址。'; }
  };
  $('agentPick').onclick = () => $('agentFile').click();
  $('agentFile').onchange = async () => {
    const file = $('agentFile').files[0]; $('agentFile').value = '';
    if(!file) return;
    if(file.size > 20 * 1024 * 1024 || !['image/jpeg','image/png','image/webp'].includes(file.type)) { showError('请选择 20 MB 以内的 JPEG、PNG 或 WebP 图片。'); return; }
    const version = ++imageGeneration;
    preparing = true; localError = ''; renderControls();
    const objectUrl = URL.createObjectURL(file);
    try {
      const photo = new Image();
      await new Promise((resolve,reject) => { photo.onload=resolve;photo.onerror=reject;photo.src=objectUrl; });
      // 统一转 JPEG，删除 EXIF 元数据并限制长边，避免原图堵住局域网遥控通道。
      const scale = Math.min(1, 1024 / Math.max(photo.naturalWidth,photo.naturalHeight));
      const canvas = document.createElement('canvas');canvas.width=Math.max(1,Math.round(photo.naturalWidth*scale));canvas.height=Math.max(1,Math.round(photo.naturalHeight*scale));
      const ctx=canvas.getContext('2d');ctx.fillStyle='#fff';ctx.fillRect(0,0,canvas.width,canvas.height);ctx.drawImage(photo,0,0,canvas.width,canvas.height);
      let data = canvas.toDataURL('image/jpeg',.78);
      if(data.length>650000) data=canvas.toDataURL('image/jpeg',.5);
      if(data.length>650000) throw new Error();
      if(version===imageGeneration){image=data;imageName=file.name;}
    } catch(_) { if(version===imageGeneration)showError('图片处理失败，请换一张较小的图片，或使用图片链接。'); }
    finally { URL.revokeObjectURL(objectUrl);if(version===imageGeneration){preparing=false;attachment();} }
  };
  document.querySelectorAll('[data-agent-prompt]').forEach(button => { button.onclick = () => { setComposerExpanded(true); $('agentText').value = button.dataset.agentPrompt; $('agentText').focus(); renderControls(); }; });
  $('agentText').addEventListener('input',renderControls);
  $('agentText').addEventListener('keydown', event => { if(event.key==='Enter' && (event.ctrlKey || event.metaKey) && !event.isComposing) { event.preventDefault(); $('agentForm').requestSubmit(); } });
  $('agentForm').onsubmit = event => {
    event.preventDefault();
    if(!state.configured) { openModal('config'); return; }
    if(preparing || state.busy || submission || sessionChanging || (!$('agentText').value.trim() && !image)) return;
    localError='';
    const draft={text:$('agentText').value.trim(),image};
    const id=command('agent_send',{...draft,context:transport.context(),sessionId:state.session?.id},()=>accepted(id),message=>{submission=null;showError(message);});
    if(id) { submission={id,...draft};renderControls(); }
  };
  function accepted(id) {
    if(!submission || submission.id!==id) return;
    const request=pending.get(id);
    if(request){clearTimeout(request.timer);pending.delete(id);}
    // 用户在等待收件时继续输入也不会被清掉；只有提交的那份草稿才清空。
    if($('agentText').value.trim()===submission.text) $('agentText').value='';
    if(image===submission.image){image='';imageName='';}
    submission=null;attachment();
  }
  function renderControls() {
    syncAccessMode();
    $('agentModelLabel').textContent=state.model?`${state.providerName} / ${state.model}`:'选择提供商和模型';
    $('agentProviderFoot').textContent=state.model?`已配对手机共享对话 · 发送至 ${state.providerName}`:'在模型设置中选择提供商后开始。';
    $('agentSend').hidden=state.busy; $('agentStop').hidden=!state.busy;
    $('agentSend').textContent=state.configured?'发送':'配置模型';
    $('agentSend').disabled=preparing || !!submission || sessionChanging || !state.session || (state.configured && !$('agentText').value.trim() && !image);
    $('agentPick').disabled=preparing;$('agentImageLink').disabled=preparing;
    $('agentProgress').textContent=preparing?'正在压缩图片…':submission?'正在发送至电视…':state.compacting?'上下文已达 80% · 正在压缩记忆，完成后继续回答…':state.approval?(state.approval.phase==='pending'?'等待手机确认操作…':(state.approval.automatic?'完全访问 · 正在执行操作…':'正在执行已允许的操作…')):state.busy?(state.messages?.[state.messages.length-1]?.role==='assistant'?'模型正在回答…':'电视已收到 · 模型正在思考…'):'';
    $('agentContextNotice').textContent=[state.contextNotice,state.contextEstimate?`上下文预算约 ${Math.round(state.contextEstimate/(state.configuration?.contextTokens || 32768)*100)}%（含输出预留）`:'',state.storageWarning].filter(Boolean).join(' · ');
    $('agentContextNotice').hidden=!$('agentContextNotice').textContent;
    $('agentError').textContent=localError || state.error || ''; $('agentError').hidden=!$('agentError').textContent;
  }
  const conversation=window.createAgentConversation($('agentMessages'), {
    messageClass:'agent-message',roleTag:'small',copyTag:'p',imageLabelClass:'agent-image-note',userLabel:'你',assistantLabel:'助手 / 回答',
    canScroll:()=>view.classList.contains('active') && modal.hidden && !state.approval,
    // 使用实际输入区顶边作为阅读底线；展开、附图和软键盘都会改变可阅读高度。
    clearance:()=>window.innerHeight-$('agentComposerDock').getBoundingClientRect().top+16
  });
  const dock=$('agentComposerDock'), bottomNav=document.querySelector('.nav');
  let dockFrame=null;
  function setComposerExpanded(expanded){
    const body=$('agentComposeBody'), toggle=$('agentComposeToggle');
    // 只隐藏 DOM，文字草稿、已选图片和正在发送的请求继续保留。
    if(!expanded && body.contains(document.activeElement)) toggle.focus({preventScroll:true});
    body.hidden=!expanded;
    toggle.setAttribute('aria-expanded',String(expanded));
    $('agentComposeToggleLabel').textContent=expanded?'收起输入框':'展开输入框';
    scheduleDockLayout();
  }
  $('agentComposeToggle').onclick=()=>setComposerExpanded($('agentComposeBody').hidden);
  function scheduleDockLayout(){
    if(dockFrame!==null)return;
    dockFrame=requestAnimationFrame(()=>{
      dockFrame=null;
      if(!view.classList.contains('active'))return;
      const bounds=view.getBoundingClientRect(), viewport=window.visualViewport;
      const navHeight=bottomNav.getBoundingClientRect().height;
      // iOS 的键盘只缩小 visualViewport，Android 也可能缩小布局视口。
      // 取两者底部遮挡的较大值，使输入区始终在导航栏或软键盘之上。
      const viewportBottom=viewport?viewport.offsetTop+viewport.height:window.innerHeight;
      const bottom=Math.max(navHeight,window.innerHeight-viewportBottom)+8;
      const available=Math.max(96,window.innerHeight-bottom-(viewport?.offsetTop || 0)-12);
      dock.style.left=`${bounds.left}px`;
      dock.style.width=`${bounds.width}px`;
      dock.style.bottom=`${bottom}px`;
      dock.style.setProperty('--agent-dock-max-height',`${available}px`);
      // 固定区脱离文档流，因此给最后一条消息留出等高空间。
      // app 已有导航栏留白，这里仅补输入区和键盘额外遮挡的部分。
      const reserve=Math.ceil(dock.getBoundingClientRect().height+bottom-navHeight+16);
      const previous=view.style.getPropertyValue('--agent-composer-space');
      view.style.setProperty('--agent-composer-space',`${reserve}px`);
      if(previous!==`${reserve}px`)conversation.layout();
    });
  }
  const dockObserver=new ResizeObserver(scheduleDockLayout);
  dockObserver.observe(dock);dockObserver.observe(bottomNav);
  // 切换面板后再测量，避免隐藏面板的零宽高污染位置。
  new MutationObserver(scheduleDockLayout).observe(view,{attributes:true,attributeFilter:['class']});
  window.addEventListener('resize',scheduleDockLayout,{passive:true});
  window.visualViewport?.addEventListener('resize',scheduleDockLayout,{passive:true});
  window.visualViewport?.addEventListener('scroll',scheduleDockLayout,{passive:true});
  scheduleDockLayout();
  $('agentLatest').onclick=()=>{
    conversation.latest();
    // 本机立即定位，电视经已配对连接接收同一操作；断线错误沿用现有提示。
    command('agent_latest');
  };
  $('agentStreamToggle').onclick=()=>{
    $('agentStreamToggle').disabled=true;
    command('agent_stream',{enabled:state.streaming===false},()=>{$('agentStreamToggle').disabled=false;},message=>{
      $('agentStreamToggle').disabled=false;$('agentModalError').textContent=message;
    });
  };
  function renderState(next) {
    if(next.revision<state.revision) return;
    const switched=next.session?.id && next.session.id!==state.session?.id;
    if(switched){
      // 同一手机的普通会话草稿分别留在内存；一次性会话离开即丢弃草稿和图片。
      if(state.session && !state.session.temporary)drafts.set(state.session.id,{text:$('agentText').value,image,imageName});
      if(state.session?.temporary)drafts.delete(state.session.id);
      const draft=drafts.get(next.session.id);
      imageGeneration++;preparing=false;submission=null;localError='';
      $('agentText').value=draft?.text || '';image=draft?.image || '';imageName=draft?.imageName || '';
      conversation.reset();attachment();
    }
    state=next;
    renderSessions();
    syncConfiguration();
    renderApproval(state.approval);
    if(submission && (state.messages || []).some(item=>item.id===submission.id)) accepted(submission.id);
    if(state.tvVisible){clearTimeout(openingTimer);$('agentOpen').disabled=false;}
    $('agentTvTitle').textContent=state.tvVisible?'电视已进入 Agent':'让答案在电视上展开';
    $('agentTvStatus').textContent=state.tvVisible?'对话正在大屏同步展示':'手机提问，电视同步展示';
    $('agentOpen').textContent=state.tvVisible?'再次打开':'在电视打开';
    const messages=state.messages || [];
    $('agentEmpty').hidden=messages.length>0;
    $('agentLatest').hidden=!messages.some(item=>item.role==='assistant');
    $('agentStreamToggle').setAttribute('aria-checked',String(state.streaming!==false));
    $('agentStreamToggle').textContent=state.streaming===false?'关闭':'开启';
    conversation.update(messages,{streaming:state.responseStreaming!==false,busy:state.busy});
    renderControls();
  }
  const approvalDialog=document.createElement('div');approvalDialog.className='agent-modal agent-approval';approvalDialog.hidden=true;
  approvalDialog.innerHTML='<section class="agent-modal-card" role="alertdialog" aria-modal="true" aria-labelledby="agentApprovalTitle" aria-describedby="agentApprovalHint" tabindex="-1"><p class="agent-approval-eyebrow">AIR MOUSE / 操作确认</p><h2 id="agentApprovalTitle"></h2><ol id="agentApprovalSteps"></ol><p id="agentApprovalHint"></p><p id="agentApprovalError" class="agent-error" role="alert"></p><div class="button-row"><button type="button" id="agentDeny">拒绝</button><button type="button" class="accent" id="agentAllow">允许本次操作</button></div></section>';
  document.body.append(approvalDialog);
  let approvalId=null, approvalFocus=null, deciding=false;
  function renderApproval(approval){
    if(!approval || approval.phase!=='pending'){
      const wasOpen=!approvalDialog.hidden;approvalDialog.hidden=true;approvalId=null;deciding=false;
      if(wasOpen && approvalFocus?.isConnected)approvalFocus.focus();return;
    }
    const isOwner=approval.owner===transport.clientId();
    const fresh=approvalId!==approval.id;
    if(fresh){
      approvalId=approval.id;deciding=false;approvalFocus=document.activeElement;$('agentApprovalError').textContent='';
      $('agentApprovalTitle').textContent=approval.title;
      $('agentApprovalSteps').textContent='';
      (approval.steps || []).forEach(step=>{const item=document.createElement('li');item.textContent=step;$('agentApprovalSteps').append(item);});
    }
    $('agentApprovalHint').textContent=isOwner?'允许后，电视按以上步骤执行；拒绝则不执行，Agent 继续对话。两分钟未确认会自动取消。':'等待发起这次对话的手机确认。你可以查看步骤，但不能代替其批准。';
    $('agentAllow').disabled=!isOwner || deciding || !connected;$('agentDeny').disabled=!isOwner || deciding || !connected;
    approvalDialog.hidden=false;
    if(fresh){if(isOwner)$('agentDeny').focus();else approvalDialog.querySelector('[role="alertdialog"]').focus();}
  }
  function decide(allow){
    const id=approvalId;if(!id || deciding)return;deciding=true;renderApproval(state.approval);
    command('agent_decide',{approvalId:id,allow},()=>{},message=>{deciding=false;$('agentApprovalError').textContent=message;renderApproval(state.approval);});
  }
  $('agentAllow').onclick=()=>decide(true);$('agentDeny').onclick=()=>decide(false);
  approvalDialog.addEventListener('keydown',event=>{
    if(event.key==='Escape'){event.preventDefault();if(! $('agentDeny').disabled)decide(false);return;}
    if(event.key!=='Tab')return;
    const buttons=[...approvalDialog.querySelectorAll('button')].filter(button=>!button.disabled);
    if(!buttons.length){event.preventDefault();return;}
    if(event.shiftKey && document.activeElement===buttons[0]){event.preventDefault();buttons[buttons.length-1].focus();}
    else if(!event.shiftKey && document.activeElement===buttons[buttons.length-1]){event.preventDefault();buttons[0].focus();}
  });
  window.addEventListener('airmouse-agent-message', event => {
    const message=event.detail;
    if(message.type==='agent_state'){connected=true;renderState(message);}
    if(message.type==='agent_delta' && message.revision>state.revision){
      if(message.sessionId!==state.session?.id){sync();return;}
      const messages=[...(state.messages || [])];
      // 重连或初始快照尚未到达时，不拼出缺少上下文的片段；请求一次完整状态恢复。
      if(!Number.isInteger(message.index) || message.index<0 || message.index>messages.length){sync();return;}
      messages[message.index]=message.message;
      renderState({...state,messages,busy:true,revision:message.revision});
    }
    if(message.type==='agent_result'){
      const request=pending.get(message.requestId);if(!request)return;
      clearTimeout(request.timer);pending.delete(message.requestId);
      if(message.ok){localError='';if(request.onSuccess)request.onSuccess(message);}
      else (request.onError || showError)(message.message || '操作失败');
      renderControls();
    }
    if(message.type==='action_result' && !message.success && String(message.requestId).startsWith('agent-')) showError(message.message);
    if(message.type==='agent_connection'){
      connected=message.connected;
      renderApproval(state.approval);
      renderSessions();
      if(connected) { state={...state,revision:-1};sync(); }
      else {
        const code=Number.isInteger(message.closeCode)?`（关闭码 ${message.closeCode}）`:'';
        // 保存配置时的断线应显示在当前面板中，不能只提示被面板遮住的聊天草稿。
        // 这里只显示关闭码和长度，不记录或回显 Key 内容；也不自动重发配置。
        for(const [id,request] of pending){
          if(!request.type.startsWith('agent_provider_')) continue;
          clearTimeout(request.timer);pending.delete(id);
          if(request.onError) request.onError(`提供商操作时电视连接断开${code}，请等待重连后核对配置。`);
        }
        $('agentTvStatus').textContent='连接已断开，正在重连…';
        showError(`电视连接已断开${code}，草稿仍保留。重连后会同步对话，请勿重复发送。`);
      }
    }
  });
  renderControls();
  if(transport.connected()) { connected=true; sync(); }
})();
