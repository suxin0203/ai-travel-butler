package com.suxin.travel.traveljava.common;

/**
 * 从大模型回复文本中提取 JSON 的工具。
 * 策略：优先截取 ```json 代码块，其次普通 ``` 代码块，最后兜底截取首个 { 到最后一个 }。
 * 为什么不用正则单一路径：不同模型/温度下回复格式不稳定，多级兜底能把解析成功率拉满，
 * 解析失败的责任在提示词约束之外的部分，必须尽量容错。
 */
public class JsonExtractor {

    public static String extract(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }
        String[] patterns = {
                "```json\\s*([\\s\\S]*?)\\s*```",
                "```\\s*([\\s\\S]*?)\\s*```"
        };
        for (String pattern : patterns) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
            java.util.regex.Matcher m = p.matcher(response);
            if (m.find()) {
                return m.group(1);
            }
        }
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start != -1 && end != -1 && end > start) {
            return response.substring(start, end + 1);
        }
        return null;
    }
}
