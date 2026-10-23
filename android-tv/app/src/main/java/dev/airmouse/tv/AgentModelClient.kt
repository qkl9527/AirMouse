package dev.airmouse.tv

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 协议边界：会话层统一使用 chat 消息/工具结果，网络层负责三种协议转换。
 * 思考块、签名及 Responses reasoning 项只在本轮内回传，不展示、不写入历史。
 * 只有收到完整结束标记才返回工具调用，半截流或上游错误不会触发电视操作。
 */
object AgentModelClient {
    class Failure(message:String):Exception(message)
    data class Reply(val text:String,val calls:JSONArray=JSONArray(),val native:JSONArray?=null,val chatMessage:JSONObject?=null)
    private fun objects(array:JSONArray)=(0 until array.length()).map {array.getJSONObject(it)}
    private fun copy(value:JSONObject)=JSONObject(value.toString())
    private fun check(ok:Boolean,message:String){if(!ok)throw Failure(message)}
    fun endpoint(config:AgentConfiguration.Value)=AgentProviderStore.normalizeBase(config.baseUrl)+when(config.protocol){
        "responses"->"/responses";"anthropic"->"/messages";else->"/chat/completions"
    }
    private fun authorize(http:HttpURLConnection,config:AgentConfiguration.Value){
        http.instanceFollowRedirects=false
        if(config.auth!="none") {
            check(config.key.isNotEmpty(),"此提供商尚未配置 API Key。")
            if(config.protocol=="anthropic")http.setRequestProperty("x-api-key",config.key)
            else http.setRequestProperty("Authorization","Bearer ${config.key}")
        }
        if(config.protocol=="anthropic")http.setRequestProperty("anthropic-version","2023-06-01")
    }
    private fun status(http:HttpURLConnection){
        val code=http.responseCode
        AgentDiagnostics.event("model", "http_status", "code=$code", code !in 200..299)
        check(code in 200..299,when(code){
            401,403->"提供商拒绝访问，请检查该提供商的 Key、地域和模型权限。"
            404->"接口或模型不存在，请检查服务地址、协议和模型 ID。"
            429->"提供商额度不足或请求过于频繁，请检查账户后重试。"
            400,413,422->"提供商未接受参数或内容。请检查模型的图片/工具能力、思考设置、输出上限及扩展 JSON。"
            in 300..399->"接口返回了重定向；为避免 Key 发往另一地址，请按提供商文档手动修改地址。"
            else->"模型服务暂时不可用（HTTP $code）。"
        })
    }
    private fun readJson(http:HttpURLConnection,limit:Int,checkActive:()->Unit):JSONObject {
        val bytes=http.inputStream.use {input->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){checkActive();val count=input.read(buffer);if(count<0)break
                check(out.size()+count<=limit,"提供商返回内容过大，请减少单次输出或手动添加模型。")
                out.write(buffer,0,count)
            };out.toByteArray()
        }
        return try {
            when(val value=org.json.JSONTokener(bytes.toString(Charsets.UTF_8)).nextValue()){
                is JSONObject->value
                is JSONArray->JSONObject().put("data",value)
                else->throw Failure("提供商未返回有效 JSON。")
            }
        }catch(_:Exception){throw Failure("提供商未返回有效 JSON，请检查地址和协议。")}
    }
    fun request(config:AgentConfiguration.Value,chat:JSONArray,stream:Boolean,allowTools:Boolean,withTools:Boolean,
        onPartial:(String)->Unit,onConnection:(HttpURLConnection)->Unit,checkActive:()->Unit):Reply {
        AgentDiagnostics.event("model", "request", "protocol=${config.protocol} stream=$stream tools=${withTools && config.toolsEnabled}")
        val body=body(config,chat,stream,allowTools,withTools).toString().toByteArray(Charsets.UTF_8)
        val http=URL(endpoint(config)).openConnection() as HttpURLConnection
        try {
            onConnection(http);checkActive();authorize(http,config)
            http.requestMethod="POST";http.connectTimeout=15000;http.readTimeout=60000;http.doOutput=true
            http.setRequestProperty("Content-Type","application/json");http.setRequestProperty("Accept",if(stream)"text/event-stream" else "application/json")
            http.setFixedLengthStreamingMode(body.size);http.outputStream.use {it.write(body)};status(http)
            val reply=if(stream && http.contentType.orEmpty().substringBefore(';').trim().equals("text/event-stream",true))
                stream(http,config.protocol,{text->onPartial(if(config.protocol=="chat")visibleChatText(text) else text)},checkActive)
            else parse(readJson(http,2_097_152,checkActive),config.protocol)
            check(reply.text.length<=262144 && reply.calls.length()<=8,"模型回复或工具数量超过上限。")
            return if(config.protocol=="chat")reply.copy(text=visibleChatText(reply.text)) else reply
        } finally {http.disconnect()}
    }

    /** 部分兼容接口把思考放在 content 开头；展示时去除，工具续答仍保留原始 assistant。
     * 流式尚未收齐 <think> 或闭合标签时先不展示，避免手机/电视短暂泄露思考片段。
     */
    private fun visibleChatText(raw:String):String {
        var text=raw.trimStart()
        while(text.startsWith("<think>") || "<think>".startsWith(text)) {
            val end=text.indexOf("</think>")
            if(end<0)return ""
            text=text.substring(end+8).trimStart()
        }
        return text
    }

    /** 只读取已保存地址，绝不从模型列表中的链接跳转；不把 Key 或原始错误体回给网页。 */
    fun models(config:AgentConfiguration.Value):JSONObject {
        val result=JSONArray();var after="";var more=false;val deadline=System.nanoTime()+30_000_000_000L
        val active={check(System.nanoTime()<deadline,"读取模型列表超时，请稍后重试或手动添加。")}
        for(page in 0 until 10){
            val query=if(config.protocol=="anthropic")"?limit=100"+(if(after.isNotEmpty())"&after_id="+java.net.URLEncoder.encode(after,"UTF-8") else "") else ""
            val http=URL(AgentProviderStore.normalizeBase(config.baseUrl)+"/models"+query).openConnection() as HttpURLConnection
            val data=try {active();authorize(http,config);http.connectTimeout=10000;http.readTimeout=15000;status(http);readJson(http,8_388_608,active)}finally{http.disconnect()}
            val list=data.optJSONArray("data") ?: data.optJSONArray("models") ?: throw Failure("该接口没有模型列表，请手动填写模型 ID。")
            for(item in objects(list)){
                if(result.length()>=1000){more=true;break}
                val id=item.optString("id",item.optString("name"))
                if(id.isBlank() || id.length>256 || item.optString("lifecycle")=="retired")continue
                val model=JSONObject().put("modelId",id).put("name",item.optString("display_name",item.optString("name",id)))
                val ctx=item.optInt("context_length",item.optInt("max_input_tokens",0))
                if(ctx>0)model.put("contextTokens",ctx.coerceIn(4096,2_000_000))
                val max=item.optJSONObject("top_provider")?.optInt("max_completion_tokens",0) ?: item.optInt("max_output_tokens",0)
                if(max>0)model.put("maxTokens",minOf(max,8192))
                item.optJSONArray("supported_parameters")?.let {model.put("toolsEnabled",(0 until it.length()).any {i->it.optString(i)=="tools"})}
                item.optJSONObject("architecture")?.optJSONArray("input_modalities")?.let {model.put("visionEnabled",(0 until it.length()).any {i->it.optString(i)=="image"})}
                item.optJSONObject("capabilities")?.let {caps->
                    caps.optJSONObject("vision")?.let {model.put("visionEnabled",it.optBoolean("supported"))}
                }
                result.put(model)
            }
            more=more || data.optBoolean("has_more")
            if(!data.optBoolean("has_more") || result.length()>=1000)break
            val next=data.optString("last_id");if(next.isBlank() || next==after)break;after=next
            if(page<9)more=false
        }
        return JSONObject().put("models",result).put("truncated",more)
    }

    fun validateExtras(protocol:String,extra:JSONObject,maxTokens:Int){
        val shared=setOf("temperature","top_p")
        val allowed=shared+when(protocol){
            "anthropic"->setOf("top_k","stop_sequences","thinking","output_config")
            "responses"->setOf("reasoning","text")
            else->setOf("stop","seed","frequency_penalty","presence_penalty","top_k","min_p","repetition_penalty","reasoning_effort","reasoning","thinking","output_config","enable_thinking","thinking_budget","chat_template_kwargs")
        }
        require(extra.toString().length<=8192 && extra.keys().asSequence().all {it in allowed}){
            "扩展 JSON 包含不支持的字段或超过 8192 字符。不能覆盖模型、消息、工具、认证或流式设置。"
        }
        for(field in listOf("temperature","top_p")) if(extra.has(field)){
            val v=extra.opt(field);val max=if(field=="temperature" && protocol!="anthropic")2.0 else 1.0
            require(v is Number && v.toDouble() in 0.0..max){"$field 必须是 0–$max 的数值。"}
        }
        extra.optJSONObject("thinking")?.let {
            if(it.optString("type")=="enabled" && protocol=="anthropic")require(it.optInt("budget_tokens") in 1024 until maxTokens){"Anthropic thinking budget_tokens 至少为 1024，且小于最大输出 Token。"}
        }
    }
    private fun body(c:AgentConfiguration.Value,chat:JSONArray,stream:Boolean,allowTools:Boolean,withTools:Boolean):JSONObject {
        val tools=withTools && c.toolsEnabled
        val definitions=AgentTools.definitions()
        val body=JSONObject().put("model",c.modelId).put("stream",stream)
        when(c.protocol){
            "responses"->{
                body.put("input",responsesInput(chat)).put("max_output_tokens",c.maxTokens).put("store",false)
                    .put("include",JSONArray().put("reasoning.encrypted_content"))
                if(tools){body.put("tools",JSONArray(objects(definitions).map {copy(it.getJSONObject("function")).put("type","function").put("strict",false)}))
                    .put("tool_choice",if(allowTools)"auto" else "none")}
                if(c.effort!="default")body.put("reasoning",JSONObject().put("effort",if(c.effort=="off")"none" else c.effort))
            }
            "anthropic"->{
                val system=objects(chat).filter {it.optString("role")=="system"}.joinToString("\n") {it.optString("content")}
                body.put("system",system).put("messages",anthropicInput(chat)).put("max_tokens",c.maxTokens)
                // 已有 tool_use/tool_result 的续答保留定义，用 none 禁止再调用；摘要不带工具。
                if(tools)body.put("tools",JSONArray(objects(definitions).map {it.getJSONObject("function").let {fn->
                    JSONObject().put("name",fn.getString("name")).put("description",fn.getString("description")).put("input_schema",fn.getJSONObject("parameters"))}}))
                    .put("tool_choice",JSONObject().put("type",if(allowTools)"auto" else "none"))
                if(c.effort=="off")body.put("thinking",JSONObject().put("type","disabled"))
                else if(c.effort!="default")body.put("thinking",JSONObject().put("type","adaptive")).put("output_config",JSONObject().put("effort",c.effort))
            }
            else->{
                val messages=JSONArray(objects(chat).map {copy(it).apply {remove("_native")}})
                val tokenField=if(c.maxTokenField=="auto") {if(c.family=="openai")"max_completion_tokens" else "max_tokens"} else c.maxTokenField
                body.put("messages",messages).put(tokenField,c.maxTokens)
                if(tools)body.put("tools",definitions).put("tool_choice",if(allowTools)"auto" else "none")
                if(c.effort!="default") when(c.family){
                    "agnes"->{body.put("chat_template_kwargs",JSONObject().put("enable_thinking",c.effort!="off"));if(c.effort!="off")body.put("reasoning_effort",c.effort)}
                    "qwen"->body.put("enable_thinking",c.effort!="off")
                    "thinking","deepseek"->{body.put("thinking",JSONObject().put("type",if(c.effort=="off")"disabled" else "enabled"));if(c.family=="deepseek" && c.effort!="off")body.put("reasoning_effort",c.effort)}
                    "openrouter"->body.put("reasoning",if(c.effort=="off")JSONObject().put("enabled",false) else JSONObject().put("effort",c.effort))
                    else->body.put("reasoning_effort",if(c.effort=="off")"none" else c.effort)
                }
            }
        }
        val extra=JSONObject(c.extraJson);validateExtras(c.protocol,extra,c.maxTokens)
        extra.keys().forEach {body.put(it,extra.get(it))}
        return body
    }
    private fun responsesInput(chat:JSONArray):JSONArray {
        val result=JSONArray()
        for(m in objects(chat)){
            val role=m.optString("role")
            val native=m.optJSONArray("_native")
            if(native!=null){
                for(item in objects(native))when(item.optString("type")){
                    "reasoning"->result.put(copy(item).apply {remove("status")})
                    "function_call"->result.put(copy(item).apply {remove("status")})
                    "message"->result.put(JSONObject().put("role","assistant").put("content",textBlocks(item.optJSONArray("content"),"output_text")))
                };continue
            }
            if(role=="tool"){result.put(JSONObject().put("type","function_call_output").put("call_id",m.getString("tool_call_id")).put("output",m.optString("content")));continue}
            val content=m.opt("content")
            if(content is JSONArray) {
                val blocks=JSONArray(objects(content).map {b->if(b.optString("type")=="image_url")
                    JSONObject().put("type","input_image").put("image_url",b.getJSONObject("image_url").getString("url"))
                    else JSONObject().put("type","input_text").put("text",b.optString("text"))})
                result.put(JSONObject().put("role",role).put("content",blocks))
            }else if(content is String && content.isNotEmpty())result.put(JSONObject().put("role",role).put("content",content))
            for(call in objects(m.optJSONArray("tool_calls") ?: JSONArray()))result.put(JSONObject().put("type","function_call").put("call_id",call.getString("id"))
                .put("name",call.getJSONObject("function").getString("name")).put("arguments",call.getJSONObject("function").getString("arguments")))
        };return result
    }
    private fun anthropicInput(chat:JSONArray):JSONArray {
        val result=JSONArray()
        fun add(role:String,blocks:JSONArray){
            if(blocks.length()==0)return
            val last=result.optJSONObject(result.length()-1)
            if(last?.optString("role")==role)objects(blocks).forEach {last.getJSONArray("content").put(it)}
            else result.put(JSONObject().put("role",role).put("content",blocks))
        }
        for(m in objects(chat)){
            val role=m.optString("role");if(role=="system")continue
            if(role=="tool"){add("user",JSONArray().put(JSONObject().put("type","tool_result").put("tool_use_id",m.getString("tool_call_id")).put("content",m.optString("content"))));continue}
            val native=m.optJSONArray("_native");if(native!=null){add("assistant",native);continue}
            val blocks=JSONArray();val content=m.opt("content")
            if(content is JSONArray)for(b in objects(content)){
                if(b.optString("type")=="image_url"){
                    val url=b.getJSONObject("image_url").getString("url")
                    val source=if(url.startsWith("data:image/jpeg;base64,"))JSONObject().put("type","base64").put("media_type","image/jpeg").put("data",url.substringAfter(','))
                        else JSONObject().put("type","url").put("url",url)
                    blocks.put(JSONObject().put("type","image").put("source",source))
                }else blocks.put(JSONObject().put("type","text").put("text",b.optString("text")))
            }else if(content is String && content.isNotEmpty())blocks.put(JSONObject().put("type","text").put("text",content))
            for(call in objects(m.optJSONArray("tool_calls") ?: JSONArray()))blocks.put(JSONObject().put("type","tool_use").put("id",call.getString("id"))
                .put("name",call.getJSONObject("function").getString("name")).put("input",JSONObject(call.getJSONObject("function").getString("arguments"))))
            add(role,blocks)
        };return result
    }
    private fun textBlocks(blocks:JSONArray?,type:String)=objects(blocks ?: JSONArray()).filter {it.optString("type")==type}.joinToString("") {it.optString("text")}
    private fun call(id:String,name:String,args:String)=JSONObject().put("id",id).put("type","function").put("function",JSONObject().put("name",name).put("arguments",args))
    private fun parse(packet:JSONObject,protocol:String):Reply {
        check(!packet.has("error"),"提供商返回错误，本次未执行新工具。")
        return when(protocol){
            "responses"->{
                check(packet.optString("status")=="completed","模型回答未完整结束，可能达到输出上限；未执行新工具。")
                val output=packet.optJSONArray("output") ?: JSONArray();val calls=JSONArray();val text=StringBuilder()
                for(item in objects(output))when(item.optString("type")){
                    "message"->text.append(textBlocks(item.optJSONArray("content"),"output_text"))
                    "function_call"->calls.put(call(item.getString("call_id"),item.getString("name"),item.getString("arguments")))
                };Reply(text.toString(),calls,output)
            }
            "anthropic"->{
                check(packet.optString("stop_reason") in setOf("end_turn","tool_use","stop_sequence"),"模型回答未完整结束，可能达到输出上限；未执行新工具。")
                val content=packet.optJSONArray("content") ?: JSONArray();val calls=JSONArray()
                for(b in objects(content))if(b.optString("type")=="tool_use")calls.put(call(b.getString("id"),b.getString("name"),b.getJSONObject("input").toString()))
                Reply(textBlocks(content,"text"),calls,content)
            }
            else->{
                val choice=packet.optJSONArray("choices")?.optJSONObject(0) ?: throw Failure("模型响应格式无效。")
                check(choice.optString("finish_reason") in setOf("stop","tool_calls"),"模型回答未完整结束，可能达到输出上限；未执行新工具。")
                val m=choice.getJSONObject("message")
                Reply(m.opt("content") as? String ?: textBlocks(m.optJSONArray("content"),"text"),m.optJSONArray("tool_calls") ?: JSONArray(),chatMessage=m)
            }
        }
    }

    private fun stream(http:HttpURLConnection,protocol:String,onPartial:(String)->Unit,checkActive:()->Unit):Reply {
        val text=StringBuilder();val reasoning=StringBuilder();val details=sortedMapOf<Int,JSONObject>()
        val calls=sortedMapOf<Int,JSONObject>();val blocks=sortedMapOf<Int,JSONObject>();val jsonParts=mutableMapOf<Int,StringBuilder>()
        var final:Reply?=null;var stop="";var lastPublish=0L;var published=0
        fun publish(force:Boolean=false){val now=System.nanoTime();if(text.length!=published && (force || now-lastPublish>=120_000_000)){onPartial(text.toString());published=text.length;lastPublish=now}}
        fun consume(raw:String):Boolean {
            if(raw=="[DONE]")return final!=null
            val p=try {JSONObject(raw)}catch(_:Exception){throw Failure("模型流式格式无效。")}
            check(!p.has("error") && p.optString("type")!="error","模型流式返回错误，未执行新工具。")
            when(protocol){
                "responses"->when(p.optString("type")){
                    "response.output_text.delta"->text.append(p.optString("delta"))
                    "response.completed"->{final=parse(p.getJSONObject("response"),protocol)}
                    "response.failed","response.incomplete"->throw Failure("模型流式未完整结束，请检查输出上限或稍后重试。")
                }
                "anthropic"->when(p.optString("type")){
                    "content_block_start"->{val i=p.getInt("index");check(i in 0..63,"模型内容块过多。");val b=copy(p.getJSONObject("content_block"));blocks[i]=b
                        if(b.optString("type")=="text")text.append(b.optString("text"))}
                    "content_block_delta"->{
                        val i=p.getInt("index");val b=blocks[i] ?: throw Failure("模型内容块顺序无效。")
                        val d=p.getJSONObject("delta")
                        when(d.optString("type")){
                            "text_delta"->{val t=d.optString("text");text.append(t);b.put("text",b.optString("text")+t)}
                            "input_json_delta"->{val buffer=jsonParts.getOrPut(i){StringBuilder()};buffer.append(d.optString("partial_json"));check(buffer.length<=16384,"模型工具参数过长。")}
                            "thinking_delta"->b.put("thinking",b.optString("thinking")+d.optString("thinking"))
                            "signature_delta"->b.put("signature",b.optString("signature")+d.optString("signature"))
                        }
                    }
                    "content_block_stop"->{val i=p.getInt("index");jsonParts.remove(i)?.let {blocks[i]?.put("input",JSONObject(it.toString()))}}
                    "message_delta"->stop=p.optJSONObject("delta")?.optString("stop_reason") ?: stop
                    "message_stop"->{check(jsonParts.isEmpty(),"工具参数尚未完整接收。");final=parse(JSONObject().put("content",JSONArray(blocks.values.toList())).put("stop_reason",stop),protocol)}
                }
                else->{
                    val choice=p.optJSONArray("choices")?.optJSONObject(0)
                    if(choice!=null){
                        val d=choice.optJSONObject("delta") ?: JSONObject()
                        if(d.opt("content") is String)text.append(d.getString("content"))
                        if(d.opt("content") is JSONArray)text.append(textBlocks(d.getJSONArray("content"),"text"))
                        if(d.opt("reasoning_content") is String)reasoning.append(d.getString("reasoning_content"))
                        for(detail in objects(d.optJSONArray("reasoning_details") ?: JSONArray())){
                            val i=detail.optInt("index",0);check(i in 0..63,"思考块过多。")
                            val dest=details.getOrPut(i){JSONObject()};detail.keys().forEach {field->
                                if(field in setOf("text","data","signature"))dest.put(field,dest.optString(field)+detail.optString(field)) else dest.put(field,detail.get(field))}
                        }
                        for(fragment in objects(d.optJSONArray("tool_calls") ?: JSONArray())){
                            val i=fragment.optInt("index",-1);check(i in 0..7,"模型工具数量超出限制。")
                            val target=calls.getOrPut(i){call("","","")}
                            if(fragment.opt("id") is String)target.put("id",target.getString("id")+fragment.getString("id"))
                            if(fragment.has("type"))check(fragment.optString("type")=="function","不支持此工具类型。")
                            fragment.optJSONObject("extra_content")?.let {target.put("extra_content",it)}
                            val fn=fragment.optJSONObject("function");val dest=target.getJSONObject("function")
                            for(field in listOf("name","arguments"))if(fn?.opt(field) is String)dest.put(field,dest.getString(field)+fn.getString(field))
                            check(dest.optString("arguments").length<=16384,"模型工具参数过长。")
                        }
                        if(choice.opt("finish_reason") is String){
                            val m=JSONObject().put("role","assistant").put("content",text.toString()).put("tool_calls",JSONArray(calls.values.toList()))
                            if(reasoning.isNotEmpty())m.put("reasoning_content",reasoning.toString())
                            if(details.isNotEmpty())m.put("reasoning_details",JSONArray(details.values.toList()))
                            final=parse(JSONObject().put("choices",JSONArray().put(JSONObject().put("message",m).put("finish_reason",choice.getString("finish_reason")))),protocol)
                        }
                    }
                }
            }
            check(text.length<=262144 && reasoning.length<=524288 && blocks.values.sumOf {it.toString().length}<=1_048_576,"模型回复过长。")
            publish();return final!=null
        }
        try {readEvents(http,checkActive,::consume)}finally{publish(true)}
        return final ?: throw Failure("模型连接提前结束，已收到的文字保留，未执行新工具。")
    }
    /** SSE 按空行分事件；限总量、行长和事件长度，不能用 readLine 绕过容量限制。 */
    private fun readEvents(http:HttpURLConnection,checkActive:()->Unit,consume:(String)->Boolean){
        http.inputStream.bufferedReader(Charsets.UTF_8).use {reader->
            val line=StringBuilder();val event=StringBuilder();var total=0;var first=true
            fun finishLine():Boolean {
                val value=line.toString().removeSuffix("\r");line.setLength(0)
                if(value.isEmpty()){
                    if(event.isNotEmpty()){val raw=event.toString().trim();event.setLength(0);if(consume(raw))return true}
                }else if(value.startsWith("data:"))event.append(value.substring(5).removePrefix(" ")).append('\n')
                check(event.length<=2_097_152,"模型流式事件过大。");return false
            }
            while(true){checkActive();val ch=reader.read();if(ch<0){if(line.isNotEmpty() && finishLine())return
                if(event.isNotEmpty())consume(event.toString().trim());return}
                check(++total<=16_777_216,"模型流式数据过大。")
                if(first && ch==0xFEFF){first=false;continue};first=false
                if(ch=='\n'.code){if(finishLine())return}else{line.append(ch.toChar());check(line.length<=2_097_152,"模型流式行过长。")}
            }
        }
    }
}
