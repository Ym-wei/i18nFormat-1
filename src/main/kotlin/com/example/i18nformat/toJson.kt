package com.example.i18nformat

/**
 * 简单的 JSON 解析器，使用正则表达式
 */
class SimpleJsonParser {
    
    /**
     * 解析简单的 JSON 字符串为键值对
     */
    fun parseJson(jsonString: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        
        // 匹配 "key": "value" 形式的键值对
        val keyValueRegex = Regex("""["']([^"']+)["']\s*:\s*["']([^"']*)["']""")
        val matches = keyValueRegex.findAll(jsonString)
        
        for (match in matches) {
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            result[key] = value
        }
        
        return result
    }
    
    /**
     * 通过键获取值
     */
    fun getValue(map: Map<String, String>, key: String): String? {
        return map[key]
    }
}
