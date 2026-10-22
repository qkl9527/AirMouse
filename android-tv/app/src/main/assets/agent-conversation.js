/* 两端共用的增量阅读器：只更新变化的文字，不反复销毁图片或打断用户的阅读位置。 */
(() => {
  'use strict';
  window.createAgentConversation = (root, options) => {
    let nodes=[], timer=null, follow=true, previousY=window.scrollY, programmaticUntil=0, initialized=false;
    const motion=window.matchMedia('(prefers-reduced-motion: reduce)');
    const allowed=()=>!options.canScroll || options.canScroll();
    const lastAnswer=()=>[...nodes].reverse().find(item=>item.role==='assistant');
    function bottomTarget(){
      const item=lastAnswer();
      return item ? item.article.getBoundingClientRect().bottom + window.scrollY - window.innerHeight + (options.clearance ? options.clearance() : 32) : null;
    }
    function scrollBottom(smooth=false){
      const target=bottomTarget();
      if(target===null || !allowed()) return;
      programmaticUntil=Date.now()+(smooth?700:100);
      window.scrollTo({top:Math.max(0,target),behavior:smooth && !motion.matches?'smooth':'auto'});
    }
    function tick(){
      timer=null;
      let more=false, changed=false;
      nodes.forEach(item=>{
        if(item.copy.textContent===item.target) return;
        const length=item.copy.textContent.length;
        // 每帧追赶剩余字符的一部分；网络一次到达大量内容时也不会积压几分钟动画。
        let end=motion.matches?item.target.length:Math.min(item.target.length,length+Math.max(1,Math.ceil((item.target.length-length)/8)));
        const code=item.target.charCodeAt(end-1);
        if(code>=0xD800 && code<=0xDBFF) end++; // 避免把 emoji 的 UTF-16 代理对拆开。
        item.copy.textContent=item.target.slice(0,end);
        changed=true;more=more || end<item.target.length;
      });
      if(changed && follow) scrollBottom();
      if(more) timer=setTimeout(tick,24);
    }
    function schedule(){if(timer===null) timer=setTimeout(tick,24);}
    window.addEventListener('scroll',()=>{
      const y=window.scrollY;
      if(allowed() && Date.now()>programmaticUntil){
        if(y<previousY-3) follow=false;
        const target=bottomTarget();
        if(target!==null && y>=target-36) follow=true;
      }
      previousY=y;
    },{passive:true});
    window.addEventListener('wheel',event=>{if(allowed() && event.deltaY<0) follow=false;},{passive:true});
    let touchY=null;
    window.addEventListener('touchstart',event=>{touchY=event.touches.length ? event.touches[0].clientY : null;},{passive:true});
    window.addEventListener('touchmove',event=>{
      const y=event.touches.length ? event.touches[0].clientY : null;
      if(allowed() && touchY!==null && y>touchY+4) follow=false;
      touchY=y;
    },{passive:true});
    return {
      reset(){if(timer!==null)clearTimeout(timer);timer=null;nodes=[];root.textContent='';follow=true;initialized=false;},
      latest(){follow=true;scrollBottom(true);},
      // 输入区尺寸变化只重排正在跟随的会话，不把阅读历史的用户拉回底部。
      layout(){if(follow)scrollBottom();},
      manualScroll(delta){if(delta<0)follow=false;window.scrollBy(0,delta);},
      update(messages,{streaming,busy}){
        const identity=(item,index)=>item.id || `${index}-${item.role}`;
        if(messages.length<nodes.length || nodes.some((item,index)=>identity(messages[index],index)!==item.id)){
          if(timer!==null) clearTimeout(timer);timer=null;root.textContent='';nodes=[];follow=true;
        }
        messages.forEach((message,index)=>{
          let item=nodes[index];
          if(!item){
            const article=document.createElement('article');article.className=options.messageClass+' '+message.role;
            const label=document.createElement(options.roleTag || 'div');label.className=options.roleClass || '';label.textContent=message.kind==='operation'?'AIR MOUSE / 操作结果':message.role==='user'?options.userLabel:(options.assistantLabel || '助手 / 回答');
            const copy=document.createElement(options.copyTag || 'div');copy.className=options.copyClass || '';
            article.append(label,copy);
            if(message.image && message.image.startsWith('data:image/jpeg;base64,')){
              const img=document.createElement('img');img.src=message.image;img.alt='发送给 Agent 的图片';article.append(img);
              img.addEventListener('load',()=>{if(follow)scrollBottom();},{once:true});
            }else if(message.image){
              const note=document.createElement('div');note.className=options.imageLabelClass;note.textContent='已附公开图片链接';article.append(note);
            }
            root.append(article);item={id:identity(message,index),role:message.role,article,copy,target:''};nodes.push(item);
          }
          item.target=message.text || '';
          item.article.classList.toggle('agent-writing',!!busy && message.kind!=='operation' && message.role==='assistant' && index===messages.length-1);
          const animate=initialized && streaming && !motion.matches && message.role==='assistant' && index===messages.length-1 && item.target.startsWith(item.copy.textContent);
          // 历史记录和关闭流式时直接显示；只对最新回答做打字效果。所有内容均用 textContent。
          if(!animate) item.copy.textContent=item.target;
        });
        initialized=true;
        if(follow) requestAnimationFrame(()=>{if(follow)scrollBottom();});
        schedule();
      }
    };
  };
})();
