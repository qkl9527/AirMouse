package dev.airmouse.tv

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI

/** 提供商拥有 Key/地址，模型拥有参数。所有方法由 AgentSession 的锁串行调用。 */
class AgentProviderStore(private val context: Context, legacy: AgentConfiguration.Value) {
    private val file=AtomicFile(File(context.filesDir,"agent-providers.enc"))
    private var data=JSONObject().put("version",0L).put("providers",JSONArray()).put("activeProvider","").put("activeModel","")
    val templates=JSONArray(context.assets.open("agent-providers.json").bufferedReader().use { it.readText() })
    var loadError=""; private set

    init {
        if(file.baseFile.exists() || File(file.baseFile.path+".bak").exists()) {
            runCatching { data=JSONObject(AgentConfiguration.unseal(JSONObject(file.readFully().toString(Charsets.UTF_8)))) }
                .onFailure { loadError="无法解密提供商配置，请检查电视 Keystore；原配置文件保留，未使用明文回退。" }
        } else {
            val model=JSONObject().put("id","agnes-default").put("modelId",legacy.modelId).put("name",legacy.modelId)
                .put("enabled",true).put("contextTokens",legacy.contextTokens).put("maxTokens",legacy.maxTokens)
                .put("effort",legacy.effort).put("toolsEnabled",true).put("visionEnabled",true)
                .put("maxTokenField","auto").put("extra",JSONObject())
            val provider=JSONObject().put("id","agnes").put("name","Agnes AI · 国际").put("baseUrl",legacy.baseUrl)
                .put("protocol","chat").put("family","agnes").put("auth","key").put("apiKey",legacy.key)
                .put("models",JSONArray().put(model))
            data.put("providers",JSONArray().put(provider)).put("activeProvider","agnes").put("activeModel","agnes-default")
            runCatching {
                // 旧密文无法解密时不能把空 Key 当成成功迁移，先保留原文件等待恢复。
                check(legacy.key.isNotEmpty() || !context.getSharedPreferences("agent-model",Context.MODE_PRIVATE).contains("key"))
                persist(data)
            }.onSuccess {
                // 新文件写入成功后再移除旧 Key，避免迁移中断导致凭据丢失。
                context.getSharedPreferences("agent-model",Context.MODE_PRIVATE).edit().remove("key").remove("iv").commit()
            }.onFailure { loadError="提供商配置迁移未完成，请检查电视存储与 Keystore。旧配置仍保留。" }
        }
    }
    private fun persist(next: JSONObject) {
        val bytes=AgentConfiguration.seal(next.toString()).toString().toByteArray(Charsets.UTF_8)
        val out=file.startWrite()
        try { out.write(bytes);file.finishWrite(out) } catch(e: Exception) {file.failWrite(out);throw e}
        data=next
    }
    private fun providers(root: JSONObject=data) = root.getJSONArray("providers")
    private fun find(id: String, root: JSONObject=data): JSONObject? =
        (0 until providers(root).length()).map { providers(root).getJSONObject(it) }.find { it.optString("id")==id }
    fun publicState(): JSONObject {
        val copy=JSONObject(data.toString())
        for(i in 0 until providers(copy).length()) {
            val p=providers(copy).getJSONObject(i)
            p.put("hasKey",p.optString("apiKey").isNotEmpty());p.remove("apiKey")
        }
        return copy.put("templates",templates).put("error",loadError)
    }
    fun current(textSize: Int): AgentConfiguration.Value {
        if(loadError.isNotEmpty()) return AgentConfiguration.Value(modelId="",key="",textSize=textSize)
        val p=find(data.optString("activeProvider"))
            ?: return AgentConfiguration.Value(modelId="",key="",textSize=textSize)
        val models=p.getJSONArray("models")
        val m=(0 until models.length()).map {models.getJSONObject(it)}.find {it.optString("id")==data.optString("activeModel") && it.optBoolean("enabled")}
        return route(p,m,textSize)
    }
    fun forModels(id: String): AgentConfiguration.Value = route(find(id) ?: error("提供商不存在，请先保存。"),null,28)
    private fun route(p: JSONObject,m: JSONObject?,size: Int)=AgentConfiguration.Value(
        key=p.optString("apiKey"),contextTokens=m?.optInt("contextTokens",32768) ?: 32768,
        maxTokens=m?.optInt("maxTokens",2048) ?: 2048,effort=m?.optString("effort","default") ?: "default",textSize=size,
        providerId=p.getString("id"),providerName=p.getString("name"),baseUrl=p.getString("baseUrl"),
        protocol=p.getString("protocol"),family=p.optString("family","generic"),modelId=m?.optString("modelId") ?: "",
        auth=p.getString("auth"),toolsEnabled=m?.optBoolean("toolsEnabled",true) ?: true,
        visionEnabled=m?.optBoolean("visionEnabled",false) ?: false,maxTokenField=m?.optString("maxTokenField","auto") ?: "auto",
        extraJson=m?.optJSONObject("extra")?.toString() ?: "{}")

    /** 修改以版本号比较，防止其他手机的旧草稿覆盖刚保存的 Key/模型。 */
    fun mutate(type: String,json: JSONObject): String? = try {
        require(loadError.isEmpty()) {loadError}
        require(json.optLong("version",-1)==data.getLong("version")) {"配置已由其他手机更新，请关闭设置后重新打开再编辑。"}
        val next=JSONObject(data.toString())
        when(type) {
            "agent_provider_save" -> {
                val p=validate(json.getJSONObject("provider"),find(json.getJSONObject("provider").optString("id")))
                val list=providers(next)
                val index=(0 until list.length()).firstOrNull {list.getJSONObject(it).getString("id")==p.getString("id")}
                require(index!=null || list.length()<24) {"最多保存 24 个提供商。"}
                if(index==null) list.put(p) else list.put(index,p)
            }
            "agent_provider_delete" -> {
                val id=json.getString("providerId")
                val list=JSONArray((0 until providers(next).length()).map {providers(next).getJSONObject(it)}.filter {it.optString("id")!=id})
                next.put("providers",list)
                if(next.optString("activeProvider")==id) next.put("activeProvider","").put("activeModel","")
            }
            "agent_provider_activate" -> {
                val p=find(json.getString("providerId"),next) ?: error("提供商不存在。")
                val models=p.getJSONArray("models")
                val m=(0 until models.length()).map {models.getJSONObject(it)}.find {it.optString("id")==json.optString("modelId") && it.optBoolean("enabled")}
                    ?: error("请先保存并启用此模型。")
                require(p.getString("auth")=="none" || p.optString("apiKey").isNotEmpty()) {"请先保存此提供商的 API Key。"}
                next.put("activeProvider",p.getString("id")).put("activeModel",m.getString("id"))
            }
            else -> error("不支持的提供商操作。")
        }
        next.put("version",data.getLong("version")+1)
        require(next.toString().length<=262144) {"提供商配置总量超过 256K 字符，请减少模型或扩展参数。"}
        try {persist(next)} catch(_: Exception) {error("电视无法加密保存提供商配置，请检查存储与 Keystore。")}
        null
    } catch(e: Exception) {if(e is IllegalArgumentException || e is IllegalStateException) e.message ?: "配置无效。" else "提供商配置格式无效。"}

    fun forgetKey(): String? {
        val p=find(data.optString("activeProvider")) ?: return "尚未选择提供商。"
        val copy=JSONObject(p.toString()).put("removeKey",true).put("apiKey","")
        return mutate("agent_provider_save",JSONObject().put("version",data.getLong("version")).put("provider",copy))
    }
    private fun validate(input: JSONObject, old: JSONObject?): JSONObject {
        fun str(name:String,max:Int)=input.optString(name).trim().also {require(it.length in 1..max){"$name 不能为空或超长。"}}
        val id=str("id",96);require(id.matches(Regex("[A-Za-z0-9_-]+"))) {"提供商编号无效。"}
        val protocol=str("protocol",20);require(protocol in setOf("chat","responses","anthropic")){"不支持此协议。"}
        val base=normalizeBase(str("baseUrl",2048))
        val auth=str("auth",10);require(auth in setOf("key","none")){"认证方式无效。"}
        val family=input.optString("family","generic")
        require(family in setOf("generic","agnes","openai","openrouter","deepseek","qwen","thinking")){"参数适配类型无效。"}
        val supplied=input.optString("apiKey").trim()
        require(supplied.length<=4096 && supplied.all {it.code in 33..126}) {"API Key 须为可打印英文字符，最多 4096 字符。"}
        val remove=input.optBoolean("removeKey") || auth=="none"
        val key=if(remove) "" else supplied.ifEmpty {old?.optString("apiKey") ?: ""}
        if(!remove && supplied.isEmpty() && key.isNotEmpty() && old!=null &&
            (old.optString("baseUrl")!=base || old.optString("protocol")!=protocol)) {
            require(input.optBoolean("confirmKeyReuse")) {"地址或协议已变更，请重新填写 Key，或明确确认将已保存 Key 用于新地址。"}
        }
        val models=input.getJSONArray("models");require(models.length()<=100){"单个提供商最多保存 100 个模型。"}
        val ids=mutableSetOf<String>();val clean=JSONArray()
        for(i in 0 until models.length()) {
            val m=models.getJSONObject(i)
            val mid=m.optString("id");val model=m.optString("modelId").trim()
            require(mid.matches(Regex("[A-Za-z0-9_-]{1,96}")) && ids.add(mid)){"模型编号重复或无效。"}
            require(model.length in 1..256 && model.none {it.isISOControl()}){"模型 ID 无效。"}
            fun integer(name:String,min:Int,max:Int):Int {
                val n=m.opt(name);require(n is Number && n.toDouble()==n.toInt().toDouble() && n.toInt() in min..max){"$name 必须是 $min–$max 的整数。"};return n.toInt()
            }
            val context=integer("contextTokens",4096,2_000_000);val output=integer("maxTokens",128,262144)
            require(output<=context-1024){"上下文至少须为输入预留 1024 Token。"}
            val effort=m.optString("effort","default")
            require(effort in setOf("default","off","minimal","low","medium","high","xhigh","max")){"思考强度无效。"}
            val tokenField=m.optString("maxTokenField","auto")
            require(tokenField in setOf("auto","max_tokens","max_completion_tokens")){"输出 Token 字段无效。"}
            require(!m.has("extra") || m.opt("extra") is JSONObject){"扩展 JSON 必须是对象。"}
            val extra=m.optJSONObject("extra") ?: JSONObject()
            AgentModelClient.validateExtras(protocol,extra,output)
            // 切换协议不能把另一种协议的扩展参数带过去。
            if(old!=null && old.optString("protocol")!=protocol) require(extra.length()==0){"切换协议后请先清空扩展 JSON，再按新协议填写。"}
            clean.put(JSONObject().put("id",mid).put("modelId",model).put("name",m.optString("name",model).take(80).ifBlank {model})
                .put("enabled",m.optBoolean("enabled",true)).put("contextTokens",context).put("maxTokens",output)
                .put("effort",effort).put("toolsEnabled",m.optBoolean("toolsEnabled",true)).put("visionEnabled",m.optBoolean("visionEnabled",false))
                .put("maxTokenField",tokenField).put("extra",extra))
        }
        return JSONObject().put("id",id).put("name",str("name",80)).put("baseUrl",base).put("protocol",protocol)
            .put("auth",auth).put("apiKey",key).put("family",family).put("models",clean)
    }
    companion object {
        fun normalizeBase(raw:String):String {
            var base=raw.trim().trimEnd('/')
            for(suffix in listOf("/chat/completions","/responses","/messages")) if(base.endsWith(suffix)) base=base.removeSuffix(suffix)
            val uri=runCatching {URI(base)}.getOrNull()
            require(uri!=null && uri.scheme=="https" && !uri.host.isNullOrEmpty() && uri.userInfo==null && uri.query==null && uri.fragment==null) {
                "服务地址须为 HTTPS baseURL 或完整 endpoint，不能包含账号、查询参数或片段。"
            }
            return base
        }
    }
}
