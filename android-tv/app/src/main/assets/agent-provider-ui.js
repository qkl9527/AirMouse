/* 提供商配置编辑器：草稿只留在当前面板，服务器状态始终不包含 Key 原文。 */
(() => {
  'use strict';
  window.createAgentProviderSettings=(root,{command,notify})=>{
    root.innerHTML=`<p id="providerActive" class="provider-active"></p><div class="provider-layout"><aside class="provider-sidebar"><div class="provider-sidebar-title">我的提供商 <span id="providerCount"></span></div><label class="provider-mobile-picker">选择提供商<select id="providerSelect"></select></label><div id="providerList" class="provider-list"></div><label>添加提供商<select id="providerTemplate"><option value="">选择平台…</option></select></label><button type="button" id="providerAdd">＋ 添加提供商</button></aside><section class="provider-editor"><div class="provider-editor-head"><div><h3 id="providerTitle">提供商</h3><p id="providerDirty">已保存</p></div><button type="button" id="providerDelete">删除提供商</button></div><p id="providerHint"></p><div class="provider-field-grid"><label>提供商名称<input id="providerName" maxlength="80"></label><label>接口协议<select id="providerProtocol"><option value="chat">OpenAI · Chat Completions</option><option value="responses">OpenAI · Responses</option><option value="anthropic">Anthropic · Messages</option></select></label><label class="provider-full">服务地址<input id="providerUrl" type="url" maxlength="2048" placeholder="https://…/v1"><small>填写 baseURL 或完整 endpoint；保存时会规范化路径。</small></label><label>认证方式<select id="providerAuth"><option value="key">API Key</option><option value="none">无需认证</option></select></label><label>API Key<input id="providerKey" type="password" autocomplete="off" spellcheck="false" autocapitalize="off" maxlength="4096"><small id="providerKeyHint"></small></label></div><label class="provider-check"><input id="providerRemoveKey" type="checkbox">保存时移除已存 Key</label><label class="provider-check provider-key-reuse" id="providerReuseRow" hidden><input id="providerReuse" type="checkbox">确认将已保存 Key 用于修改后的地址／协议</label><details class="provider-advanced"><summary>提供商参数适配</summary><label>参数格式<select id="providerFamily"><option value="generic">通用 OpenAI 兼容</option><option value="openai">OpenAI</option><option value="agnes">Agnes AI</option><option value="openrouter">OpenRouter</option><option value="deepseek">DeepSeek</option><option value="qwen">enable_thinking（如千问）</option><option value="thinking">thinking.type（如 Kimi / GLM）</option></select></label><p>使用预设时已填写。接入其他兼容服务时，可改为“通用”，并按模型文档填写扩展参数。</p></details><div class="provider-model-head"><h4>模型 <span id="providerModelCount"></span></h4><div><button type="button" id="providerModelAdd">＋ 手动添加</button><button type="button" id="providerFetch">保存并读取模型列表</button></div></div><div id="providerImport" class="provider-import" hidden><label>从接口列表选择模型<input id="providerModelSearch" placeholder="搜索模型 ID 或名称"><select id="providerRemoteModels"></select></label><button type="button" id="providerImportModel">添加选中模型</button><p id="providerImportHint"></p></div><div id="providerModels" class="provider-models"></div><p id="providerModelEmpty">还没有模型。读取接口列表，或手动填写模型 ID。</p><div id="providerModelFields" class="provider-model-card" hidden><div class="provider-model-card-head"><label class="provider-check"><input id="modelEnabled" type="checkbox">启用此模型</label><button type="button" id="providerModelDelete">移除模型</button></div><div class="provider-field-grid"><label>模型 ID<input id="modelId" maxlength="256" placeholder="填写接口要求的完整模型 ID"></label><label>显示名称<input id="modelName" maxlength="80" placeholder="可选，默认使用模型 ID"></label><label>上下文窗口（Token）<input id="modelContext" type="number" min="4096" max="2000000" step="1"></label><label>最大输出 Token<input id="modelOutput" type="number" min="128" max="262144" step="1"></label><label>思考强度<select id="modelEffort"><option value="default">提供商默认（不传参数）</option><option value="off">关闭</option><option value="minimal">Minimal</option><option value="low">Low · 低</option><option value="medium">Medium · 中</option><option value="high">High · 高</option><option value="xhigh">XHigh</option><option value="max">Max</option></select></label></div><p id="modelEffortHint"></p><div class="provider-capabilities"><label class="provider-check"><input id="modelTools" type="checkbox">支持工具调用</label><label class="provider-check"><input id="modelVision" type="checkbox">支持图片输入</label></div><p>按模型实际能力勾选。启用工具仍需手机逐次确认；上下文预算达到 80% 时自动压缩历史。</p><details class="provider-advanced"><summary>高级模型参数</summary><label id="modelTokenFieldRow">输出 Token 参数名<select id="modelTokenField"><option value="auto">自动</option><option value="max_tokens">max_tokens</option><option value="max_completion_tokens">max_completion_tokens</option></select></label><label>扩展 JSON<textarea id="modelExtra" rows="4" spellcheck="false" placeholder='{"temperature":0.7}'></textarea></label><p id="modelExtraHint"></p></details><button type="button" id="providerUseModel" class="accent">保存并设为当前模型</button></div><div class="provider-save-bar"><span id="providerSaveHint">Key 加密保存在电视；关闭面板会丢弃未保存修改。</span><button type="button" id="providerSave" class="accent">保存提供商</button></div></section></div><p id="providerError" class="agent-error" role="alert"></p>`;
    const $=id=>root.querySelector('#'+id);
    const clone=value=>JSON.parse(JSON.stringify(value));
    const uuid=prefix=>prefix+'-'+(crypto.randomUUID?crypto.randomUUID():Date.now().toString(36)+'-'+Math.random().toString(36).slice(2));
    let latest={}, drafts=new Map(), selected='', model='', dirty=new Set(), busy=false, pending=false, reading=false, opened=false, deleting=false, baseVersion=0, epoch=0;
    const fetched=new Map();
    const draft=()=>drafts.get(selected);
    const currentModel=()=>draft()?.models.find(item=>item.id===model);
    function mark(){dirty.add(selected);deleting=false;$('providerDelete').textContent='删除提供商';renderStatus();}
    function error(text){$('providerError').textContent=text || '';}
    function adopt(value,keepSelection=true){
      latest=value || {};baseVersion=latest.version || 0;const previous=selected;
      drafts=new Map((latest.providers || []).map(p=>[p.id,{...clone(p),apiKey:'',removeKey:false,confirmKeyReuse:false}]));dirty.clear();
      selected=keepSelection && drafts.has(previous)?previous:(latest.activeProvider || [...drafts.keys()][0] || '');
      if(!drafts.has(selected))selected=[...drafts.keys()][0] || '';
      model='';render();
    }
    function renderStatus(){
      const p=draft(), m=currentModel();
      $('providerDirty').textContent=dirty.has(selected)?'有未保存的修改':'已保存';
      $('providerDelete').textContent=deleting?'确认删除提供商及 Key':'删除提供商';
      const locked=pending || busy;
      root.querySelectorAll('input,select,textarea').forEach(input=>input.disabled=pending);
      root.querySelectorAll('.provider-nav-item,.provider-models button').forEach(button=>button.disabled=pending);
      ['providerSave','providerDelete','providerUseModel','providerFetch','providerModelAdd','providerModelDelete','providerImportModel','providerAdd'].forEach(id=>$(id).disabled=locked || (id!=='providerAdd' && !p));
      $('providerFetch').disabled=locked || reading || !p;
      $('providerSave').textContent=pending?'正在保存…':'保存提供商';
      $('providerFetch').textContent=reading?'正在读取…':'保存并读取模型列表';
      $('providerUseModel').textContent=latest.activeProvider===selected && latest.activeModel===model && !dirty.has(selected)?'当前正在使用':'保存并设为当前模型';
      $('providerUseModel').disabled=locked || !m || !m.enabled;
      $('providerSaveHint').textContent=busy?'正在回答或执行操作，结束后可保存配置。':'Key 加密保存在电视；关闭面板会丢弃未保存修改。';
      const original=(latest.providers || []).find(item=>item.id===selected);
      const changed=original && (original.baseUrl.replace(/\/$/,'')!==(p?.baseUrl || '').replace(/\/$/,'') || original.protocol!==p?.protocol);
      $('providerReuseRow').hidden=!(changed && original.hasKey && !p.apiKey && !p.removeKey && p.auth==='key');
      $('providerKey').disabled=pending || p?.auth==='none';
      if(p)$('providerTitle').textContent=p.name || '新提供商';
      $('providerKeyHint').textContent=p?.apiKey?`已输入 ${p.apiKey.length} 字符 · 最多 4096 字符`:p?.hasKey?'已加密保存；留空沿用同一提供商的 Key':'尚未保存 Key';
      $('providerActive').textContent=(()=>{const active=(latest.providers || []).find(item=>item.id===latest.activeProvider);const activeModel=active?.models.find(item=>item.id===latest.activeModel);return activeModel?`当前使用：${active.name} / ${activeModel.name || activeModel.modelId}`:'尚未选择模型：添加提供商与模型后，点击“设为当前模型”。';})();
    }
    function render(){
      $('providerCount').textContent=drafts.size;
      $('providerList').textContent='';$('providerSelect').textContent='';
      drafts.forEach(p=>{
        const button=document.createElement('button');button.type='button';button.className='provider-nav-item';button.setAttribute('aria-pressed',String(p.id===selected));
        const title=document.createElement('strong');title.textContent=p.name || '新提供商';const note=document.createElement('small');note.textContent=`${p.models.length} 个模型${dirty.has(p.id)?' · 未保存':''}`;
        button.append(title,note);button.onclick=()=>{selected=p.id;model='';deleting=false;render();};$('providerList').append(button);
        $('providerSelect').add(new Option(p.name || '新提供商',p.id));
      });$('providerSelect').value=selected;
      const templates=latest.templates || [];
      if($('providerTemplate').options.length!==templates.length+1){$('providerTemplate').textContent='';$('providerTemplate').add(new Option('选择平台…',''));templates.forEach(p=>$('providerTemplate').add(new Option(`${p.group} · ${p.name}`,p.id)));}
      const p=draft();root.querySelector('.provider-editor').hidden=!p;
      if(!p){renderStatus();return;}
      $('providerTitle').textContent=p.name || '新提供商';
      const preset=templates.find(t=>t.baseUrl===p.baseUrl);$('providerHint').textContent=preset?.note || '自定义地址与协议；读取模型列表失败时可手动填写模型 ID。';
      [['providerName','name'],['providerUrl','baseUrl'],['providerProtocol','protocol'],['providerAuth','auth'],['providerKey','apiKey'],['providerFamily','family']].forEach(([id,field])=>$(id).value=p[field] || '');
      $('providerRemoveKey').checked=p.removeKey;$('providerReuse').checked=p.confirmKeyReuse;
      if(!p.models.some(m=>m.id===model))model=p.models.find(m=>m.id===latest.activeModel)?.id || p.models[0]?.id || '';
      renderModels();renderFetched();renderStatus();
    }
    function renderModels(){
      const p=draft();$('providerModels').textContent='';$('providerModelCount').textContent=p.models.length;
      p.models.forEach(m=>{const button=document.createElement('button');button.type='button';button.textContent=(latest.activeProvider===selected && latest.activeModel===m.id?'★ ':'')+(m.name || m.modelId || '新模型');button.setAttribute('aria-pressed',String(m.id===model));button.onclick=()=>{model=m.id;renderModels();renderStatus();};$('providerModels').append(button);});
      const m=currentModel();$('providerModelFields').hidden=!m;$('providerModelEmpty').hidden=!!m;if(!m)return;
      const levels=p.protocol==='anthropic'?['default','off','low','medium','high','max']:['qwen','thinking'].includes(p.family)?['default','off','high']:p.family==='deepseek'?['default','off','low','high','max']:['default','off','minimal','low','medium','high','xhigh','max'];
      $('modelEffort').textContent='';levels.forEach(level=>$('modelEffort').add(new Option(({default:'提供商默认（不传参数）',off:'关闭（须模型支持）',high:['qwen','thinking'].includes(p.family)?'开启':'High · 高',low:'Low · 低',medium:'Medium · 中'})[level] || level,level)));
      if(!levels.includes(m.effort)){$('modelEffort').add(new Option(m.effort+'（已保存，请核对模型支持）',m.effort));}
      [['modelId','modelId'],['modelName','name'],['modelContext','contextTokens'],['modelOutput','maxTokens'],['modelEffort','effort'],['modelTokenField','maxTokenField']].forEach(([id,field])=>$(id).value=m[field]);
      [['modelEnabled','enabled'],['modelTools','toolsEnabled'],['modelVision','visionEnabled']].forEach(([id,field])=>$(id).checked=m[field]);
      $('modelExtra').value=m.extraText ?? JSON.stringify(m.extra || {},null,2);
      $('modelTokenFieldRow').hidden=p.protocol!=='chat';
      $('modelEffortHint').textContent=p.protocol==='anthropic'?'非默认强度使用 adaptive thinking + output_config.effort，须选支持此功能的模型；旧模型可选默认并在 JSON 中设置 thinking budget。':p.family==='qwen' || p.family==='thinking'?'此适配以开关控制思考；非关闭档位均表示启用，不承诺不同强度。':'选择“提供商默认”兼容性最好。其他档位按协议发送，实际支持范围以模型文档为准。';
      $('modelExtraHint').textContent=p.protocol==='anthropic'?'可填 temperature、top_p、top_k、stop_sequences、thinking、output_config。':p.protocol==='responses'?'可填 temperature、top_p、reasoning、text 等。':'可填 temperature、top_p、seed、reasoning_effort、thinking、enable_thinking 等。不能覆盖消息、模型、工具或认证字段。';
    }
    function addModel(source={}){
      const p=draft();if(!p)return;if(p.models.length>=100){error('单个提供商最多保存 100 个模型。');return;}
      if(source.modelId && p.models.some(m=>m.modelId===source.modelId)){error('此模型 ID 已添加。');return;}
      const context=Math.min(2000000,Math.max(4096,source.contextTokens || 32768));
      const item={id:uuid('model'),modelId:source.modelId || '',name:source.name || '',enabled:true,contextTokens:context,maxTokens:Math.min(source.maxTokens || 2048,context-1024),effort:'default',toolsEnabled:source.toolsEnabled ?? true,visionEnabled:source.visionEnabled ?? false,maxTokenField:'auto',extra:{}};
      p.models.push(item);model=item.id;mark();renderModels();renderStatus();$('modelId').focus();
    }
    function renderFetched(){
      const data=fetched.get(selected);$('providerImport').hidden=!data;const select=$('providerRemoteModels');select.textContent='';if(!data)return;
      const query=$('providerModelSearch').value.toLowerCase();
      data.models.forEach((m,index)=>{if((m.modelId+' '+m.name).toLowerCase().includes(query))select.add(new Option(m.modelId,index));});
      $('providerImportHint').textContent=`读取到 ${data.models.length} 个模型${data.truncated?'（列表已截断，可手动添加其他模型）':''}；选择后添加，再保存。能力未提供时请按模型文档确认。`;
    }
    function payload(){
      const p=clone(draft());p.models.forEach(m=>{m.extra=JSON.parse(m.extraText ?? JSON.stringify(m.extra || {}));delete m.extraText;});return p;
    }
    function saved(response){
      // 服务器随确认返回新版本，避免“保存后立即启用”误用旧版本号。
      const next=response.providerSettings;const other=[...dirty].filter(id=>id!==selected).map(id=>[id,drafts.get(id)]);
      const current=selected, currentModelId=model;
      adopt(next || latest);selected=current;model=currentModelId;
      other.forEach(([id,p])=>{drafts.set(id,p);dirty.add(id);});render();
    }
    function save(after){
      if(pending || busy)return;let value;try{value=payload();}catch(_){error('扩展 JSON 格式无效，请检查后保存。');return;}
      const requestEpoch=epoch;pending=true;error('');renderStatus();
      command('agent_provider_save',{version:baseVersion,provider:value},response=>{if(requestEpoch!==epoch)return;pending=false;saved(response);notify('提供商配置已保存。');if(after)after();},message=>{if(requestEpoch!==epoch)return;pending=false;error(message);renderStatus();});
    }
    function activate(){const requestEpoch=epoch;const p=draft(), m=currentModel();if(!p || !m)return;pending=true;renderStatus();
      command('agent_provider_activate',{version:baseVersion,providerId:p.id,modelId:m.id},response=>{if(requestEpoch!==epoch)return;pending=false;saved(response);notify('当前模型已切换，下一条问题开始使用。');},message=>{if(requestEpoch!==epoch)return;pending=false;error(message);renderStatus();});}
    $('providerSave').onclick=()=>save();
    $('providerUseModel').onclick=()=>dirty.has(selected)?save(activate):activate();
    $('providerFetch').onclick=()=>save(()=>{
      const id=selected, requestEpoch=epoch;reading=true;renderStatus();
      command('agent_provider_models',{providerId:id},response=>{if(requestEpoch!==epoch)return;reading=false;fetched.set(id,response.data);renderFetched();renderStatus();},message=>{if(requestEpoch!==epoch)return;reading=false;error(message);renderStatus();});
    });
    $('providerSelect').onchange=()=>{selected=$('providerSelect').value;model='';deleting=false;render();};
    $('providerAdd').onclick=()=>{
      const template=(latest.templates || []).find(t=>t.id===$('providerTemplate').value);if(!template){error('先选择要添加的平台。');return;}
      const id=uuid('provider');drafts.set(id,{id,name:template.name,baseUrl:template.baseUrl,protocol:template.protocol,family:template.family,auth:'key',apiKey:'',hasKey:false,models:[]});selected=id;model='';mark();render();$('providerName').focus();
    };
    $('providerDelete').onclick=()=>{
      if(!deleting){deleting=true;$('providerDelete').textContent='确认删除提供商及 Key';return;}
      if(!(latest.providers || []).some(p=>p.id===selected)){drafts.delete(selected);dirty.delete(selected);selected=[...drafts.keys()][0] || '';deleting=false;render();return;}
      const requestEpoch=epoch;pending=true;renderStatus();command('agent_provider_delete',{version:baseVersion,providerId:selected},response=>{if(requestEpoch!==epoch)return;pending=false;deleting=false;const remaining=[...dirty].filter(id=>id!==selected).map(id=>[id,drafts.get(id)]);adopt(response.providerSettings,false);remaining.forEach(([id,p])=>{drafts.set(id,p);dirty.add(id);});if(!draft())selected=[...drafts.keys()][0] || '';render();},message=>{if(requestEpoch!==epoch)return;pending=false;error(message);renderStatus();});
    };
    $('providerModelAdd').onclick=()=>addModel();
    $('providerModelDelete').onclick=()=>{draft().models=draft().models.filter(m=>m.id!==model);model='';mark();render();};
    $('providerModelSearch').oninput=renderFetched;
    $('providerImportModel').onclick=()=>{const data=fetched.get(selected);const index=$('providerRemoteModels').value;if(index!=='' && data)addModel(data.models[Number(index)]);};
    [['providerName','name'],['providerUrl','baseUrl'],['providerAuth','auth'],['providerKey','apiKey'],['providerFamily','family']].forEach(([id,field])=>$(id).addEventListener('input',()=>{if(!draft())return;draft()[field]=$(id).value;draft().confirmKeyReuse=false;$('providerReuse').checked=false;mark();if(field==='family'){renderModels();renderStatus();}}));
    $('providerProtocol').onchange=()=>{const p=draft();p.protocol=$('providerProtocol').value;p.confirmKeyReuse=false;$('providerReuse').checked=false;p.models.forEach(m=>{m.extra={};delete m.extraText;m.effort='default';m.maxTokenField='auto';});mark();renderModels();renderStatus();};
    [['providerRemoveKey','removeKey'],['providerReuse','confirmKeyReuse']].forEach(([id,field])=>$(id).onchange=()=>{draft()[field]=$(id).checked;mark();});
    [['modelId','modelId'],['modelName','name'],['modelContext','contextTokens'],['modelOutput','maxTokens'],['modelEffort','effort'],['modelTokenField','maxTokenField']].forEach(([id,field])=>$(id).addEventListener('input',()=>{const m=currentModel();if(!m)return;m[field]=['contextTokens','maxTokens'].includes(field)?Number($(id).value):$(id).value;mark();}));
    [['modelEnabled','enabled'],['modelTools','toolsEnabled'],['modelVision','visionEnabled']].forEach(([id,field])=>$(id).onchange=()=>{currentModel()[field]=$(id).checked;mark();});
    $('modelExtra').oninput=()=>{currentModel().extraText=$('modelExtra').value;mark();};
    return {
      open(value,isBusy){epoch++;pending=false;reading=false;opened=true;busy=isBusy;error(value?.error || '');adopt(value,false);},
      close(){epoch++;pending=false;reading=false;opened=false;drafts.clear();dirty.clear();fetched.clear();root.querySelectorAll('input[type="password"]').forEach(input=>input.value='');},
      sync(value,isBusy){busy=isBusy;const changed=latest.version!==value?.version;latest=value || {};
        if(opened && !dirty.size && !pending && changed)adopt(latest);else if(opened)renderStatus();},
      fail(message){pending=false;reading=false;error(message);if(opened)renderStatus();}
    };
  };
})();
